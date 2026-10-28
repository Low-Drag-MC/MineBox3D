# Box3D 加一种新形状要碰的全部地方（为 `b3_voxelShape` 做的调研）

日期：2026-10-28。只读调研，没有改任何源码。

**基线**：`native/box3d` 子模块，上游提交 `f36d061`（2026-10-20）。下文所有 `path:line` 都相对 `native/box3d/`，指**未打补丁**的上游源码。MineBox3D 构建前会打 `native/patches/0001-sphere-mesh-flat-edge-ghosts.patch`，它在 `src/mesh_contact.c` 第 915 行之后插入 38 行，所以打过补丁的副本里 `mesh_contact.c` 916 行以后的行号要 +38。

**设想中的新形状**（需求原文的意思）：每个 chunk section 一个 static body（原点在 section 角上、单位旋转），上面一个 16×16×16 的格子形状；每格存「方块碰撞体」表的下标（一个碰撞体 = 格内 0–1 空间里的几个轴对齐盒 + 表面材质）和六个面是否被邻格盖住的位；对动态凸形（球 / 胶囊 / hull）按格内每个盒生成接触，类似 `mesh_contact.c` 按三角形；格子运行时可改，形状包围盒不变。

---

## 0. 先读这三条（它们改变了「断言」的含义）

1. **MineBox3D 里断言不会停下来。** `B3_ASSERT` 在 `B3_ENABLE_ASSERT` 下调用 `b3InternalAssert`（`include/box3d/base.h:133-141`，`src/core.c:72-80`），后者调宿主回调；桥的 `mb3_assert`（`native/src/mb3_core.c:69-88`）记下错误并**返回 0**，于是 Box3D 继续往下执行，Java 调用返回时才抛异常。所以下文凡是「`default: B3_ASSERT(false)` 然后返回空值」的地方，在 MineBox3D 里都等于「记一条错误，带着空值继续跑」，后面常常就是空指针。下文把后果分成三类：
   - **崩**：断言后继续执行会解引用垃圾或空指针；
   - **断言后静默错**：断言一次，然后结果是错的（没碰撞、没画出来、查询漏掉）；
   - **完全静默**：没有断言，直接漏掉。
2. **MineBox3D 的构建**（`native/CMakeLists.txt`）：`BOX3D_DOUBLE_PRECISION ON`（位置是 double，body 内部是 float），`B3_ENABLE_ASSERT` 打开。Box3D 作为子目录引入，`BOX3D_VALIDATE` 选项只在顶层项目里声明（`CMakeLists.txt:110-113`），所以发布库里 `B3_ENABLE_VALIDATION = 0`：所有 `B3_VALIDATE` 和 `b3Validate*` 都是空的。但 Box3D 自己的单元测试 / samples 顶层构建时 VALIDATE 默认打开，在那里会命中校验断言。
3. **没有函数指针表。** 按 `b3ShapeType` 的分派全是手写的 `switch` / `if`，散在约 15 个文件里；接触「注册表」只是两个 bool（§4.1）。漏掉一处**不会**编译失败。

---

## 1. 类型定义与形状存储

### 1.1 枚举 `b3ShapeType` — `include/box3d/types.h:452-474`

- 按字母序：capsule 0、compound 1、height 2、hull 3、mesh 4、sphere 5、`b3_shapeTypeCount` 6。
- `b3_voxelShape` 按字母序正好排在 sphere 之后 = 6，`b3_shapeTypeCount` 变成 7 ⇒ **已有的值都不变**。录制 / 快照按 `(int)type` 写盘（`src/world_snapshot.c:592-627`），追加在末尾不会破坏已有文件。
- `b3_shapeTypeCount` 只在 `src/contact.c:103,108-109,149-150` 用（二维注册表的大小与范围断言），自动跟着变大。
- **要做**：加枚举值。

### 1.2 `b3Shape` 结构 — `src/shape.h:27-70`

- 几何放在匿名 union 里（`src/shape.h:57-65`）：`b3Capsule`（28 字节，最大）、`b3Sphere`、`const b3HullData*`、`b3Mesh`（指针 + vec3 缩放）、`const b3HeightFieldData*`、`const b3CompoundData*`。
- mesh / height / compound **借用用户的数据**（不拷贝，由用户保证寿命，见 `include/box3d/box3d.h:868-885` 的文档）；只有 hull 进 world 的引用计数库（`b3AddHullToDatabase`）。
- 材质：`material`（单个，内联）/ `materials`（多个时的堆数组）/ `materialCount`；统一用 `b3GetShapeMaterials`（`src/shape.h:72-75`）访问。
- 其他与类型相关的字段：`aabbMargin`、`localCentroid`、`userShape`（调试绘制句柄）。
- `sizeof(b3Shape)` 进快照布局哈希（`src/world_snapshot.c:51`）；union 里加一个指针不会让它变大。
- `b3IsConvex`（`src/shape.h:203-206`）只认 sphere / capsule / hull ⇒ 新类型自动被当作「非凸」，这正是想要的。
- **要做**：union 里加 `const b3VoxelData* voxel;`（借用指针，可再带少量内联字段，别超过 28 字节）。

### 1.3 创建 — `src/shape.c`

- `b3CreateShapeInternal`（`92-251`）里的几何 `switch`（`112-164`）：`default: B3_ASSERT(false)`，几何不写入但形状照样建出来 ⇒ **断言后静默错**（一个几何全 0 的形状）。
  - compound 分支顺带断言 static、非 sensor（`118-123`）；mesh 存 `data` 和 `b3SafeScale(scale)`；height 只存指针。
- 同一函数里的 compound 特例：`world->compoundShapeCount += 1`（`189-191`），并把 compound 自己的材质表拷一份（`192-199`）。其他类型：`def->materialCount > 1` 时把 `def->materials` 拷成堆数组（`200-206`），否则内联一个（`207-213`）。
- `forcePairCreation = def->invokeContactCreation && type != compound`（`218`）。
- `b3CreateShape`（`253-310`）：static-only 规则在这里（§2）。
- 公开创建函数：`b3CreateMeshShape`（`405-428`：`B3_VALIDATE(b3IsValidMesh)`、版本 / 哈希检查、录制时 `b3RecInternMesh`），`b3CreateHeightFieldShape`（`430-452`），`b3CreateBakedCompoundShape`（`454-475`）。声明在 `include/box3d/box3d.h:868-885`。
- **要做**：`switch` 加 voxel 分支；新增公开的 `b3CreateVoxelShape`；static / 非 sensor 校验（§2）；录制策略（§7）。

### 1.4 包围盒 / margin / 质心 / 质量 / extent

| 函数 | 位置 | 现状 | voxel |
|---|---|---|---|
| `b3ComputeShapeMargin` | `src/shape.c:27-76` | mesh / height / compound 一组，返回 `B3_MAX_AABB_MARGIN`（`60-68`，注释：static 的 broadphase 用 speculative distance，这个值实际不会被用到）；`default: B3_VALIDATE(false)` | 加进那一组 |
| `b3ComputeShapeAABB` | `src/shape.c:604-633` | 每类型一个 `b3Compute*AABB`；`default: B3_ASSERT(false)`，返回缩成一点的 AABB ⇒ **断言后静默错**（proxy 太小，永远配不上对） | **必须实现**：局部 (0,0,0)–(16,16,16) 经 transform 变换 |
| `b3ComputeFatShapeAABB` | `src/shape.c:635-653` | 双精度下先用只含旋转的 transform 算局部盒、外扩，再平移并向外取整（`638-646`） | 自动走上一行 |
| `b3ComputeSweptShapeAABB` | `src/shape.c:655-676` | 只给凸形（CCD 的快速形状）；`default: B3_ASSERT` | 跳过（voxel 永远不是快速形状） |
| `b3GetShapeCentroid` | `src/shape.c:678-706` | mesh / height / compound 用局部 AABB 中心；`default` 返回 0，无断言 | 建议返回 (8,8,8)；只存进 `localCentroid`，static 用不到 |
| `b3GetShapeArea` / `b3GetShapeProjectedArea` | `src/shape.c:708-752` | 非凸返回 0 | 跳过（只用于风力和爆炸，两者都只作用于动态体） |
| `b3ComputeShapeMass` | `src/shape.c:754-770` | 非凸 `default` 返回零质量 | 跳过 |
| `b3ComputeShapeExtent` | `src/shape.c:772-832` | compound、mesh 有分支（mesh 的注释是「运动学 mesh 睡眠要用」，`815-825`）；height **没有**分支 | 跳过（static body 在 `b3UpdateBodyMassData` 里提前返回，`src/body.c:950-971`） |
| `b3ComputeShapeMinExtent` | `src/shape.c:834-850` | 走 extent | 跳过 |
| `b3MakeShapeProxy` | `src/shape.c:1079-1102` | 只认凸形；`default: B3_ASSERT(false)`，返回 `{NULL, 0, 0}` | 不实现，但要堵住每个可能把 voxel 传进来的调用者（§4.9、§5） |

