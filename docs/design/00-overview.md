# MineBox3D · 总体设计（2026-10-26）

状态：**第一版设计，M1 已做完（`ee657d8`），MC 接入做完（地形换成了方案 B，见 `01-voxel-terrain.md`）；2026-10-28 整体 review + 补测试一轮做完（01 §12.6）**。§2 的线程规矩是现行的；§6 列的 Java 面只做了一部分（事件、结果对象做了，关节、Hull / HeightField 还没有）。多线程和 API 见 `03-threading-and-api.md`：第 1 批（地基）做完，接着是关节那一批。
来由：EntityStudio doc 56 §12.5——用户裁决「走 Box3D + 自己的 JNI，开一个全新的 project，专门为 MC 服务，要能支持世界地形和实体体积动态进出我们的 Box3D 引擎；功能增强等稳定后再做」，2026-10-26「你先把 mc 做好了」。

## 1. 分层

```
com.lowdragmc.minebox3d
├─ internal   B3：一个 native 一个桥函数，id 是打包的整数（不是 API，什么都不查）
├─ natives    NativeLibrary：找库、拷出来、加载、B3.init
├─ physics    纯 Java 面：PhysicsWorld / Body / Shape / 几何 / 定义对象 / 查询 / 事件。不认识 MC
└─ mc         MC 接入：每个 Level 一个 LevelPhysics；地形进出（TerrainStreamer）；实体体积进出
              （EntityProxies）；步进与插值；调试绘制；命令
native/
├─ box3d      子模块，钉提交
└─ src        C 桥（mb3_*.c）
```

`physics` 不依赖 `mc`，可以在 JUnit 里直接跑；`mc` 只通过 `physics` 用 Box3D。

## 2. 线程规矩（Box3D 的规矩 + 桥的保证）

- **一个 world 同一时刻只能有一个线程在用**（Box3D 本身的要求：step 期间不能读写，step 之外的只读查询才可以并发）。
- **不同 world 可以在不同线程上同时用**。建 / 删 world 在 C 里有一把锁（Box3D 扫全局槽不加锁）。
- 桥本身没有共享缓冲、不缓存 JNIEnv ⇒ 上面两条之外没有别的限制。
- `PhysicsWorld` 两种线程模式（`03-threading-and-api.md` §4.2、§9.1）：**绑定线程**（默认）= 建它的线程是拥有者，别的线程任何时候调都抛 `WrongThreadException`，要换线程用 `transferTo(thread)`；**独占使用** = 任何线程，`try (var use = world.use())` 占住期间才能用，别人占着就立刻抛（给在线程池上 step 的私有世界）。两种都是出错就确定地抛，不会偶尔在服务端 tick 里炸。
- 传进 native 之前 Java 先查参数（非有限值、半径 ≤ 0、非单位四元数…，`IllegalArgumentException`）：Box3D 断言之后照样往下跑，坏值进了世界就会一直扩散。仍然漏过去的断言 ⇒ `Box3DException`；`step` 里出的 ⇒ 世界进入 `isFaulted()`，之后不再 step。断言了却已经建出来的刚体 / 形状由桥当场删掉，不留没有句柄的孤儿。faulted 的世界里删刚体 / 关节 / 形状 / 碰撞对表只在 Java 这边放手、不进 Box3D，`close()` 连世界一起释放（0.3.1，2026-11-01）：被解算器甩成 NaN 的刚体把 NaN 包围盒留在 broadphase 里，删东西（尤其碰撞对表删除时刷新过滤 = 重插代理）会走这些节点，ES 物理资产编辑器里实测整个 JVM 崩在 `pairTableDestroy`。review 补的两处：放手的形状用的网格 / 高度场数据留到世界关掉再释放（Box3D 的形状还在、查询还会读，原来会 use-after-free）；关 faulted 关卡时地形不解除停放、不单独删网格（世界一起释放）；垃圾回收清理器先删世界再删网格，和 `close()` 同序。还能进 Box3D 的：查询、改形状 / 变换 / 过滤（含碰撞对表 `apply()`）——faulted 了就尽快关掉。
- `workerCount > 1` 时 Box3D 给这个世界单开一套自旋等待的工作线程，工作线程上的断言只进日志、不抛异常（`WorldDef.workerCount` 的文档里写了）。共享线程池在多线程设计稿里讨论。
- `LevelPhysics` 的 world 归该 Level 的线程（服务端 = 服务端主线程，客户端 = 客户端主线程，`LevelThreads` 从 NeoForge 的 `LogicalSidedProvider.WORKQUEUE` 判断）；别的线程调 `LevelPhysics.of` 直接抛。别的线程上的方块变化排进队列，下一次地形更新时在 Level 线程上取。

