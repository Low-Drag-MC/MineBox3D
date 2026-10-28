# MineBox3D · Box3D fork（`Low-Drag-MC/box3d`，分支 `minebox3d`）

状态：**现行规矩 + 改动台账**。每次在 fork 里提交都要在 §3 记一行；每次同步上游都按 §2 走一遍。
来由：2026-10-28 用户：「我打算放 LowDragMC 下，我自己推送就好」「你可以顺手修掉，记录一下，也许之后 box3d 会修复，我们会时不时同步 latest 的代码，并且我们自己也会扩展新功能。你要写 doc」。

## 1. 布局

- 子模块 `native/box3d`，`.gitmodules` 指向 `https://github.com/Low-Drag-MC/box3d.git`、分支 `minebox3d`。
- 子模块里两个远端：`origin` = 我们的 fork（**用户自己推送**），`upstream` = `erincatto/box3d`。
- 构建直接编子模块（以前的 `native/patches` + 复制打补丁那一步已删）。
- **改法**：大的新功能放新文件（例如体素形状的 `voxel*.c`），老文件只在分派点加最少的分支，并在改动处写 `// MineBox3D:` 注释说明为什么——合上游时冲突少、一眼看得出哪些是我们的。
- 提交一个改动一个提交，信息格式同 MineBox3D（`fix: fixed …` / `feat: added …` 一行）。上游 bug 的修复和我们的新功能**分开提交**，方便上游修了以后单独丢掉。

## 2. 同步上游

1. `git -C native/box3d fetch upstream`，看 `upstream/main` 新增了什么（尤其 §3 里「上游 bug」那几处的文件）。
2. 在 `minebox3d` 上 `merge upstream/main`（不 rebase：fork 已推送，别人可能基于它）。
3. 对 §3 每一行：上游已修 ⇒ 合并时取上游的、把这一行标「上游已修（上游提交）」；没修 ⇒ 保留。我们的新功能：检查分派点有没有被上游挪动（调研 `research/box3d-new-shape-touchpoints.md` 的 grep 清单就是体素的全部分派点，照着再 grep 一遍新加的形状类型 / switch）。
4. 重新编、跑 JUnit + GameTest 全量。
5. 在 MineBox3D 里提交新的子模块指针，信息里写同步到的上游提交。

## 3. 改动台账

### 3.1 上游 bug 的修复（上游修了就丢掉我们的）

| fork 提交 | 文件 | 问题 | 怎么发现的 | 上游状态 |
|---|---|---|---|---|
| `4ea0530` | `src/mesh_contact.c`（球的暂定接触过滤） | 球对 mesh 按顶点编号找「鬼边」，只看得见一个 mesh；平边的另一侧在别的 mesh 里时，球在那条边上被顶起来（段边界上 2 m/s 被顶到 0.45 m/s、掉 15% 速度）。修法：边 / 顶点特征落在 `identifyEdges` 标过的平边上、法线又不是三角形自己的 ⇒ 丢掉（凸包路径早就这样处理平边）。原来是 `native/patches/0001` | 方案 A 的接缝测量（`MeshSeamTest`） | 未报 |
| `281b0e5` | `src/body.c` `b3Body_SetType` | body 上有 compound / 高度场时拒绝改类型，但 `return` 前没把 `world->locked` 放回 `false` ⇒ 之后这个世界的所有调用都断言并返回空 | 读代码（体素调研） | 未报 |
| `54ff131` | `src/solver.c` `b3ContinuousQueryCallback` | 形状开了 pre-solve 事件、世界没设回调 ⇒ CCD 命中时调空函数指针（接触路径 `contact.c:700` 有判空，这里没有） | 读代码（体素调研） | 未报 |
| `9dc1eda` | `src/shape.c` `b3Shape_GetClosestPoint` | 对 mesh / 高度场 / compound 直接拿凸形代理 ⇒ 断言后解引用空指针。修法：非凸形断言并返回目标点本身（和 `b3Body_GetClosestPoint` 一样只认凸形） | 读代码（体素调研） | 未报 |

用户 10-28：先自己修、记下，不一定报上游。

⚠️ `4ea0530` 的判断后来在 `2d87e17` 里挪进了 `b3IsFlatFeatureGhost`（体素的凸包 / 胶囊路径也用它）：上游修了球的鬼边，丢掉的是球那一处调用，函数留给体素。

### 3.2 我们的新功能

| fork 提交 | 内容 | 设计 |
|---|---|---|
| `2d87e17` | 体素形状 `b3_voxelShape` + `b3VoxelGrid`（V1 + V2：形状、接触、推出、改格子、射线 / 重叠 / 扫掠 / mover） | `01-voxel-terrain.md` |
| `29bbc36` | `b3VoxelGrid_CollectTriangles`：一段给接触看的露出三角形和边标记（调试绘制、测试） | 同上 §12.4 |
| `b9e9f98` | 体素鬼碰撞两处：露出矩形在平不平变化处切开；顶点接触要越过一条真实的边（`mesh_contact.c` 的 `b3IsVoxelGhost` + `voxelGhosts`） | 同上 §12.4 |
| `eaf509b` | review 轮三处：`b3VoxelGrid_GetFeatureCell(grid, shape, …)` 换掉 `b3VoxelShape_GetFeatureCell` / `GetGrid`（只认这个网格的形状，别的世界的 id 不去读）；切开的小块不在查询范围里就不交出去（不占 512 的候选名额，编号照旧数）；推出时找「在哪个盒子里」改成半开区间（推到格子边界上正好停住的刚体不再卡死） | 同上 §12.6 |
| `1caa529` | 体素的边 / 顶点接触，法线和面法线差在 0.999 以内 ⇒ 直接用面法线（`mesh_contact.c`，算完 `voxelGhosts` 之后）。段界上另一段的面接触在另一个接触里，Box3D 的「已找到的边」挡不住，斜一度的法线把箱子推歪 | 同上 §12.6 |

`2d87e17` 碰到的老文件和位置（合上游时逐个核对；新文件 `include/box3d/voxel.h`、`src/voxel.h`、`src/voxel.c` 不会冲突）：

| 文件 | 改了什么 |
|---|---|
| `include/box3d/types.h` | `b3ShapeType` 末尾加 `b3_voxelShape` |
| `src/shape.h` | 几何 union 加 `voxel` 指针 |
| `src/contact.h` | `b3MeshContact` 加 `voxelTriangles`；`b3DeclareArray( b3Triangle )` |
| `src/contact.c` | 接触注册 3 行；`b3_simMeshContact`；`b3UpdateContact` 走 mesh 分支；销毁时释放 `voxelTriangles` |
| `src/mesh_contact.c` | `b3RefreshCache` 取体素三角形；`b3ComputeMeshManifolds` 两处取三角形、平边鬼碰撞规则（`b3IsFlatFeatureGhost`）、体素顶点鬼碰撞（`b3IsVoxelGhost`，`b9e9f98`）、近似面法线的边接触改成面法线（`1caa529`）、推出接触、材质 |
| `src/shape.c` | margin / 创建 / 只许静态与非 sensor / 包围盒 / 质心 / `b3CreateVoxelShape` / 射线 / 扫掠 / 重叠 / mover / CCD / `b3GetShapeUserMaterialId` |
| `src/body.c` | `b3Body_SetType` 拒绝体素；两个刚体射线 / 扫掠的材质 id |
| `src/physics_world.c` | 世界销毁释放 `voxelTriangles`；调试绘制跳过体素；开始录制拒绝体素；`b3ValidateContacts` 分支；世界射线 / 扫掠的材质 id |
| `src/CMakeLists.txt` | 加三个新文件 |