### 1.5 销毁

- `b3DestroyShapeInternal`（`src/shape.c:478-574`）：compound 计数减一（`482-486`）；从 body 的形状链表摘除；`b3DestroyShapeProxy`；遍历 **body** 的接触链表，销毁属于这个形状的接触（`512-525`）；处理 sensor；`b3DestroyShapeAllocations`。
- `b3DestroyShapeAllocationForShapeChange`（`src/shape.c:1043-1062`）：只有 hull 释放库引用；所有类型都销毁 `userShape`（调试绘制句柄）。
- `b3DestroyShapeAllocations`（`1064-1077`）：再释放 `materials` 堆数组。
- 同样走这条路的：`b3DestroyBody`（`src/body.c:361-...`，compound 计数在 `401-405`）、`b3DestroyWorld`（`src/physics_world.c:458-466`），以及快照恢复的 `b3FreeLiveSimElements`（`src/world_snapshot.c:967-1030`，hull 引用在 `986-990`）。
- **voxel**：如果形状自己拥有内存（例如拷了一份格子数组），必须在 `b3DestroyShapeAllocationForShapeChange` 里释放（`b3Shape_Set*` 换类型时也走这里）；如果像 mesh 一样借用调用方的数据，就什么都不用加。

### 1.6 访问器与换类型 — `src/shape.c`

- `b3Shape_GetType`（`1523-1528`）通用。
- `b3Shape_GetSphere/Capsule/Hull/Mesh/HeightField/Compound`（`1530-1576`）各自断言类型 ⇒ **要加** `b3Shape_GetVoxel`。
- `b3Shape_SetSphere/Capsule/Hull/Mesh`（`1578-1718`）能把任意形状换成该类型：`b3DestroyShapeAllocationForShapeChange` → 改 union 和 `type` → 重算 `aabbMargin` → `b3ResetProxy(wake, destroyProxy)`。注意：这些函数**不重算 `localCentroid`**，**不处理 compound 计数，也不检查 static-only 规则**（把 compound 换成 sphere 后 `compoundShapeCount` 不会减）。
- **要做**：一个「改格子」的 API（例如 `b3Shape_SetVoxelCells`），失效逻辑见 §4.6，不要照抄 `b3Shape_SetMesh`。

### 1.7 校验

- mesh：`b3IsValidMesh`（`src/mesh.c:288/310`，只在 VALIDATE 构建里真查）+ `version`/`hash` 检查（`src/shape.c:407-414`）。height：只查 `version`/`hash`（`432-438`）。compound：只查 `version`（`456-461`），回放载入时另有 `b3ValidateCompound`（`src/replay.c:2492-2497`）。
- voxel：建议做一个便宜的 `b3IsValidVoxel`（下标都在碰撞体表范围内、盒都在 0–1 里）。

---

## 2. static-only 规则

| 位置 | 内容 |
|---|---|
| `src/shape.c:274-278` | `b3CreateShape`：body 不是 static 且类型是 **compound 或 height** ⇒ 直接返回 `b3_nullShapeId`（没有断言）。**mesh 不在其中**：mesh 可以挂在运动学 / 动态 body 上（`b3ComputeShapeExtent` 给「运动学 mesh 睡眠」留了分支；`src/solver.c:544-548` 跳过快速 mesh 的 CCD）。 |
| `src/shape.c:118-123` | compound 再断言 static、非 sensor。height / mesh **可以当 sensor**。 |
| `src/body.c:1615-1627` | `b3Body_SetType` 改成非 static 时，body 上有 compound / height 就 `return`。**上游 bug：这个 `return` 之前 `world->locked` 已经被设成 `true`（`1605`），世界从此锁死**，之后所有 `b3GetUnlockedWorld` 都断言并返回 NULL。 |
| `src/body.c:1764-1772` | `b3Body_SetType` 重建 proxy 的循环里 `B3_ASSERT(type != compound)`。 |
| `src/contact.c:229-230` | 接触的 shapeB 必须是凸形（组合形状永远在 A 侧）；「B 不能是 static」那句被注释掉了，注释说动态 mesh 撞 static 凸形是可能的。 |
| `src/broad_phase.c:115-121,602-610` | 「compound 只能是 static，所以只有 static 交叉那一趟要查 compound」——只在 static 那一半展开 compound 的 pair。 |
| `src/solver.c:380` | CCD 命中的 body 必须是 static，或者快速 body 是 bullet。 |
| 质量 | 非凸形 `b3ComputeShapeMass` 为零（`src/shape.c:767-768`）。 |

`b3Body_SetType` 不拦 mesh：static 改 dynamic 后 mesh 进动态树，可以和 static 凸形产生接触（质量为 0 的动态体，调试绘制显示成红色「bad body」，`src/physics_world.c:1253`）。

**voxel 要做**：把 `b3_voxelShape` 加进 `shape.c:274` 和 `body.c:1620` 两处（顺手修掉 1623 的锁泄漏）；在创建时像 compound 一样拒绝 sensor。动态 voxel、运动学 voxel、sensor voxel 一律不支持。

---

## 3. Broad phase

- **建 proxy**：`b3CreateShapeProxy`（`src/shape.c:1021-1032`）→ `b3UpdateShapeAABBs`（`78-90`）：`shape->aabb` = 形状 AABB 外扩 `B3_SPECULATIVE_DISTANCE`；放进树的 fat AABB，static 再外扩一个 speculative distance（其他类型外扩 `aabbMargin`）→ `b3BroadPhase_CreateProxy`（`src/broad_phase.c:53-62`）：每种 body 类型一棵树（static body ⇒ static 树），proxy key 低 2 位是树类型（`src/broad_phase.h:17-19`）；只有非 static 或 `forcePairCreation` 才把节点标成 moved。
- **⇒ static 形状默认不会触发找 pair**：新载入的 section 正好压在一个**醒着但不动**的物体上时，要等那个物体动了才会有接触。`b3ShapeDef.invokeContactCreation`（`include/box3d/types.h:527-530`）就是为这个准备的（桥已经暴露：`native/src/mb3_defs.c:295,326`）。compound 永远不 force（`src/shape.c:218`）。
- **找 pair**：`b3UpdateBroadPhasePairs`（`src/broad_phase.c:649-790`）：static 树有 moved，或运动学 / 动态树需要重建时才跑；动态树 vs static 树、动态树 vs 运动学树（交叉种子 `b3GatherCrossSeeds`）、动态树自身。**运动学 vs static 从不配对** ⇒ 运动学物体永远碰不到 voxel（和 mesh 一样）。之后清掉 static 树的 moved 标记（`701`）。
- **候选过滤**：`b3FlushCandidatePairs`（`256-349`）：compound 展开成「每个重叠的子形状一个 pair」（`b3EmitCompoundPairs`，`222-254`，用 compound 自带的子形状树）；其他形状按 `b3ShapePairKey(A, B, 0)` 去重后过 `b3ShouldCreatePair`（`144-195`：同 body、sensor、filter、joint、自定义过滤）。pair key = 22 位 shapeA + 22 位 shapeB + 20 位 child（`include/box3d/constants.h:135-139`，`src/table.h:44-53`）。排序后逐个 `b3CreateContact`（`src/broad_phase.c:771`）。
- **运行时改 AABB**：`b3Shape_Set*` → `b3ResetProxy`（`src/shape.c:1352-1401`）重算 AABB 后销毁重建 proxy（`forcePairCreation = true`）或 `MoveProxy`；`b3Body_SetTransform`（`src/body.c:1145-1194`）超出 fat AABB 才 `MoveProxy`。
- **static 树从不自动重建**：每步只重建动态和运动学树（`b3UpdateTreesTask`，`src/broad_phase.c:621-631`）；static 树只能手动 `b3World_RebuildStaticTree`（`src/physics_world.c:3581-3593`）。chunk section 不停进出时 static 树质量会退化。
- **voxel**：包围盒固定 ⇒ 改格子**不用**动 proxy；一对形状一个 pair（childIndex 0，和 mesh 一样）。**不要**走 compound 那套按子形状展开（4096 格 × 若干盒会把 pair set 撑爆，而且 compound 的子形状是 BVH 叶子）。只需要 `b3ComputeShapeAABB` 有分支。