## 3. 每个 Level 一个物理空间（`LevelPhysics`）

- 两端各自一个，`LevelPhysics.of(level)` 惰性建，Level 卸载时销毁（`LevelEvent.Unload`；服务器停止 / 客户端断开时全清）。**本地库没加载成功时整个 MC 层不工作，`of` 返回空**，调用方自己降级。
- 内容：一个 `PhysicsWorld` + 它的地形 + 实体代理 + 用户建的刚体。
- **两端互不相干**：服务端的刚体不会自动出现在客户端（不做网络同步，见 §8）。谁要同步谁自己发（EntityStudio 的击倒 = 服务端算、把骨盆位置随 mover 复制下去）。
- 别的用途可以建自己的私有 `PhysicsWorld`（比如只给一个 ragdoll 用），同样可以挂地形和实体代理（§4、§5 的接口不绑死在 `LevelPhysics` 上）。

### 3.1 步进

- 在该 Level 的 tick 末尾（服务端 `LevelTickEvent.Post`，客户端 `ClientTickEvent.Post`）：先更新实体代理和地形，再 step。`/tick freeze` 时不走（`/tick step` 照走）；一次 tick 里抛了异常 ⇒ 这个 Level 的物理停掉、记一条错误日志，不让 Level 的 tick 每次都崩。
- 默认每 tick 3 步、每步 1/60 秒、每步 4 子步（Box3D 推荐 60 Hz + 4 子步；MC 一 tick 1/20 秒）。可配置。
- **渲染插值**：每次 step 之后批量读一次所有醒着的刚体的位姿（Box3D 的 move event），留上一 tick 和这一 tick 两份 ⇒ `Body.renderTransform(partialTick)`。

### 3.2 单位与坐标

1 格 = 1 米、秒、千克；Box3D 开了 double 世界坐标，刚体直接放 MC 的世界坐标，不需要浮动原点（宽相仍是 float，Box3D 说 1e7 以内只是多一些假候选对，MC 世界边界 3e7）。重力默认 `(0, -32, 0)`（= 原版实体的 0.08 格/tick²），可改。

## 4. 地形进出（`TerrainStreamer`）

### 4.1 什么方块进 Box3D

- **按需**：只有「需要地形的刚体」周围的方块才进。需要地形 = 动态刚体（醒着或睡着）+ 显式请求的区域（`requestTerrain(AABB, ticks)`，给查询之类用）。
- 粒度 = **区块段（16×16×16）**：刚体包围盒按速度向前扩一点、再加边距，碰到哪些段就要哪些段；没人要的段延迟若干 tick 再卸（滞后，避免在边界上来回建拆）。
- 每 tick 有预算（最多建 N 个段），刚体正下方的段优先。
- **刚体下面的区块没加载 ⇒ 刚体停放**（`setEnabled(false)`，和原版「区块不 tick 的实体不动」一样），区块回来再放回模拟；区块卸载时先停放、再删段，不会掉进虚空。只用 `getChunkNow`，不在服务端等区块加载。
- 一个 Level 的所有 streamer（Level 自己的和私有世界的）都收得到方块变化和区块进出。

> ⚠️ §4.2、§4.3、§4.4 是方案 A，已被 `01-voxel-terrain.md`（方案 B，体素形状，§8 / §12.4）取代；留着是为了 §4.3.1 的测量记录。§4.1 的按需流送还是现行做法。

### 4.2 一个段怎么变成碰撞体

- 段里每个方块取 `getCollisionShape(level, pos, CollisionContext.empty())` 的盒子；空的跳过；流体不算（浮力以后再说）。
- 盒子拆成面；**和相邻盒子完全贴合的相对面删掉**（同一方块里的、隔壁方块里的、隔壁段里的都算）⇒ 一片整方块的地面只剩顶面那一层。
- 同一平面上相邻的整格面**合并成大矩形**（贪心合并），三角形数量从「每格 2 个」降到「每块平地 2 个」。
- 一个段 = 一个 static body（放在段的世界坐标原点）+ 一个 mesh shape，顶点用段内的局部 float 坐标；`identifyEdges` 打开（Box3D 防内棱卡住的办法）。
- 摩擦 / 弹性：方块的 `getFriction()` 换算进三角形的材质（冰面滑），其余用默认。