---

## 4. 接触

### 4.1 「注册表」 — `src/contact.c:97-142`

- `struct b3ContactRegister { bool supported; bool primary; }`，`static s_registers[b3_shapeTypeCount][b3_shapeTypeCount]`——**进程级全局**，靠 `s_initialized` 只填一次（`b3CreateWorld` 调 `b3InitializeContactRegisters`，`src/physics_world.c:241`）。
- `b3AddType(t1, t2)`：`[t1][t2]` 为 primary，`[t2][t1]` 非 primary（创建时翻转顺序）。现有：sphere / capsule / hull 两两之间；compound、mesh、height 各对 sphere / capsule / hull。mesh-mesh、mesh-compound 等都没注册 ⇒ 不建接触。
- **没有函数指针**，真正的分派在 `b3UpdateContact` 的 if / else 里（§4.3）。
- **voxel 要做**：`b3AddType(b3_voxelShape, sphere / capsule / hull)` 三行。不加 ⇒ **完全静默**（`b3CreateContact` 在 `152-156` 直接返回，没有碰撞）。加了但不改 `b3UpdateContact` ⇒ **崩**（§4.3）。

### 4.2 建立 / 销毁接触

- `b3CreateContact`（`src/contact.c:145-331`）：
  - `215-226`：A 是 mesh / height，或 A 是 compound 且该子形状是 mesh ⇒ 置 `b3_simMeshContact`。**这个标志就是「多 manifold 接触」的总开关**，它决定：
    - `b3Contact` 的 union 用 `meshContact`（三角形缓存 + 查询盒）还是 `convexContact`（`src/contact.h:146-153`）；
    - 进约束图时放进 `color->contacts`（标量 / wide-mesh 求解，允许多个 manifold）还是 `color->convexContacts`（SIMD 凸形路径，**只能 1 个 manifold**，`src/contact_solver_wide.inl:929` 断言）（`src/constraint_graph.c:147-169`、`171-217`；睡眠时搬运 `src/solver_set.c:361`）；
    - 销毁时是否释放 `triangleCache`（`src/contact.c:434-437`，`src/physics_world.c:476-479`）；
    - 快照是否写三角形缓存（`src/world_snapshot.c:841-965`）；
    - 校验（§9.7）。
  - `229`：断言 B 是凸形。`234-237`：任一 body 是 static ⇒ `b3_contactStaticFlag`（求解用 static 软度，`src/contact_solver.c:135`）。`239`：断言两边都不是 sensor。`241-244`：contact events。`246-249`：两个形状都开 speculative ⇒ `b3_enableSpeculativePoints`。`252-256`：两个 body 都开回收 **且** 接触开了 speculative ⇒ `b3_contactRecycleFlag`。`292-294`：pair key 进 `pairSet`。`300-330`：滚动阻力（按半径）、pre-solve 标志。
- `b3DestroyContact`（`340-480`）：删 pair key、释放 manifold、end touch 事件（只在 touching 时）、摘链、释放三角形缓存、从图 / 集合里移除；`wakeBodies && touching` 才叫醒两个 body。
- **voxel**：在 `215-226` 置 `b3_simMeshContact`——**必须**，否则多 manifold 的接触会进 SIMD 凸形路径。其余全是通用代码，直接复用。

### 4.3 每步更新与分派 — `src/contact.c:736-890` `b3UpdateContact`

- `742`：断言 B 不是 compound。
- compound（`744-839`）：在栈上拷一份 `b3Shape`，把 `type` 换成子形状类型（非 mesh 子形状还换材质），按子类型再分派（凸形子形状可能翻转 A / B）；mesh 子形状走 `b3ComputeMeshManifolds` 并传 `child.materialIndices` 做材质映射；最后把锚点从子形状原点挪到 compound 原点（`825-838`）。
- mesh / height（`840-857`）：`b3ComputeMeshManifolds(..., 材质映射 = NULL, ...)`，再按两个形状的 `enableHitEvents` 置 / 清 `b3_simEnableHitEvent`。
- 否则（`858-863`）：「凸形对凸形」⇒ `b3UpdateConvexContact`（`619-732`）⇒ `b3ComputeConvexManifold`（`482-617`），后者对未知的 A 类型走 `else { B3_ASSERT(typeA == b3_hullShape); ... shapeA->hull ... }`（`515-537`）。**voxel 若掉进这里：断言后把 voxel 的 union 当成 hull 指针用 ⇒ 崩。**
- 共同收尾（`865-887`）：锚点改成相对质心，置 / 清 `b3_simTouchingFlag`。
- **voxel 要做**：加 `else if (shapeA->type == b3_voxelShape)` 分支，调新的 `b3ComputeVoxelManifolds(...)`，照 mesh 分支设 hit-event 标志，保持「touching ⇔ manifoldCount > 0」（`856` 的断言）。

### 4.4 一个接触能有几个 manifold，存在哪

- `contact->manifolds` / `manifoldCount`（`src/contact.h:137-139`），每个 `b3Manifold` 最多 `B3_MAX_MANIFOLD_POINTS = 4` 个点（`include/box3d/constants.h:111-113`，`include/box3d/types.h:2709-2728`）。
- 分配：`b3AllocateManifolds` / `b3FreeManifolds`（`src/physics_world.h:325-361`）：**每种 manifold 个数一个 block allocator**（按需创建），外加一把**全局互斥锁** `manifoldAllocatorMutex`（窄相是并行的）。凸形接触永远 1 个；mesh 接触 = 聚类数，可变，数量变了就整块重新分配（`src/mesh_contact.c:1123-1134`）。
- 约束图里 `b3ContactSpec.manifoldCount` 是 `uint16_t`（`src/contact.h:164-171`），加入图时写（`src/constraint_graph.c:156-163`），之后每步由碰撞任务更新（`src/physics_world.c:755-762`，只对 `b3_simMeshContact` 且这一步前后都 touching 的接触）；求解器按 manifold 个数分桶做 SIMD（`src/solver.c:1575-1640`）。
- 上限：mesh 的候选三角形最多 `B3_MAX_MESH_CONTACT_TRIANGLES = 512`（`include/box3d/constants.h:141-146`），满了打一次日志后静默截断（`src/mesh_contact.c:123-131`，那个 `static bool s_once` 是全局静态变量）。
- **voxel**：全部复用。

### 4.5 特征 id / warm starting / 缓存

- `b3ContactCache` = `b3SATCache`（hull，面 / 边下标）∪ `b3SimplexCache`（胶囊等走 GJK）（`src/contact.h:17-21`）。凸形接触：一个缓存（`b3ConvexContact`，`95-98`）。mesh 接触：`b3MeshContact { b3Array(b3TriangleCache) triangleCache; b3AABB queryBounds; }`（`87-91`），每个候选三角形一个 `{triangleIndex, cache}`（`23-27`），**按 triangleIndex 升序**。
- `b3RefreshCache`（`src/mesh_contact.c:77-169`）：B 的 AABB 还在上次的 `queryBounds` 里 ⇒ 什么都不做（**候选列表不刷新**）；否则 `queryBounds` = B 的 AABB 外扩 `B3_MAX_AABB_MARGIN + B3_SPECULATIVE_DISTANCE`，转到 mesh 局部坐标查三角形（必须升序，`B3_VALIDATE(b3IsSorted)`），再和旧缓存按下标归并（同下标沿用旧缓存，新的清零）。
- 点级 warm start：`(featureId, triangleIndex)` 都相同才继承 `normalImpulse`（`src/mesh_contact.c:1199-1216`）；manifold 级：法线点积 > 0.995 的旧 manifold 继承摩擦 / 滚动 / 扭转冲量（`1146-1184`）。凸形接触只比 `featureId`（`src/contact.c:587-614`）。
- `b3ManifoldPoint.triangleIndex`（`include/box3d/types.h:2700-2701`）是公开字段，命中事件和材质查找也用它。
- **voxel**：用一个整数「特征键」代替 triangleIndex（例如 `cellIndex × 每格盒数上限 + boxIndex`，远小于 2^31）；**候选必须按键升序产生**才能复用归并；每个键一个 `b3ContactCache`（盒对 hull 用 SAT 缓存，对胶囊 / 球用 simplex 缓存）。

### 4.6 静态几何变了，接触怎么失效

**mesh 的做法**：`b3Shape_SetMesh`（`src/shape.c:1683-1718`）→ `b3ResetProxy(wake = true, destroyProxy = true)`（`1352-1401`）：**销毁这个形状的全部接触**（遍历 body 的接触链表按 shapeId 过滤，`1358-1372`），重算 AABB，销毁并重建 proxy 且 `forcePairCreation` ⇒ 下一步重新找 pair，新接触从「未接触、空缓存」开始（这一整片的 warm start 都丢了）。`b3DestroyContact` 只在接触原本 touching 时才叫醒 body（`src/contact.c:475-479`）。

**voxel 为什么不能只改数据**——有四层缓存会让「格子变了」看不见：

1. **接触回收**（`src/physics_world.c:653-737`）：两个 body 都开了回收、接触开了 speculative、物体不快、相对位姿变化小于 `contactRecycleDistance` ⇒ **整步跳过 `b3UpdateContact`**，只按位姿差更新旧点的 separation。静止在方块上的物体，方块被挖掉后会一直被旧接触托着。只有清掉 `b3_relativeTransformValid` 才会重算（质心改变时就是这么做的：`src/body.c:1066-1077`、`1969-1980`）。
2. **候选缓存**：B 没离开 `queryBounds` 就不重新查候选（`src/mesh_contact.c:84-96`）⇒ 新放的方块进不了候选；挖掉的方块还在候选里（数据层面要在窄相里判空）。
3. **睡眠**：睡着的岛的接触根本不更新（touching 接触随岛进睡眠集合，`src/solver_set.c:178-...`；不 touching 的进 disabled 集合，`300-330`）。
4. **每个键的 SAT / simplex 缓存**：同一个键换成了不同的盒，旧缓存里的面 / 边下标会被拿来用（盒都是 6 个面，下标不会越界，但第一步可能用错特征）。

**voxel 要做**（改格子的 API，只能在 step 之外调，用 `b3GetUnlockedWorld`）：遍历这个 static body 的接触链表——一个 section 一个 body 一个形状 ⇒ 链表就是这个形状的全部接触，**包括睡着的和不 touching 的**（fat AABB 重叠期间接触一直存在）——对 B 的 `shape->aabb` 和改动格子相交的接触：清 `b3_relativeTransformValid`、把 `meshContact.queryBounds` 置空（强制重查）、清掉该键的缓存（或整个 triangleCache）、对另一侧 body 调 `b3WakeBody`。不需要销毁接触，也不需要动 proxy。

两个退路：
- 照抄 `b3Shape_SetMesh` 销毁全部接触：简单，但丢掉整片的 warm start，而且叫不醒「还没接触、马上要接触」的睡眠物体（新放的方块嵌进一个睡着的物体里）。
- section 的 static body 关掉接触回收（`b3BodyDef.enableContactRecycling`，桥已暴露：`native/src/mb3_defs.c:225,271`）：省掉第 1 层，代价是 section 上所有接触每步都重算；第 2–4 层仍要处理。

### 4.7 事件

- begin / end touch：`b3Collide` 的状态处理（`src/physics_world.c:938-1030`）和 `b3DestroyContact`（`src/contact.c:361-384`），通用。
- hit：mesh 分支置 `b3_simEnableHitEvent`（`src/contact.c:847-854`）；求解器生成事件时取接近最快那个点的 `triangleIndex`，材质 id 用 `b3GetShapeUserMaterialId(shapeA, childIndex, triangleIndex)`（`src/solver.c:2085-2156`，`src/shape.c:2403-2441`）⇒ voxel 要在 `b3GetShapeUserMaterialId` 里加分支（键 → 碰撞体 → 材质），否则落到 `materials[0]`（**完全静默**，材质 id 错）。
- **pre-solve**：只有凸形路径调 `preSolveFcn`（`src/contact.c:700-720`）；**mesh / height / compound 的 mesh 子形状接触从不调**（`b3ComputeMeshManifolds` 里没有）⇒ voxel 照 mesh 可以不做。但 CCD 对任何形状都调（`src/solver.c:439-445`），而且**没检查 `preSolveFcn` 是否为 NULL**：形状开了 `enablePreSolveEvents` 却没设回调 ⇒ 调空函数指针。

### 4.8 speculative 与 `enableSpeculativeContact`

- 形状标志 `b3_enableSpeculative`（`src/shape.h:24`，`src/shape.c:180`），接触标志 `b3_enableSpeculativePoints`（`src/contact.c:246-249`）。只影响两件事：mesh 的三角形对 hull 路径（`src/mesh_contact.c:568,582,755`；`src/triangle_manifold.c:751,767,867,884`：关掉时丢掉 separation > 0 的点），以及接触回收资格（`src/contact.c:252-256`）。凸形对凸形（包括 `b3CollideHulls`）**总是**产生 `B3_SPECULATIVE_DISTANCE` 以内的推测点，不看这个标志。
- `b3World_EnableSpeculative`（`src/physics_world.c:3595-3606`）只写 `world->enableSpeculative`，**整个模拟没有地方读它**（只有快照存取，`src/world_snapshot.c:511,540`）——是个空开关。
- mesh 点的 separation 统一减去 `B3_MESH_REST_OFFSET`（`src/mesh_contact.c:567,1194`；常量 `include/box3d/constants.h:77-81`）。voxel 要不要用 rest offset 是设计选择（它帮的是 hull 对三角形；盒对盒未必需要）。

### 4.9 连续碰撞（TOI）、快速物体、bullet

- 哪些 body 做 CCD：`src/solver.c:782-812`：动态体 + 开了 continuous + 本步位移 > `safetyFactor × minExtent` ⇒ `b3_isFast`；非 bullet 当场 `b3SolveContinuous`，bullet 稍后统一做。
- `b3SolveContinuous`（`src/solver.c:490-...`）：遍历快速 body 的每个形状；**快速形状是 mesh / height 的跳过**（`544-548`）；sensor 跳过；非 bullet 再用 `b3IsShapeFast`（`460-487`，非凸 `default` 返回 true）判断；用扫掠 AABB 查 **static 树**（`566`），bullet 还查运动学 / 动态树（`568-572`）。
- 快速标志在窄相里也有作用：禁止接触回收（`src/physics_world.c:648-654`），mesh 对 hull 时清掉缓存的边轴（`src/mesh_contact.c:749-752`）。
- 回调 `b3ContinuousQueryCallback`（`src/solver.c:336-457`）：过滤后调 `b3ShapeTimeOfImpact(shape = 被撞的形状, fastShape, ...)`（`416`）。
- `b3ShapeTimeOfImpact`（`src/shape.c:2274-2396`）：
  - compound（`2279-2308`）：`b3QueryCompound` + `b3CompoundTimeOfImpactFcn`（`2183-2272`，子形状 switch，mesh 子形状再走 mesh 回调）。
  - mesh / height（`2310-2365`）：假设 A 是 static；把快速形状的扫掠 AABB 转到 mesh 局部，`b3QueryMesh` / `b3QueryHeightField` + `b3MeshTimeOfImpactFcn`（`2110-2166`）：**背面剔除**（质心起点在三角形平面后面就跳过）、「已经在前面、近乎平行移动」就跳过、三角形当 3 点 proxy 做 `b3TimeOfImpact`、fraction == 0 时退回质心小球（`usedFallback`）。
  - 否则（`2367-2395`）：断言 B 不是组合形状，两边都 `b3MakeShapeProxy`。**voxel 若掉进这里：`b3MakeShapeProxy` 断言后返回 `{NULL, 0}`，`b3TimeOfImpact` 读 `points[...]` ⇒ 崩。**