### 4.3 ⚠️ 段与段之间的接缝

Box3D 只在**同一个 mesh 内部**识别内棱；两个段的网格在 16 格的边界上是两个 mesh ⇒ 滑过边界时可能「磕一下」。**先量再定**（GameTest：箱子 / 胶囊在平地上以几种速度滑过段边界，量竖直速度和角速度的扰动，对照同一片地形建成一个整 mesh 的结果）：
- 扰动可以忽略 ⇒ 就按段。
- 不能忽略 ⇒ 改成「按刚体簇合并」：相连的一片段合成一个 mesh，变化时只重建那一片（代价：重建变大）。
这一条量完写回本文。

#### 4.3.1 量出来的（2026-10-26，「方案 A = 每段一个三角网格」）

⚠️ 这一版是我没和用户讨论就先写了的（用户：「你还没和我讨论你打算怎么支持世界地形呢」），之后用户让「先量量 A」。

- **整格接缝**：段边界上 Box3D 只认同一 mesh 里恰好两个三角形共用的边 ⇒ 原样切段会磕（JUnit `MeshSeamTest`：箱子 2 m/s 被转到 5 rad/s、横胶囊 6 m/s 被顶起 0.65 m/s）。修法 = 每段带邻段一圈建「上下文」mesh 算边标记、抄给本段（`createMeshWithContext`）+ Box3D 补丁 `native/patches/0001`（球的接缝走的是按顶点编号去重，不读边标记）⇒ 整格石头地面过段边界，真实 MC 方块上四种形状三种速度都 ≈ 0（GameTest `SeamProbeGameTest`）。
- **半格方块：不过**。同一个探针：石头|上半砖（顶面同高）胶囊 6 m/s 顶起 1.09 m/s；石头|楼梯踏面箱子 4.7 m/s、横胶囊被掀下去；一片下半砖箱子 2 m/s 转到 6.8 rad/s；球 2 m/s 处处 0.54。原因：面剔除只在「邻居是整格」时删相邻面 ⇒ 半格旁边的面整片留着 ⇒ 一条边上叠了 3–4 个三角形（非流形），`identifyEdges` 只给恰好两个三角形的边打标记。要修得做「盒子并集的精确表面 + 全段一致的切分（无 T 形接缝）」。
- **重建耗时：不过**（`/minebox3d_bench terrain 6`，真实生成的世界，出生点 13×13 区块、1395 个有几何的段）：Java 建面 均值 0.60 / p95 0.93 / 最大 5.1 ms；Box3D 建 mesh + 上下文标记 均值 3.2 / p50 1.8 / p95 11.2 / **最大 41.7 ms**（不带上下文只要 0.48 / 1.32 / 2.96）；每段三角形 均值 803、p95 2084、最大 4828。⇒ 一次爆炸改 8 个段、或一个刚体进新区域，就是几十 ms 的主线程卡顿。
- ⇒ A 两项都不过，等用户在 B（fork Box3D 加体素形状）/ D（换 Rapier 走自定义形状）之间定。
- **✅ 用户裁决（2026-10-27）：B**——「我觉得做B吧，我更看好B的潜力」。地形 = Box3D 里新增一种体素形状（方块碰撞盒按格子存、按格子算接触、按面是否外露过滤假接触），不再转网格。界线：只依据 MC 原版碰撞和公开资料设计，不看、不沿用 Sable 的代码（Polyform Shield 竞业条款）。

### 4.4 方块变化

- 钩 `LevelChunk.setBlockState`（两端都走这里，包括活塞、爆炸、命令）：变化的格子在已加载的段里 ⇒ 那个段（和贴边时的隔壁段）标脏，下一 tick 重建。
- 区块卸载 ⇒ 段跟着移除。
- 兜底：已加载的段每隔一阵抽查（一次少量），防漏掉的批量改动。

## 5. 实体体积进出（`EntityProxies`）