- `b3_featureTriangleFace` 不在 CCD 里用；它是 mesh 接触里「面接触直接接受」的分类（`src/mesh_contact.c:783`）。
- **voxel 要做**：`b3ShapeTimeOfImpact` 加 voxel 分支。两种做法：
  - (a) 每个候选盒做 8 点 proxy 的 `b3TimeOfImpact`；
  - (b) 只枚举**露出的面**（用覆盖位），拆成三角形喂给现成的 `b3MeshTimeOfImpactFcn`（它在 `shape.c` 里是 static，voxel 分支写在同一个文件就能直接用），自动得到背面剔除和退回逻辑，不会在两个相邻实心盒的接缝上产生假 TOI。推荐 (b)。

---

## 5. 查询

所有世界查询都是「三棵树 × 对每个命中的 proxy 调一个按类型分派的函数」；body 查询是「遍历 body 的形状 × 同一个分派函数」。所以分派函数有了 voxel 分支，上层全部自动生效。

| 入口 | 位置 | 走哪个分派 | 没有 voxel 分支时 |
|---|---|---|---|
| `b3World_CastRay` / `RayCastCallback` | `src/physics_world.c:3011-3069` / `2960-3009` | `b3RayCastShape` | **完全静默**（漏掉） |
| `b3World_CastRayClosest` | `src/physics_world.c:3092-...` | 同上 | 同上 |
| `b3World_CastShape` / `b3ShapeCastCallback` | `3211-3293` / `3160-3209` | `b3ShapeCastShape` | **完全静默** |
| `b3World_CastMover` / `MoverCastCallback` | `3341-3411` / `3295-3339` | `b3ShapeCastShape`（fraction == 0 视为已重叠，忽略） | **完全静默** |
| `b3World_OverlapShape` / `b3TreeOverlapCallback` | `2803-2853` / `2770-2801` | `b3OverlapShape` | 断言后静默错（当成不重叠） |
| `b3World_OverlapAABB` / `TreeQueryCallback` | `2714-2758` / `2691-2712` | 不调形状，只比树里的 AABB | 不用改；但语义是「section 的盒相交就报」，哪怕那片全是空气 |
| `b3World_CollideMover` / `TreeCollideCallback` | `2902-2945` / `2865-2900` | `b3CollideMover`（缓冲 64 个平面） | 断言后 0 个平面 |
| `b3Shape_RayCast` | `src/shape.c:1194-1221` | `b3RayCastShape` | 完全静默 |
| `b3Body_CastRay` / `b3Body_CastShape` / `b3Body_OverlapShape` | `src/body.c:596-660` / `662-725` / `727-758` | 同上三个 | 同上 |
| `b3Body_CollideMover` / `b3Body_TimeOfImpactMover` / `b3Body_GetClosestPoint` | `src/body.c:760-812` / `814-...` / `546-594` | 先过滤，只处理 sphere / capsule / hull | 跳过（符合预期） |
| `b3Shape_GetClosestPoint` | `src/shape.c:1861-1885` | **不过滤**，直接 `b3MakeShapeProxy` | **崩**（mesh / height / compound 今天就会崩） |

分派函数（都在 `src/shape.c`，先把输入转到形状局部，输出再转回来）：

- `b3RayCastShape`（`852-886`）：`default: return output;`（**没有断言**）。
- `b3ShapeCastShape`（`888-935`）：proxy 点数截到 `B3_MAX_SHAPE_CAST_POINTS`；`default: return output;`（**没有断言**）。
- `b3OverlapShape`（`937-964`）：`default: B3_ASSERT(false); return false;`。
- `b3CollideMover`（`966-1019`）：`default: B3_ASSERT(false)`；末尾把每个平面的 `materialIndex` 夹到 `[0, shape->materialCount - 1]`（`1015`）。

每种类型的底层函数（声明在 `include/box3d/collision.h:516-595`），voxel 要各写一个：

- 射线：`b3RayCastMesh`（`src/mesh.c:1945-2068`）、`b3RayCastHeightField`（`src/height_field.c:593-603`，就是半径 0 的 shape cast）、`b3RayCastCompound`（`src/compound.c:841-852`）。
- shape cast：`b3ShapeCastMesh`（`src/mesh.c:2070-2235`）、`b3ShapeCastHeightField`（`src/height_field.c:605-...`，**格子 DDA**，最接近 voxel 的模板）、`b3ShapeCastCompound`（`src/compound.c:918-946`）。
- 重叠：`b3OverlapMesh`（`src/mesh.c:1837-1935`，每个三角形 `b3ShapeDistance` < 0.1 slop）、`b3OverlapHeightField`（`src/height_field.c:1034-...`）、`b3OverlapCompound`（`src/compound.c:752-779`）。
- mover：`b3CollideMoverAndMesh`（`src/mesh.c:2237-2370`，**背面剔除** + 每个三角形一次距离 → 平面）、`...HeightField`（`src/height_field.c:1202-...`）、`...Compound`（`src/compound.c:1188-...`）。
- 区域枚举：`b3QueryMesh`（`src/mesh.c:2372-...`）、`b3QueryHeightField`（`src/height_field.c:1136-1200`：行列二重循环、下标单调递增、空洞跳过——voxel 的候选枚举照这个写）、`b3QueryCompound`（`src/compound.c:955-...`）。

输出字段约定（`b3CastOutput`，`include/box3d/types.h:1439-1464`；`b3PlaneResult`，`1843-1860`）：`triangleIndex`（mesh / height 的三角形）、`childIndex`（compound 的子形状）、`materialIndex`（**是 `shape->materials` 的下标**）。voxel 应把特征键放进 `triangleIndex`（或 `childIndex`）；`materialIndex` 见 §9.6。

每个盒可以直接用的现成函数：`b3RayCastHull`（`src/hull.c:2643-2709`，起点在盒内时返回 fraction 0 的命中）、`b3ShapeCastHull`（`2711-2725`，内部就是 `b3ShapeCast`，它的 `b3ShapeCastPairInput.transform` 可以直接带上格子偏移）、`b3OverlapHull(shape, shapeTransform, proxy)`（`2628-2641`，自带 transform）、`b3CollideMoverAndHull`（`2727-...`）。注意盒与盒之间的内部接缝：用盒做 mover / shape cast 会在接缝处产生「台阶」平面，所以和 TOI 一样，**用露出的面（覆盖位）做单面三角形**更接近 mesh 的行为。

---

## 6. Sensor

- sensor 形状可以挂在 static body 上，每步查三棵树（`src/sensor.c:233-235`）。
- voxel 作为 **sensor**：`b3OverlapSensor`（`src/sensor.c:28-71`）按 sensor 形状的类型分派，mesh / height 可以当 sensor，compound 不行（`src/shape.c:121`）；`default: B3_ASSERT(false); return false`。
- voxel 作为 **访客**：`b3SensorQueryCallback` 用 `b3IsConvex` 把非凸形状全部跳过（`src/sensor.c:110-114`；文档 `include/box3d/types.h:515`「只有凸形能当访客」）⇒ 自动跳过。
- CCD 也会碰到 static 树里的 sensor（`src/solver.c:363-367,417-433`）；只要 voxel 不能当 sensor 就无关。
- **voxel**：禁止当 sensor（照 compound，在创建时拒绝）；访客路径不用改。

---

## 7. 录制 / 回放

- **不能编译期关掉**：`recording.c`、`replay.c`、`world_snapshot.c` 总是编进库；是否录制看运行时 `world->recording != NULL`（`B3_REC` / `B3_REC_CREATE` 宏，`src/recording.h:295-317`；大几何的建形状直接写 `if (world->recording != NULL)`，例如 `src/shape.c:420-425`）。桥（`native/src/*.c`）**目前不调** `b3World_StartRecording`。
- **开始录制会序列化整个世界**：`b3World_StartRecording`（`src/physics_world.c:2323-2334`）→ `b3StartRecordingIntoBuffer`（`src/recording.c:983-1033`）→ `b3SerializeWorld`（`src/world_snapshot.c:1032-...`）→ `b3SerShapes`（`548-638`）按类型写几何：hull / mesh / height / compound 各 intern 一个几何 blob；`default: B3_ASSERT(false); 写 -1`（`628-634`）。回放时 `b3DesShapes`（`640-835`）遇到未知类型 `r->ok = false` ⇒ 整个文件载入失败。
- 几何登记：`b3GeometryKind`（`src/recording.h:89-95`：hull / mesh / heightField / compound），`b3RecInternHull/Mesh/HeightField/Compound`（声明 `src/recording.h:369-373`，实现 `src/recording.c:1150-1175`：按内容哈希去重、整块拷贝）。
- 操作码：`src/recording_ops.inl:80-83`（CreateHull / Mesh / HeightField / CompoundShape），`100-102`（ShapeSetMeshMaterial / SetHull / SetMesh）；回放分派 `src/replay.c:1013-1076`（建形状）、`1108-1175`（Set*）；载入登记表时只校验 compound（`src/replay.c:2492-2497`）。
- 查询录制只记 shape id 和结果（`b3RecCastTrampoline` 等，`src/recording.c:623-669`），与类型无关。
- **voxel 两个选项**：
  - (a) 不支持录制：在 `b3World_StartRecording` 里发现 voxel 形状就拒绝（断言并返回），在 `b3CreateVoxelShape` / 改格子 API 里 `world->recording != NULL` 时断言。代价最小。
  - (b) 完整支持：新的 `b3GeometryKind`、intern 函数、`CreateVoxelShape` / `ShapeSetVoxelCells` 操作码 + 回放分派 + `b3SerShapes` / `b3DesShapes` 分支。格子会变 ⇒ 每次改都要录一个操作，快照里存当前格子。
  - 推荐 (a)，等真的需要回放再做 (b)。

---

## 8. 调试绘制

- `b3World_Draw`（`src/physics_world.c:1371-...`）用 `drawingBounds` 查三棵树，回调 `DrawQueryCallback`（`1217-1369`）：颜色由 body 状态决定（材质 0 的 `customColor` 优先，`1238-1243`）；第一次画某个形状时调用户的 `createDebugShape(b3DebugShape*)` 建句柄，存进 `shape->userShape`；`switch (shape->type)`（`1325-1355`）填 `b3DebugShape` 的 union；`default: B3_ASSERT(false)` ⇒ 不建句柄、不画（断言后静默）。之后每次调 `DrawShapeFcn(userShape, transform, color)`。
- `b3DebugShape`（`include/box3d/types.h:3037-3055`）：`type` + 指针 union（capsule / compound / heightField / hull / mesh / sphere）。
- 几何变了如何让句柄重建：`b3DestroyShapeAllocationForShapeChange` 调 `destroyDebugShape` 并清空 `userShape`（`src/shape.c:1057-1061`），下一次绘制时重建。快照恢复也会释放句柄（`src/world_snapshot.c:821-834`）。
- samples 的渲染（`samples/gfx/debug_adapter.c` 等）和测试（`test/test_compound.c`）也按类型 switch，但不编进库。
- 桥目前不用 `b3World_Draw`（MineBox3D 的调试绘制在 Java 侧）。
- **voxel**：要用 `b3World_Draw` 就给 `b3DebugShape` 加 `const b3VoxelData* voxel`、`DrawQueryCallback` 加分支，并在改格子 API 里销毁 `userShape`。否则可以跳过（只会断言一次，不画）。

---

## 9. 其他

### 9.1 岛 / 睡眠

- 岛和睡眠代码里没有按形状类型的分支（`src/island.c`、`src/solver_set.c` 只区分 static body）。static body 不在岛里，但它的 touching 接触作为岛的接触一起睡（`src/solver_set.c:178-...`），不 touching 的进 disabled 集合（`300-330`）。
- `b3WakeBody` 对 static body 没有作用 ⇒ 改格子时要叫醒的是**另一侧**的 body（§4.6）。

### 9.2 爆炸 — `src/physics_world.c:3459-3579`

- 只查**动态树**（`3576`），回调断言 body 是动态（`3474`）并用 `b3MakeShapeProxy` ⇒ static voxel 永远碰不到。跳过。

### 9.3 `b3World_DumpShapeBounds`

- 只在 `include/box3d/box3d.h:269` 声明，**`src/` 里没有实现**（调用会链接失败）。不用处理。

### 9.4 角色 mover

- `src/mover.c` 只有 `b3SolvePlanes` / `b3ClipVector`，与类型无关。和形状有关的都在 `b3CollideMover` 和 `b3World_CastMover`（§5）。

### 9.5 质量

- `b3UpdateBodyMassData`（`src/body.c:925-1077`）：非动态 body 提前返回（运动学还会算 extent）；`b3Body_ApplyMassFromShapes`（`1992-...`）、`b3Body_SetMassData`（`1890-1981`）同理。static voxel 不用处理。

### 9.6 表面材质（隐藏耦合）

- 创建时每个形状把 `def->materials` 拷成自己的堆数组（`src/shape.c:200-206`）；`b3SurfaceMaterial` 是 40 字节。
- 所有「从结果拿材质」的地方都写死了「`materialIndex` 是本形状 `materials` 的下标」：
  - 世界射线 / shape cast：`src/physics_world.c:2991-2992`、`3191-3192`；
  - body 射线 / shape cast：`src/body.c:642-643`、`707-708`；
  - mover 平面：`src/shape.c:1015`；
  - 命中事件：`b3GetShapeUserMaterialId`（`src/shape.c:2403-2441`：mesh 用三角形的材质下标，height 用 `triangleIndex >> 1`，compound 经子形状的 `materialIndices` 再间接一层）；
  - 接触摩擦 / 恢复：mesh 路径按每个接触点的三角形材质求平均（`src/mesh_contact.c:1220-1295`，经 `world->frictionCallback` / `restitutionCallback`），凸形路径只用 `materials[0]`（`src/contact.c:646-653`）；
  - 调试颜色：`materials[0].customColor`（`src/physics_world.c:1238`）。
- 公开 API 都是按 `materials` 数组走的通用实现：`b3Shape_GetMeshMaterialCount`（`src/shape.c:1313-1318`）、`b3Shape_SetMeshMaterial`（`1320-1335`，断言非 compound）、`b3Shape_GetMeshSurfaceMaterial`（`1337-1343`）、`b3Shape_SetFriction / SetRestitution / SetSurfaceMaterial`（`1258-1304`，断言非 compound，只改 `[0]`）。
- **voxel 两条路**：
  - (a) 把碰撞体的材质放进每个 voxel 形状自己的 `materials`（通过 `def->materials`），碰撞体条目里存材质下标 ⇒ 上面所有地方零改动；但每个 section 都拷一份（例如 256 种 × 40 B = 10 KB / section，几千个 section 就是几十 MB）。
  - (b) 碰撞体表共享（放在 `b3VoxelData` 里，或 world 级注册），voxel 在上面约 8 处各加一个分支。

### 9.7 其他零散分支

- `b3Shape_ApplyWind`（`src/shape.c:1905-1909`）：非凸直接返回。跳过。
- `b3IsShapeFast`（`src/solver.c:460-487`）：只给快速形状。跳过。
- 世界销毁 / body 销毁里的 compound 计数（`src/physics_world.c:458-466`，`src/body.c:401-405`）：只和 compound 有关。
- `world->compoundShapeCount`：只用来决定 broadphase 要不要展开 compound（`src/broad_phase.c:693`）。voxel 不用。
- 校验 `b3ValidateContacts`（`src/physics_world.c:4079-4198`）：对 `b3_simMeshContact` 接触检查三角形缓存下标，只认 mesh / height，`else` 断言是 compound 且子形状是 mesh（`4178-4187`）⇒ **voxel 接触在 VALIDATE 构建里会断言**（发布库不编译这段，Box3D 自己的测试构建会触发）。要加分支。
- 快照布局哈希（`src/world_snapshot.c:43-70`）含 `sizeof(b3Shape)`、`sizeof(b3Contact)`；如果给 `b3MeshContact` 加字段（例如格子版本号），哈希会变——fork 里无所谓。

### 9.8 grep 全部命中的归属