- 「需要地形的刚体」附近的实体以 **kinematic body** 进 Box3D，每 tick 用 `SetTargetTransform` 推到实体的新位置（按速度推，而不是瞬移 ⇒ 撞上去的刚体会被正确地推开）；离开范围就移除。
- 形状由**提供者注册表**决定：默认 = 实体的碰撞箱（盒子）；别的 mod 可以为自己的实体注册形状（EntityStudio 的受击体积、一个用胶囊的玩家…）。
- **只单向**：实体推得动刚体，刚体推不动实体（第一版）。双向（被箱子砸了会退）是增强项。
- 碰撞分类：`TERRAIN` / `ENTITY_PROXY` / `DYNAMIC` / … 用 Box3D 的 category bit；分类表是注册表，别的 mod 可以加。

## 6. `physics` 包的 Java 面

- `PhysicsWorld implements AutoCloseable`：`create(WorldDef)`、`step`、`createBody(BodyDef)`、重力 / 设置、查询（射线最近 / 全部、形状扫掠、重叠）、事件（移动、接触、传感器、关节）、`close`。
- `Body` / `Shape` / `Joint`：Box3D id 的薄句柄，持有所在 world；id 到对象的映射表在 world 里（事件和查询回来的 id 直接换成对象）。
- 几何：`Sphere`、`Capsule`、`Box`、`Hull`（点集，Box3D 自己会复制）、`Mesh`（native 数据，`AutoCloseable`，引用计数：还有 shape 在用就不真正释放）、`HeightField`。
- 定义对象：公有字段 + 链式 setter，默认值来自 Box3D（`B3.defaultXxxDef`）。
- 第一版先补 MC 接入用得到的；其余（全部关节、录制回放…）随后补齐。

## 7. 调试

- 客户端：`/minebox3d debug` 开关，画客户端物理空间里的刚体、地形段、实体代理（线框），颜色区分睡着 / 醒着 / 运动学。
- 试玩：`/minebox3d spawn box|sphere|chain` 在**客户端**物理空间里生成刚体（纯本地，用来看地形和实体交互对不对）。
- 统计：`/minebox3d stats`（world 数、刚体、段、三角形、step 耗时）。

## 8. 第一版不做（稳定后的增强）

- 刚体的网络同步（服务端刚体自动出现在客户端）。
- 刚体推实体（双向）、流体浮力。
- Box3D 关节语义的增强（椭圆锥等）。
- MC 多版本、发布流程（GitHub Actions 五平台在接入稳定后做）。

## 9. 测试

现状（2026-10-28，JUnit 82 + GameTest 25，全绿；GameTest 在随机位置连跑多次）。下面是体素那一轮的列表，`03-threading-and-api.md` §9.1 / §9.2 又加了 `ThreadModeTest`、`WorldEventsTest` 等：

- JUnit（`physics` 层，不依赖 MC）：`B3SmokeTest`（每个桥函数至少调一次、看得见效果）、`PhysicsWorldTest`（插值、拥有者线程、交出线程、遍历中建删、多世界多线程、mesh 引用计数）、`PhysicsApiTest`（参数校验、断言后的孤儿、关了的世界、三种关闭顺序不漏内存、8 线程 × 100 世界、200 个刚体的事件、睡着事件跨步累计）、`VoxelTerrainTest` + `VoxelDetailTest`（体素地形，见 01 §12）、`MeshSeamTest`。
- GameTest：`TerrainGameTest`（落地、拆方块、半砖、立方体表面、栅栏、实体推刚体）、`TerrainSeamGameTest`（7 种真实地面 × 4 形状 × 3 速度）、`TerrainStreamingGameTest`（拆栅栏 / 开栅栏门、高速和高空、段的来去、区块卸载停放、冰 / 史莱姆 / 传送带材质）、`EntityProxyGameTest`（代理随实体变大小 / 消失、传送不扫过、托住刚体、排除和提供者）、`BlockCatalogueGameTest`（89 种方块状态 × 550 条射线对 `VoxelShape.clip`）、`NativeLoadGameTest`。
- 测不到、要手动看：Level 卸载 / 服务器停止 / 客户端断开的释放、客户端步进和调试绘制、`/minebox3d` 命令、真区块卸载事件（GameTest 里直接调 `onChunkUnloaded` 只测得到 streamer 的反应）、`workerCount > 1` 工作线程上的断言。