`b3_meshShape | b3_heightShape | b3_compoundShape | b3_shapeTypeCount`，范围 `src/` + `include/`：

| 命中 | 所在函数 | 节 | voxel |
|---|---|---|---|
| `include/box3d/types.h:458,461,467,473` | 枚举 | §1.1 | 加值 |
| `src/body.c:401` | `b3DestroyBody` 的 compound 计数 | §1.5 | 无 |
| `src/body.c:1620` | `b3Body_SetType` 拒绝 | §2 | 加 voxel + 修锁泄漏 |
| `src/body.c:1766` | `b3Body_SetType` 断言 | §2 | 可加 |
| `src/broad_phase.c:275-276` | `b3FlushCandidatePairs` 展开 compound | §3 | 无 |
| `src/compound.c:180` | `b3GetCompoundChild` | compound 内部 | 无 |
| `src/compound.c:725,811,888,1158` | compound 的 overlap / ray / shape cast / mover 子形状 switch | compound 内部 | 无（voxel 不当子形状） |
| `src/compound.c:1011` | 在 `#if 0`（`967-1104`）里 | 死代码 | 无 |
| `src/contact.c:103,108,109,149,150` | 注册表大小与断言 | §4.1 | 自动 |
| `src/contact.c:128-139` | 注册 | §4.1 | 加 3 行 |
| `src/contact.c:215,219,222` | 置 `b3_simMeshContact` | §4.2 | 加 voxel |
| `src/contact.c:742,744,756,787` | `b3UpdateContact` 的 compound 分支 | §4.3 | 无 |
| `src/contact.c:840` | `b3UpdateContact` 的 mesh / height 分支 | §4.3 | 加 voxel 分支 |
| `src/mesh_contact.c:79,86,113,119,163` | `b3RefreshCache` | §4.5 / §10 | 不能直接复用（会把 voxel 当 height field 读 ⇒ 崩），照着写 |
| `src/mesh_contact.c:532,593,642,722,728,1232,1249` | `b3ComputeMeshManifolds` | §10 | 同上 |
| `src/physics_world.c:1331,1335,1343` | `DrawQueryCallback` | §8 | 可选 |
| `src/physics_world.c:4160,4169,4180,4182` | `b3ValidateContacts` | §9.7 | 加分支 |
| `src/sensor.c:55,58,64` | `b3OverlapSensor` | §6 | 禁止当 sensor 即可 |
| `src/shape.c:60-62` | `b3ComputeShapeMargin` | §1.4 | 加进组 |
| `src/shape.c:118,150,157` | `b3CreateShapeInternal` | §1.3 | 加分支 |
| `src/shape.c:189` | compound 计数 + 材质 | §1.3 | 视材质方案 |
| `src/shape.c:218` | `forcePairCreation` | §3 | 无 |
| `src/shape.c:274` | static-only | §2 | 加 voxel |
| `src/shape.c:416,440,463` | 公开创建函数 | §1.3 | 新函数 |
| `src/shape.c:482` | 销毁时 compound 计数 | §1.5 | 无 |
| `src/shape.c:611,614,620` | `b3ComputeShapeAABB` | §1.4 | **必须** |
| `src/shape.c:684,693,698` | `b3GetShapeCentroid` | §1.4 | 建议 |
| `src/shape.c:789,815` | `b3ComputeShapeExtent` | §1.4 | 无 |
| `src/shape.c:864,873,876` | `b3RayCastShape` | §5 | **必须** |
| `src/shape.c:909,913,921` | `b3ShapeCastShape` | §5 | **必须** |
| `src/shape.c:945,948,954` | `b3OverlapShape` | §5 | **必须** |
| `src/shape.c:986,998,1002` | `b3CollideMover` | §5 | **必须** |
| `src/shape.c:1264,1281,1302,1331` | SetFriction / SetRestitution / SetSurfaceMaterial / SetMeshMaterial 断言非 compound | §9.6 | 视材质方案 |
| `src/shape.c:1558,1566,1574` | GetMesh / GetHeightField / GetCompound | §1.6 | 新 `GetVoxel` |
| `src/shape.c:1709` | `b3Shape_SetMesh` | §4.6 | 新的改格子 API |
| `src/shape.c:2212` | `b3CompoundTimeOfImpactFcn` 的 mesh 子形状 | §4.9 | 无 |
| `src/shape.c:2279,2310,2355,2359,2367` | `b3ShapeTimeOfImpact` | §4.9 | **必须** |
| `src/shape.c:2411,2419,2423,2426` | `b3GetShapeUserMaterialId` | §4.7 / §9.6 | 加分支 |
| `src/solver.c:545` | CCD 跳过快速 mesh | §4.9 | 无（voxel 只在 static 上） |
| `src/world_snapshot.c:608-625,760-797` | 快照写 / 读几何 | §7 | 拒绝录制，或实现 |

另外不靠这几个名字、但按类型分派的地方：`src/shape.c` 的 `31 / 112 / 606 / 661 / 680 / 711 / 731 / 756 / 776 / 836 / 859 / 903 / 940 / 980 / 1046 / 1081 / 1950`，`src/contact.c:498-537`（凸形 manifold），`src/mesh_contact.c:740,850,1300`（按 B 的类型），`src/solver.c:463`，`src/sensor.c:50`，`src/physics_world.c:1325`，`src/world_snapshot.c:591-635`，`src/body.c:579,869`（只收凸形）——都已在上文各节覆盖。

---

## 10. `mesh_contact.c` 端到端结构（voxel 接触照它写）

`src/mesh_contact.c`（上游行号；打补丁后 916 行以后 +38）：

1. **收集候选**：`b3CollectTriangleIndicesCallback`（`40-51`）、`b3QueryMeshTriangles`（`53-63`）、`b3QueryHeightFieldTriangles`（`65-75`）；`b3RefreshCache`（`77-169`）：查询盒缓存判断（`84-96`）→ 外扩（`98-102`）→ 转局部（`108-111`）→ 按类型查（`112-121`）→ 512 上限（`123-131`）→ 按下标升序归并旧缓存（`136-168`）。
2. **准备**：`b3ComputeMeshManifolds`（`528-1318`）开头在 arena 里分配缓冲（`541-599`）；求 A→B 的相对变换（`557-559`）——**mesh 的 manifold 在 B 的局部坐标里算**（三角形先转进 B），这一点和凸形路径（在 A 里算）相反。
3. **SIMD 4 路三角形变换 + 对 hull 的 AABB 剔除**（`601-713`），被剔除的那条清 SAT 缓存（`680-685`）。
4. **逐三角形窄相**（`715-844`）：`b3CollideTriangleAndCapsule` / `b3CollideTriangleAndHull`（快速时清掉缓存的边轴，`749-752`）/ `b3CollideTriangleAndSphere`；记下 `triangleIndex`、三角形法线、`i1..i3`；分类：三角形面特征直接接受并登记它的边和顶点（`783-793`）；hull 面且法线夹角 cos > 0.5 接受（`794-807`）；深穿透（< −2 slop）接受（`816-826`）；其余进「暂定」（`827-842`）。
5. **鬼碰撞过滤**（`850-965`）：球：暂定的按距离排序（`QSORT`），只有该特征的边 / 顶点还没被登记过才接受（`b3FoundEdges` / `b3FoundVertices`，`177-273`，**以顶点下标为键**）——MineBox3D 补丁 0001 在这里加了「平边上、法线又不是三角形自己的，就丢掉」；胶囊 / hull：三条边全平就丢，平边已被登记过就丢（`924-965`）。
6. **一个都没有** ⇒ 释放 manifold，返回 false（`969-978`）。
7. **聚类**（`980-1112`）：贪心地按 manifold 法线和三角形法线（阈值 0.996）分簇，收集点，`b3ReduceCluster`（`476-517`）→ `b3CullPoints`（`294-472`）缩到 ≤ 4 点。
8. **写回接触**（`1114-1218`）：个数变了就重新分配 manifold；按法线匹配旧 manifold，继承摩擦 / 滚动 / 扭转冲量；按 (featureId, triangleIndex) 继承法向冲量；separation 减去 rest offset；锚点从 B 局部转到世界再加偏移。
9. **材质**（`1220-1295`）；**滚动阻力与切向速度**（`1297-1316`）。

不能直接调用的原因：`b3RefreshCache` 和 `b3ComputeMeshManifolds` 都断言 mesh / height，而且多处写成「不是 mesh 就当 height field」（`119-120`、`642-646`、`728-729`、`1236-1239`、`1258-1261`），voxel 进去会把自己的指针当高度场读 ⇒ 崩。辅助函数（`b3ReduceCluster`、`b3CullPoints`、`b3AddEdge` / `b3FindEdge` / `b3AddVertex`、`b3Cluster`）都是 `static` ⇒ voxel 的实现放进 `mesh_contact.c`（或者把这些辅助挪进内部头文件）最省事。

---

## 11. 可复用的窄相原语

都在 `include/box3d/collision.h:620-660` 公开声明，**都在 A 的局部坐标里工作，参数是 B 相对 A 的变换 `transformBtoA`**，输出 `b3LocalManifold`（点和法线在 A 局部，法线从 A 指向 B）：

| 函数 | 定义 | 签名要点 | 备注 |
|---|---|---|---|
| `b3CollideHullAndSphere` | `src/convex_manifold.c:303-399` | `(manifold, capacity, hullA, sphereB, xfBtoA, b3SimplexCache*)` | capacity ≥ 1；1 个点 |
| `b3CollideHullAndCapsule` | `src/convex_manifold.c:647-790` | `(manifold, capacity, hullA, capsuleB, xfBtoA, b3SimplexCache*)` | capacity ≥ 2 |
| `b3CollideHulls` | `src/convex_manifold.c:1598-1602` → `b3CollideHullsAtWidth`（`1271-1596`，内部，`src/manifold.h:51`） | `(manifold, capacity, hullA, hullB, xfBtoA, b3SATCache*)`；后者多一个 SIMD 宽度参数 | capacity < 4 直接返回空；带 SAT 缓存 |
| `b3CollideTriangleAndSphere` | `src/triangle_manifold.c:53-113` | `(manifold, capacity, const b3Vec3 tri[3], sphereB)` | **单面**（背面剔除）；设 `feature`、`squaredDistance` |
| `b3CollideTriangleAndCapsule` | `src/triangle_manifold.c:366-508` | `(manifold, capacity, tri[3], capsuleB, b3SimplexCache*)` | 单面 |
| `b3CollideTriangleAndHull` | `src/triangle_manifold.c:961-...` | `(manifold, capacity, v1, v2, v3, triangleFlags, hullB, b3SATCache*, enableSpeculative)` | capacity ≥ 4；读边标志 `b3MeshEdgeFlags`（`include/box3d/types.h:2171-2189`）；有背面缓存 |
| `b3MakeOffsetBoxHull` | `src/hull.c:2974-2978` | `(hx, hy, hz, offset)` → 按值返回 `b3BoxHull`（`include/box3d/types.h:2099-2117`） | 内部调 `b3MakeTransformedBoxHull`（`2870-2967`），每次都算惯量和**整个 hull 的哈希** ⇒ 不要每步每格建，按碰撞体表预建 |

注意：

- 凸形函数**不设** mesh 管线需要的 `b3LocalManifold` 字段（`feature`、`triangleNormal`、`triangleIndex`、`i1..i3`、`triangleFlags`、`squaredDistance`），而 mesh 的分类在 `src/mesh_contact.c:773` 断言 `feature != b3_featureNone`。用盒做窄相，就得自己写分类和鬼碰撞过滤，不能原样塞进 §10 的第 5–7 步。
- `b3BoxHull` 里的 hull 数据用相对 `base` 的偏移寻址，按值拷贝仍然有效 ⇒ 每个碰撞体盒在 0–1 格子坐标里预建一次，格子偏移放进 `transformBtoA`（`xfBtoA = inv(xfA ∘ cellOffset) ∘ xfB`）。
- 两条窄相路线：
  - **盒路线**：每个盒当 hull A。准确、盒的 SAT 便宜；但相邻实心盒的内部接缝会产生鬼碰撞，要用覆盖位自己过滤（例如丢掉法线指向被覆盖面的点 / manifold）。
  - **面路线**：只取露出的面，拆成单面三角形，按覆盖位和邻面关系设平边 / 凹边标志，整条复用三角形函数 + §10 的鬼碰撞过滤与聚类。代价：`b3FoundEdges` 以顶点下标为键，格点要有全局一致的编号（例如 17³ 个格点）；亚格盒（台阶、半砖）的顶点编号得另想办法；三角形数量也多。
- 跨 section 的接缝：每个 section 是独立的接触，mesh 的鬼碰撞过滤看不到别的接触（这正是补丁 0001 要修的问题）。覆盖位里包含邻 section 的信息，就能在本地丢掉内部面，两条路线都受益。

---

## 12. 结论清单

### 必须实现（不做会崩、会断言或完全不碰撞）

1. 枚举值 `b3_voxelShape`（追加在 sphere 后，= 6）+ `b3Shape` union 成员（`types.h:452-474`，`shape.h:57-65`）。
2. `b3CreateShapeInternal` 几何分支、`b3CreateVoxelShape`、`b3Shape_GetVoxel`（`shape.c:112-164`，`1530-1576`）。
3. static-only + 非 sensor：`shape.c:274`、`body.c:1620`（顺手修 `body.c:1623` 的锁泄漏）、创建时拒绝 sensor。
4. `b3ComputeShapeMargin` 归组、`b3ComputeShapeAABB` 分支（`shape.c:60-68`、`604-633`）。
5. 接触：`b3AddType` × 3（`contact.c:121-142`）、`b3_simMeshContact`（`contact.c:215-226`）、`b3UpdateContact` 分支（`contact.c:840-857` 旁边）、新的 `b3ComputeVoxelManifolds`（照 `mesh_contact.c` 写）。
6. 改格子 API + 接触失效（§4.6：清回收标志、清查询盒和缓存、叫醒另一侧 body）。
7. CCD：`b3ShapeTimeOfImpact` 分支（`shape.c:2274-2396`）。
8. 查询：`b3RayCastShape`、`b3ShapeCastShape`、`b3OverlapShape`、`b3CollideMover` 各一个分支 + 底层的 `b3RayCastVoxel` / `b3ShapeCastVoxel` / `b3OverlapVoxel` / `b3CollideMoverAndVoxel`。
9. 材质：选定 §9.6 的 (a) 或 (b)；选 (b) 就改约 8 处，包括 `b3GetShapeUserMaterialId`。
10. `b3Shape_GetClosestPoint` 加类型过滤（`shape.c:1861-1885`；今天对 mesh / height / compound 也会崩）。
11. `b3ValidateContacts` 分支（`physics_world.c:4151-4196`），否则 Box3D 自己的 VALIDATE 测试构建会断言。

### 复用 mesh 的路（设了 `b3_simMeshContact` 就自动得到）

- `b3MeshContact` 的缓存与查询盒、按键升序归并（算法照 `b3RefreshCache` 写）；多 manifold 分配；约束图 / 标量与 wide-mesh 求解；`b3ContactSpec` 每步更新；begin / end 事件；hit 标志；接触销毁和世界销毁时释放缓存；快照里三角形缓存的写读。
- 聚类与点数缩减、warm start 的匹配规则（`mesh_contact.c:980-1218`）；面路线时还包括三角形窄相与鬼碰撞过滤。
- CCD 的 `b3MeshTimeOfImpactFcn`（面路线）。
- 区域枚举照 `b3QueryHeightField`，格子 DDA 照 `b3ShapeCastHeightField`。

### 可以跳过，或明确断言不支持

- sensor（作为 sensor 禁止；作为访客自动跳过）、动态 / 运动学 voxel、`b3Body_SetType` 改出 static、compound 子形状。
- 质量 / extent / 面积 / 扫掠 AABB / 风力 / 爆炸 / `b3Body_GetClosestPoint` / `b3Body_CollideMover` / `b3Body_TimeOfImpactMover`（都只处理凸形或动态体）。
- pre-solve（mesh 也没有）。
- 录制回放：在 `b3World_StartRecording` 里拒绝。
- 调试绘制（桥不用；要用就加 `b3DebugShape` 成员和一个分支）。
- broadphase：不用改（包围盒固定，一对形状一个 pair）。
