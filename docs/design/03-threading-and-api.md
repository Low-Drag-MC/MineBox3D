# MineBox3D · 多线程与 API（2026-10-28）

状态：**§8 五条用户都按建议定了（2026-10-28「好」）；第 1 批（地基）做完 + review 过（§9.1、§9.2）；第 2 批（关节、刚体、形状、世界，§9.3）做完 + review 过（§9.4，低级项和一个问题等用户）**。2026-10-29 全面 review 对 API 的改动记在 `04-entities-and-bodies.md` §12：`Body.setParked` / `isParked`（和 `setEnabled` 分开记）、`Geometry.asBuilt` / `isRounded` / `isConvex`、`Shape.castShape` 拒绝圆形查询对网格 / 高度场 `canEncroach`、`ShapeDef.materials` 给了就不用 `baseMaterial`、`Checks.worldDef`、高度场上限 2²⁴ 点（§9.4 里「int 乘法溢出」那条修了）。
来由：2026-10-28 用户：「考虑了性能和api的需求吗？比如多线程啊，api设计等等」。依据：本轮 review 的 API / 线程评估（01 §12.6）、Box3D 源码（`native/box3d`，`1caa529`）、EntityStudio doc 56（物理资产 + ragdoll，是 MineBox3D 的第一个大用户）。

---

## 1. 一句话

线程模型分两种（Level 物理绑定线程；私有世界谁用谁占），大世界接一个进程共享的 C 线程池让 Box3D 自己多核，step 挪出主线程等量出需要再做；API 先把地基打好（分层、零分配的结果对象、批量事件、一次读回刚体状态），再按 ragdoll 的需要补齐关节和刚体 / 形状 / 世界的接口。

## 2. 谁会用、怎么用

| 用户 | 世界 | 在哪个线程 step | 规模 | 来源 |
|---|---|---|---|---|
| Level 物理 | 每个 Level 每端一个 | Level 线程（服务端主线程 / 客户端主线程），每 tick 3 步 | 几十到几千个刚体 | 现在就有 |
| EntityStudio Rigid Body 节点（= UE 的 RBAN） | 每个节点一个小世界 | 那个实体的**姿势 worker**——线程池里的线程，**每帧不一定是同一个** | 约 20 个体 | doc 56 §3.4、§12.5 |
| EntityStudio 世界 ragdoll | Level 物理（大家互相碰） | 所有姿势 worker 做完 → 整个场景 step → 混回姿势 | 同时几个到十几个 ragdoll | doc 56 §3.4、§7 |
| 编辑器预览 | 私有世界 | 客户端主线程 | 一个模型 | doc 56 §9 |
| 玩法查询（受击判定、射线、扫掠） | Level 物理 | Level 线程 | 每 tick 几十次 | doc 56 §8 |

两件由此而来的硬要求：
1. **私有世界要能在「每帧换一个」的线程上 step**。现在的规矩（世界绑定建它的线程，`transferTo` 只有拥有者能调）做不到：上一帧的 worker 不会来交接。
2. **私有世界也要地形**。地形只能在 Level 线程上读区块（`getChunkNow`）；Box3D 本身不读 MC。所以同一个私有世界会被两个线程先后用：Level 线程更新地形，姿势 worker step。

## 3. 量出来的（Box3D 自带调度器，本机 24 个逻辑核）

刚体放在 80 × 80 的体素地面上乱滑、不许睡，60 Hz × 4 子步，一步的时间：

| 刚体数 | 1 线程 | 2 | 4 | 8 | 4 线程时忙的核 |
|---|---|---|---|---|---|
| 20（一个 ragdoll） | 0.024 ms | 0.025 | 0.031 | 0.043 | — |
| 256 | 0.227 | 0.143 | **0.103** | 0.136 | 4.0 |
| 1024 | 0.823 | 0.481 | **0.291** | 0.295 | 3.6 |
| 4096 | 3.36 | 1.71 | 0.96 | **0.83** | 3.6（8 线程 8.0） |

- 20 个体的小世界开多线程**更慢**；256 个起有用，4 线程约快 2–3.5 倍；8 线程只在几千个刚体时比 4 线程多一点，还把 8 个核吃满。
- 工作线程在 step 期间自旋等活（Box3D 的求解器就是这么同步的），**两次 step 之间睡着**：按 MC 的节奏跑（每 50 ms 三步，其余时间空闲），4 线程平均只占 0.15 个核。
- 换算成 MC：4096 个刚体单线程每 tick 约 10 ms（服务端 tick 预算 50 ms 的 1/5），4 线程约 3 ms。

## 4. 线程模型

### 4.1 现在

`PhysicsWorld` 绑定建它的线程，别的线程调就抛 `WrongThreadException`；`transferTo(thread)` 由拥有者交出去（本轮 review 改的，原来是「两个线程撞上才抛」，会让服务端 tick 偶发地崩在别人的错上）。

### 4.2 方案：建世界时选一种（推荐）

| 模式 | 谁能用 | 查什么 | 给谁 |
|---|---|---|---|
| **绑定线程**（默认） | 只有拥有者线程；`transferTo` 交出 | 每次调用：是不是拥有者 | Level 物理、编辑器预览 |
| **独占使用** | 任何线程，但要先 `try (var use = world.use()) { … }` 占住；同一时刻第二个线程来占 ⇒ 立刻抛；没占就调 ⇒ 立刻抛 | 进出时一次 CAS，调用时看「占着的是不是我」 | Rigid Body 节点这种在线程池上 step 的私有世界 |

- 两种都是「错了立刻、确定地抛」，不会静默踩坏内存，也不会偶发。
- 独占模式下，Level 线程更新地形和姿势 worker step 是两次 `use()`，先后由 EntityStudio 的帧调度保证（它本来就要保证「地形先到、再 step」）。重叠了 ⇒ 第二个抛，不会坏数据。
- 模式在 `WorldDef` 里定，建好不能改。

另外两条路和后果：
- **只留绑定线程**：Rigid Body 节点只能在一个固定线程上 step，所有角色的节点排队，失去姿势 worker 的并行。
- **不查**：最快，但用错就是 native 里踩内存、JVM 崩，查不出是谁。不推荐。

## 5. 多核

三件不同的事：

### 5.1 很多小世界同时跑

**已经可以**：Box3D 允许不同世界在不同线程上同时 step（建 / 删世界在 C 里有锁）。Rigid Body 节点的并行来自 EntityStudio 的姿势 worker，MineBox3D 只需要 §4.2 的独占模式，以及 §5.2 的池子不去和它抢核。小世界本身不开多线程（§3：20 个体开了反而慢）。

### 5.2 一个大世界用多核：接一个进程共享的 C 线程池（推荐做）

- Box3D 留了任务接口：世界有 `enqueueTask` / `finishTask` 两个回调，step 里把活拆成任务交给宿主（`types.h:16-41`）。
- **为什么不用 Box3D 自带的调度器**：它给**每个世界**开一套线程。客户端 + 集成服务端 + 每个维度 + 私有世界，线程数乘起来。
- **为什么不在 Java 里做池子**：每个任务都要 C → Java → C 往返（微秒级），一步几百个任务，和 step 本身同一个量级；step 中回调 Java 也违反桥的规矩（不缓存 `JNIEnv`、step 里不进 Java）。
- 做法：
  - C 里一个进程共享的池，默认 `min(4, 核数 − 2)` 个线程（§3：4 个以后收益很小）。
  - `finishTask` 等的任务如果还没开始，就在等的那个线程上直接跑。池子被别的世界占满也不会卡住。Box3D 的求解器本来就保证「线程不够也能做完」（`solver.c:1183-1190`）。
  - 每一步之前按醒着的刚体数自动选这个世界用几个 worker：少于约 128 个用 1 个，多了用池子的大小（阈值量出来定；`b3World_SetWorkerCount` 可以每步改）。
  - 工作线程上的断言：每个世界一个原子标记 + 消息，step 返回后在调用线程上变成异常、世界进入 faulted（现在只进日志）。
- 代价：大世界 step 期间池子的线程自旋（4 线程约占 3.6 个核），step 之间睡着。单人游戏里客户端和服务端的 Level 物理共用这一个池，不会翻倍。

### 5.3 step 挪出 Level 线程（异步 step，推荐先不做）

- 服务端：Level tick 末尾开始 step，交给池子里的一个线程，Level 线程继续往下走（别的维度、网络、空闲）；下一次有人碰这个世界时先等它做完（等待发生在访问时）。API 还是同步的语义，调用方不用改。
- 客户端：渲染每帧要读插值后的位置 ⇒ step 结束时把位置拷进 Java 的双缓冲，渲染读缓冲，不等 step。
- 收益要看玩法：同一 tick 里只要有一次查询（受击判定每 tick 都有），就会提前等，重叠就没了。
- 建议：先做 §5.2，在真实场景里量 Level 线程上物理占多少；真被物理挤满了再做。

## 6. API

### 6.1 分层

- 对外的只有 `physics`（不认识 MC）和 `mc` 的一小部分。
- `jni.B3` 挪到 `internal` 包、标 `@ApiStatus.Internal`：它绕过所有检查，过期的世界 id 被复用时会踩到别的线程的世界。
- `Box3DException` 挪到 `physics`（对外的 API 会抛它）。
- 新加的接口标 `@ApiStatus.Experimental`。发布前不做兼容（项目规矩），发布后按 semver。

### 6.2 结果怎么回来：调用方持有、可复用的结果对象（推荐）

- 现在五种形状：射线 / 扫掠回一个新建的 `CastHit`；重叠写 `long[]`；mover 写三个数组、7 个 float 一组；接触点 8 个 float 一组；包围盒写 `float[]`。用的人要记步长，热路径每次都分配。
- 改成：`RayResult`、`ShapeCastResult`、`OverlapResult`、`ContactPoints`、`MoverPlanes`。调用方建一次、反复传进去，里面是会自己长大的数组，按下标读（`result.normal(i, out)`、`result.shape(i)`）。不分配，也不用记步长。现在这种「回一个新对象」的写法留作便捷版。
- 查询形状用 `Geometry` + 位置 / 朝向，不再是裸的 `float[] points`。
- 打在地形上：同一次调用里带回格子坐标和方块状态（材质表里的 `userMaterialId` 存方块状态 id）。不用再拿 id 去 `featureCell` 反查，也就不会查到过期的段或别的世界。
- 单个向量 / 旋转照旧用 JOML 的输出参数（`position(out)`），这是 MC 的习惯。

### 6.3 事件：每步批量拷回，step 之后在用的线程上读（推荐）

- 每步结束一次 JNI 调用，把移动、接触开始 / 结束、命中、传感器开始 / 结束、关节（断裂）事件拷进世界自己的缓冲。一 tick 三步累计。step 之后在用这个世界的线程上读：`world.events().contactBegins()` 之类的迭代器，下一次 step 前有效。
- **step 里没有回调**：Box3D 的事件本来就是 step 之后取。拷的时候把 id 换成 Java 对象（结束事件里的形状可能已经删了）。
- 打在地形上的事件带方块状态（同 §6.2）⇒ 「撞到了什么方块」（声音、伤害）。
- 移动事件顺带拷回包围盒和速度：地形 streamer 和实体代理每 tick 不用再逐个刚体调 JNI（现在每个刚体每 tick 约 3 次）。

### 6.4 补齐 Box3D 的接口（按 ragdoll 的需要排）

1. **关节**：Box3D 全部 9 种。球铰（圆锥 + 扭转限位 + 弹簧 + 马达，ragdoll 主力）、铰链、焊接、马达关节（让刚体跟动画目标走）、距离、棱柱、轮子、过滤、平行。`Joint` 句柄，任一端刚体删了就失效；断裂 = 力 / 力矩超过阈值 ⇒ 关节事件。
2. **刚体**：
   - 质量 / 惯量：读、设、从形状重算。
   - 阻尼、力矩、角冲量、作用在某点的力。
   - 子弹模式、睡眠阈值、某点的速度。
   - 只对这一个刚体的射线 / 扫掠 / 重叠 / 最近点（打某个 ragdoll 用）。
3. **形状**：
   - 凸包（点集 → `b3CreateHull`）、高度场。
   - 材质、密度、传感器、过滤运行时改。
   - 球 / 胶囊就地改尺寸：实体代理变大小时不用删了重建。
4. **世界**：
   - 爆炸（`b3World_Explode`；`ShapeDef.explosionScale` 现在没有作用）。
   - 接触 / 关节参数、反弹阈值、命中阈值、最大速度。
   - 分阶段的耗时（现在只有总时间）。
5. **角色移动**：`CastMover` + `SolvePlanes` / `ClipVector`（后两个是纯数学，Java 里写也行）。
6. **不接**：每对接触都回调 Java 的接口（自定义过滤、pre-solve）。它们在工作线程上、每对接触调一次，回调 Java 太慢，也违反桥的规矩。用过滤位、组、过滤关节代替。

### 6.5 MC 层

- `TerrainStreamer.setConsumers` / `EntityProxies.setConsumers` 只有一个谓词，最后一个 mod 说了算 ⇒ 改成每个刚体的标记（要不要地形、要不要被实体推）。
- 公有的调参字段（每 tick 几步、边距、预算、保留多久…）⇒ 每个世界一个设置对象。
- 私有世界挂地形和实体代理的正式入口：今天是 GameTest 里的 `PrivatePhysics` 手搓，Rigid Body 节点要用。
- `userObject` 只有一个槽，实体代理已经占了 ⇒ 按类型的附件（`body.attach(MyData.class, data)`）。

### 6.6 生命周期

- 忘了 `close` 的世界 / mesh：用 `Cleaner` 兜底，记警告并释放。现在一个漏掉的世界永久占一个槽（共 1024 个）。

## 7. 分批（每批完整可用、带测试）

1. **地基**：§4.2 两种线程模式；§6.1 分层；§6.2 结果对象；§6.3 事件 + 刚体状态批量拷回；§6.5 MC 层的标记 / 设置对象 / 私有世界入口；§6.6。
2. **ragdoll 能做了**：§6.4 的关节、刚体、形状、世界、爆炸。做完 EntityStudio 的物理资产线就能接回来（doc 56）。
3. **大世界多核**：§5.2 共享线程池 + 自动选 worker 数 + 工作线程的断言变异常。
4. **异步 step**：§5.3，量过真实负载再定。
5. 角色移动（§6.4 第 5 条）等有人要用再做。

体素地形的 512 候选上限 / AB 混合（01 §12.6.3）和这几批独立，单独定。

## 8. 要用户定的

1. **线程模型**：两种模式（推荐，§4.2）；还是只留绑定线程（Rigid Body 节点失去并行）。
2. **共享线程池**：默认 `min(4, 核数 − 2)` 个线程，少于约 128 个醒着的刚体不开（推荐，§5.2）；要不要给玩家一个配置项改线程数。
3. **异步 step 先不做**（推荐，§5.3），等第 3 批量过再说。
4. **结果对象**：零分配的结果对象为主、新建对象的写法留作便捷版（推荐，§6.2）；还是只保留新建对象的写法（简单，但热路径每次分配）。
5. **分批顺序**：§7 的 1 → 2 → 3（推荐）；还是先 2（先让 ragdoll 能接），地基边做边补（后面会返工）。

**✅ 用户裁决（2026-10-28）**：「好」⇒ 五条都按推荐：两种线程模式；共享池 `min(4, 核数 − 2)`、少于约 128 个醒着的刚体不开（玩家配置项没要，先不做）；异步 step 先不做；零分配结果对象为主、新建对象的写法留作便捷版；分批 1 → 2 → 3。体素 512 上限 / AB 混合（01 §12.6.3）不在这五条里，仍待定。

## 9. 进度

### 9.1 第 1 批：地基（2026-10-28）

做了什么（和 §4、§6 的差别写在后面）：

| 块 | 做法 |
|---|---|
| 线程模式 §4.2 | `ThreadMode.OWNER_THREAD`（默认）/ `EXCLUSIVE`，在 `WorldDef.threadMode` 里选。`world.use()` 返回 `Use`（`try (var use = world.use())`）：独占模式下 CAS 占住、持有者可以嵌套、别人来占立刻抛 `WrongThreadException`；绑定模式下只是检查是不是拥有者，所以两种模式写同一份代码。`tryUse()` 不抛、占不到返回 null。`transferTo` 只给绑定模式。 |
| 分层 §6.1 | `B3` 挪到 `internal` 包、`@ApiStatus.Internal`；`Box3DException` 挪到 `physics`。 |
| 结果对象 §6.2 | `CastResult`（最近一个）、`CastHits`（全部，从近到远）、`Overlaps`、`ContactPoints`、`MoverPlanes`：调用方建一次反复传，数组不够就自己长大再问一次，按下标读。便捷版：`castRayClosest / castShapeClosest` 不传结果对象时回一个 `CastHit` 值。查询形状 = `Geometry` + 位置 + 朝向（盒子用 8 个角点、球 1 点、胶囊 2 点，不支持 mesh）。新增全部命中的射线 `castRay`。`QueryFilter` 代替两个裸 long。0.3.2（2026-11-02，EntityStudio 把受击查询搬过来）：全部命中的形状扫掠 `castShape`（同射线：从近到远、一个形状一个、开始就重叠的是 0）。0.3.4（2026-11-02 傍晚，ES doc 56 §19.10）：射线 / 扫掠 / 两个重叠 / mover 平面的 native 临时内存按实际个数分配（栈上 16 / 32 / 16 个，多了翻倍上堆），不再按结果对象的容量——结果对象只长不缩，原来一次大查询之后同一个结果对象的每次查询都替它付 malloc / free（容量 2 万时一条碰 8 个的射线 8 µs，现在 1.2 µs）。 |
| 地形格子 | 打在地形上的命中 / 接触点 / mover 平面 / 命中事件，**在同一次 native 调用里**把格子坐标算好带回来（fork 新函数 `b3Shape_GetVoxelCell` 换掉 `b3VoxelGrid_GetFeatureCell`）；Java 的 `VoxelGrid.featureCell` 删了——不会再拿过期的 id 去查，也不会查到别的世界。没有按 §6.2 原想法把方块状态 id 放进材质表：那样碰撞体按状态分开，数量可能顶到 65535 的上限；MC 层拿格子坐标自己去 `level.getBlockState` 就行。 |
| 事件 §6.3 | `world.events()`：接触开始 / 结束 / 命中、传感器开始 / 结束，每步拷一次（接触一次 JNI、传感器一次），一帧的几步累计，`beginInterpolationFrame` 清（从不开帧的世界只留最后一步的）。命中事件带地形格子：Box3D 取命中点和材质的那个三角形的格子（fork `1c5d823` 给命中事件加了 `triangleIndex`）。事件默认都关（Box3D 的默认），谁要谁开；地形段和实体代理的形状明写关（Box3D 是「任一方要就报」）。 |
| 刚体状态 §6.3 | 移动事件顺带拷回每个醒着的刚体的包围盒和线 / 角速度，`Body` 缓存；改它们的调用（设速度、冲量、传送、改形状、睡醒、改类型、启停）让缓存失效、下次读时再问。地形 streamer 和实体代理每 tick 不再逐个刚体调 JNI。 |
| 附件 | `Body / Shape.attach(Class, value)` / `attachment(Class)` 代替 `userObject`，各 mod 按自己的类型挂，不抢一个槽。 |
| 生命周期 §6.6 | 世界 / mesh 数据忘了 `close`：`Cleaner` 在被回收后释放并记警告。 |
| MC 层 §6.5 | `LevelBodies.setNeedsTerrain / setPushedByEntities`（按刚体，不设就跟类型走）代替两个全局 `setConsumers`；`LevelPhysics / TerrainStreamer / EntityProxies` 各一个 `Settings`（带检查的 setter）；`LevelPhysics.create(level, def)` 建私有的，`prepare()`（Level 线程：开帧、地形、实体代理）+ `step()`（任何持有世界的线程）；独占模式下别的线程正占着世界时，区块进出事件排队到下一次 `prepare`。 |

和 §6 的差别：事件是两次 JNI（接触、传感器）不是一次；方块身份走格子坐标不走材质表（上面写了原因）；`events()` 挂在世界上，接口和 `movedBodies()` 一样按帧。

测试：JUnit 66 → 78（`ThreadModeTest` 4：独占模式的进出 / 嵌套 / 拒绝、绑定模式的 `use`、8 个小世界在 4 线程池上每帧换线程 step、8 线程抢 2000 次从不同时进去；`WorldEventsTest` 4：落地开始 / 命中带格子 / 方块没了结束、两边都不要就没有、传感器进出、100 个同一步落地的开始全都到；`PhysicsApiTest` +4：缓存的包围盒 / 速度在每种改动之后都和 Box3D 直接问的一样、附件、转过的查询形状、丢掉不关的世界被回收；`VoxelDetailTest` 改成结果对象，加了全部命中的射线）。GameTest 23 → 25（不想被推的刚体不带代理；独占的私有世界在线程池上 step、别的线程占着时区块卸载排队）。

### 9.2 第 1 批 review（2026-10-28，两个只读 opus review 并行：一个审原生桥和线程，一个审 API、MC 层和测试）

没有高级问题。中级 8 个，都修了：

| # | 问题 | 怎么修的 | 测试 |
|---|---|---|---|
| 1 | 独占的私有世界空着时，Level 线程上的区块事件会顺手占住它；姿势 worker 恰好这时要用就抛——正是 §4.2 说不会有的「看时机」的失败 | 区块事件只在「当前线程本来就能用这个世界」时当场处理（绑定模式的拥有者、独占模式里已经占着的那个线程），否则排队到下一次 `prepare`；队列改成并发的 | GameTest：世界空着时卸载区块，段还在、没人占着 |
| 2 | `TerrainStreamer.update()` / `EntityProxies.update()` 是 public 却不查线程：线程池上调会在别的线程读区块、和 Level 线程抢集合 | 两个都查 Level 线程，不是就抛 | GameTest：池子上占着世界调 `update()` ⇒ `WrongThreadException` |
| 3 | 私有 `LevelPhysics` 忘了关 ⇒ 连 Level 一起泄漏（streamer 表是以 Level 为键的弱表，值又强引用着 Level） | 记下每个 Level 的私有物理，Level 卸载时还开着的关掉并警告 | 没测：GameTest 里卸不了 Level |
| 4 | 不开帧的世界事件越攒越多 | 从没调过 `beginInterpolationFrame` 的世界每步先清：只留最后一步的 | JUnit |
| 5 | 两步之间删掉的形状，Box3D 在下一步之后才报它的结束事件，那时 Java 已经把它从表里拿掉 ⇒ 事件里是 null，和地形分不开；javadoc 还说反了 | 上一步之后删掉的形状留到这一步的事件读完 | JUnit：删掉落地的球，下一步的结束事件还叫得出它 |
| 6 | 清理器可能在 native 调用还没返回时释放世界：方法最后一次用 `this` 可以早于 native 调用返回，JIT 可以提前认定世界不可达 | 每个调用都经过的 `exit()` 里 `Reference.reachabilityFence(this)` | 没测：取决于 GC 时机，测不稳 |
| 7 | 命中事件的格子取的是最深的接触点，Box3D 取命中点和材质的是接近最快的那个点 ⇒ 横跨两种方块落地时，格子和材质可能是两块 | fork 给命中事件加 `triangleIndex`（`1c5d823`），桥用它算格子 | JUnit：箱子横跨石头和泥土落在三个位置，格子和材质一致（⚠️ 没法证明这个测试在旧代码上会红：两种取点不一定分得开） |
| 8 | `setAwake(false)` 让整座岛睡下，Box3D 里岛上别的刚体速度都变 0，缓存只清了被调的那个 | 世界上一个「运动版本号」，会动到别的刚体的调用（睡下、改类型、启停）加一，缓存比对它 | JUnit：两块贴着滑的箱子，一块睡下，另一块的缓存速度和 Box3D 直接问的一样 |

另外修了测试本身：8 线程抢锁的测试原来看不到抢锁线程里的异常、也不检查真的抢过（`refused > 0`）。

低级的记下没修（等你定）：
- 改形状不清速度缓存：刚体在转、加个形状移动质心时，Box3D 按 `w × Δ质心` 改了线速度（Box3D 默认更新质量），缓存还是旧的。
- `readContactEvents` 重新问的条件没看 `hitData` 够不够（今天两个数组一起长，不会出事；改了初始大小就会读到旧数据）。
- 清理器释放的世界里有 mesh 形状时，mesh 数据不还引用 ⇒ native mesh 泄漏（不是用后释放）。
- 清理器里先删网格再删世界，删网格抛了异常世界就不删了。
- 结果对象强引用着世界（常被放进静态字段复用）⇒ 世界永远不会被清理器回收；`TerrainStreamer` 的表同理。
- `transferTo(null)` 之后世界永远用不了（`enter()` 里 NPE）。
- 查询抛异常时结果对象还留着上一次的数量。
- 绑定模式的世界在别的线程收到区块事件：现在静默排队（原来会抛），排队用的是并发队列，不会坏数据。
- `LevelPhysics.tick()` 在 `use()` 抛的时候（独占的私有世界正被别人占着）也会把整个物理停掉；`close()` 在 `use()` 抛的时候什么也不关。
- §6.1 说的 `@ApiStatus.Experimental` 没加；`bounds(float[])` / `counters(int[])` 还是裸数组；结果类之间命名不统一（`isTerrain` / `terrain` / `onTerrain`，`userMaterialId` / `materialA`，只有 `CastHits` 有 `cellX(i)`，`ContactPoints` / `MoverPlanes` 没有 `body(i)`）；没命中时读结果抛 `IndexOutOfBoundsException` 而不是 `IllegalStateException`。
- 测试缺口：缓存在 `setType` / `setEnabled` / `setTargetTransform` / 点冲量 / 删形状之后；超过 64 个刚体后的缓存；mesh 的清理器；命中和传感器缓冲长大；同一帧里开始又结束；`Settings` 的检查；`tick()` 停掉的路径。

修完全量：JUnit 82、GameTest 25 全绿（随机位置跑了两次）。

### 9.3 第 2 批：关节、刚体、形状、世界（2026-10-28）

用户 10-28「我基本都认可…开工吧」（doc 04 §7 第 4 条：先做这一批）。§6.4 第 1–4 条全做了，第 5 条（角色移动）照 §7 留到有人要。

| 块 | 做法 |
|---|---|
| 关节 §6.4.1 | Box3D 全部 12 种：`SphericalJoint` / `RevoluteJoint` / `WeldJoint` / `MotorJoint` / `DistanceJoint` / `PrismaticJoint` / `WheelJoint` / `ParallelJoint` / `FilterJoint` / `MoverJoint` / `PogoJoint` / `MotionLockJoint`（2026-11-25），加上我们的 `ConstraintJoint`，各一个定义类（`…JointDef`，字段和 Box3D 一一对应，默认值从 Box3D 读）。`world.createJoint(def)` 按定义的类型回对应的关节。坐标系：`def.bodies(a, b).worldFrame(x, y, z, rotation)` 从一个世界位姿算两个本地坐标系（双精度相减，几百万格外也准），关节建好就是静止的。运行时每个属性都有 getter / setter（setter 先查 Box3D 会断言的范围：锥角 0–π/2、目标角 ±π、下限 ≤ 上限、非负…）。关节跟着任一端的刚体、世界一起失效；`body.joints()`、`world.joints()`。**断裂事件**：`events().jointEvents()`（力 / 力矩过阈值，每步一次；Box3D 只报不断，断不断由读的人定）。2026-11-07（0.3.7，EntityStudio doc 61 的物理角色）：上游的动态角色两种也接上——`MoverJoint`（B 坐标系 xz 平面里的速度驱动 + 力上限）、`PogoJoint`（沿 B 的 y 轴托地的弹簧，拉 / 压各有上限；`impulse()` / `currentSpeed()` 可读，照示例重建时热启动）；JUnit `MoverJointTest`。⚠️ 两者都和别的关节一样默认 `collideConnected = false`：角色挂在箱子上就会穿过箱子，示例和 ES 都打开。 |
| 桥 | 9 个定义类一张字段表（按 `offsetof` 读写，一个循环），运行时属性两个调用 `jointSet` / `jointGet` + 参数编号（C 的 `enum mb3_joint_param` ↔ Java 的 `JointParam`，桥先核对关节类型，类型不对抛异常而不是让 Box3D 断言）；刚体 / 形状 / 世界的标量设置同样按编号。删了 6 个被取代的单独函数。 |
| 刚体 §6.4.2 | `massData` / `setMassData` / `applyMassFromShapes` / `worldCenter`；阻尼、重力倍数、睡眠阈值、安全系数的 get / set；子弹、可睡、快速旋转、接触回收的开关；`setFixedRotation`（2026-11-25 前是 `setMotionLocks`，上游删了，按轴锁改用 `MotionLockJoint`）；`applyForce`（某点）/ `applyTorque` / `applyAngularImpulse`；`pointVelocity`（某点的速度，doc 04 带人走要用）；`toWorld` / `toLocal`；只对这一个刚体的 `castRay` / `castShape` / `overlaps` / `closestPoint`。0.3.4：`setTransform(x, y, z, rotation, wake)`（PhysX `setGlobalPose(pose, autowake)`；原签名 = 叫醒）——只靠瞬移移动、没人要回应它的刚体（查询体积）不用醒：查询照样碰到睡着的，醒着的每步都要算（ES 800 个在动的碰撞体：三步 0.71 → 0.05 ms / tick）。0.3.5（doc 04 §17）：标签 `setTag` / `setIgnoredTag`——忽略某个标签的刚体和带这个标签的刚体不碰（桥的自定义过滤里查，只对开了自定义过滤的形状），`PhysicsWorld.sweepBox(…, tag, out)` 扫的盒子带标签时也穿过它们；MC 层用它做「刚体不碰某个实体」（`LevelBodies.setIgnoredEntity`）。 |
| 形状 §6.4.3 | `Geometry.Hull`（记录里存的是 Box3D 算出来的凸包顶点，≤ 128）、`Geometry.HeightField` + `HeightFieldData`（和 `MeshData` 一样按形状计数、只给静态刚体）。`setGeometry(geometry, updateBodyMass)` 原地换成球 / 胶囊 / 盒子 / 凸包 / 网格（同一个形状、id、过滤，Box3D 重建接触、叫醒刚体；换掉的网格 / 高度场释放引用）。密度、摩擦、反弹、整个表面材质、网格每三角形的材质、过滤的读写；事件开关读写；`isSensor`；`sensorOverlaps`（传感器里现在有谁）；`closestPoint`。查询形状可以是凸包。 |
| 世界 §6.4.4 | `explode`（Box3D 的爆炸，可带 mask；负值是吸）；反弹阈值、命中阈值、最大速度的 get / set；`setContactTuning`（Box3D 没有 getter）；睡眠 / 连续碰撞开关的 get / set；`profile(Profile)` 分阶段耗时。 |
| 最近点 | Box3D 的 `b3Body_GetClosestPoint` / `b3Shape_GetClosestPoint` 是世界坐标的 float（上游注释说这是有意的）⇒ 桥自己做：在目标点的坐标系里跑 `b3ShapeDistance`，几百万格外也准到万分之一。 |

和 §6.4 的差别：
- 「传感器运行时改」做不到：Box3D 只有 `b3Shape_IsSensor`，是不是传感器建形状时定。
- 「接触 / 关节参数」：接触是世界级的 `setContactTuning`；关节是每个关节的 `setConstraintTuning`（Box3D 没有世界级的关节参数）。
- 碰到上游 bug：关节偏差（`linearSeparation` / `angularSeparation`）拿刚体朝向比、离轴只量一个方向、轮子的角度偏差直接断言 ⇒ fork `0db645d` 修了（02 §3.1）。
- 顺手修了 §9.2 的一条低级项：加形状、换几何、改密度（更新质量时）之后清速度缓存（质心变了 Box3D 会改线速度）。
- 和 EntityStudio：查到 ES 的伤害盒部件实体会被当成实体做代理去推刚体 ⇒ 代理默认跳过部件实体（`EntityShapes`，doc 04 §8.2 #1）。

测试：JUnit 82 → 123（`JointTest` 17：每种关节每个属性设了读回来、桥拒绝别的类型的属性、定义的默认值是 Box3D 的、9 种各自做该做的事（摆的锥角限位对照不限位、铰链马达转速和限位、焊接扛重力、棱柱只沿轴滑到限位、杆定长 / 绳到限位、马达关节跟目标、轮子转速、过滤关节对照不过滤、平行关节扶正）、几百万格外按世界坐标系放的关节一建好就静止、阈值事件每步一次并能当场断开、关节跟着刚体 / 世界走、Box3D 会存下坏值的参数先拒绝、坐标系里的偏差（fork 修的三处）；`BodyApiTest` 10；`ShapeApiTest` 9（凸包、高度场和洞、只给静态刚体、原地换几何后刚体停在新形状上、换掉的网格被释放、材质 / 过滤 / 开关读回、传感器里有谁、形状的最近点）；`WorldSettingsTest` 5（爆炸只推范围和 mask 里的、衰减区推得少、吸、设置读回、限速、耗时））。GameTest 25 → 26（部件实体默认没有代理、提供者给形状就有）。

### 9.4 第 2 批 review（2026-10-28，两个只读 opus review 并行：一个审原生桥和 fork 改动，一个审 Java API 和测试）

高 1 个、中 5 个，都修了：

| # | 问题 | 怎么修的 | 测试 |
|---|---|---|---|
| 高 1 | Java 认的「单位四元数」是 \|q\|² 差 1e-3 以内，Box3D 断言的是 20 个 float ε（约 2.4e-6）⇒ 手写的 `(0, 0.7071, 0, 0.7071)` 过了 Java、在 Box3D 里断言；关节坐标系 / 球铰目标 / 盒子形状这几条路**先存进去再抛**（`setGeometry` 之后 Java 和 Box3D 各记一个形状）。马达关节每 tick 喂动画的旋转，必中。第 1 批的 `BodyDef.rotation` 一样 | 桥的 `mb3_quat` 统一归一化，关节定义的两个坐标系和球铰目标读进来后也归一化（Java 照旧拒绝离单位长度远的） | `JointTest.aNearlyUnitRotationIsTakenAsTheUnitOne`、`BodyApiTest.aNearlyUnitRotationIsTakenAsTheUnitOne`（定义 / 刚体 / 盒子形状 / 换几何 / 目标位姿 / 传送都用 0.7071） |
| 中 1 | fork `0db645d` 之后的角度偏差还有两处错：刚体四元数从不翻符号，自由转的铰链每转半圈读成 2π；「清掉 z 再求角」在铰链快转半圈时放大偏摆；超限时报整个转角 | fork `f005141`（02 §3.1） | `JointTest.aSpinningHingeIsNeverOffItsAxis`（三圈里偏差 < 0.01）、超限只报超出的 0.2 |
| 中 2 | 带高度场的刚体 `setType(DYNAMIC)`：Box3D 不出声地不改，Java 却记成动态 ⇒ `type()` 骗地形 streamer 和 `LevelBodies` | Java 先拒绝（`IllegalStateException`） | `BodyApiTest.aBodyWithAHeightFieldStaysStatic` |
| 中 3 | `setMassData` 只查有限：行列式 < 0 Box3D 先存后断言；两个负对角（行列式 > 0）不断言、刚体越转能量越大 | `Checks.inertia`：对称、对角非负、主子式和行列式非负 | `BodyApiTest.anInertiaThatIsNotOneIsRefused` |
| 中 4 | 新加的缓存失效几乎没测到：测试里第一次读速度都在改动之后（缓存本来就空），删掉 `forgetMotion` / `motionEpoch++` 照样绿 | 只补测试 | `BodyApiTest.movingTheCentreOfMassOfASpinningBodyIsSeenInItsCachedVelocity`（先 step 填缓存，转着的刚体挪质心：加形状 / 改密度 / 换几何 / 质量数据 / 回到形状的质量 / 删形状，每次都核对 Box3D 并确认速度真变了）、`anExplosionIsSeenInTheCachedVelocityOfWhatItPushed` |
| 中 5 | 「设了读回来」钉不住参数编号：两个同形状的编号在 Java 里对调，设和读走同一个错编号照样读回 | 只补测试：每种关节用每个字段都不同的定义建（定义按字段名走 C 的表，和编号无关），再用运行时 getter 读 | `JointTest.whatADefinitionSaysIsWhatTheGettersRead` |

另外修了冒烟测试：原来靠非单位四元数触发断言，现在桥会归一化，换成 NaN 位置。

低级的记下没修（等你定）：
- 最近点：目标在球 / 胶囊里面时回距离 0 + 表面上的点（Box3D 的 `useRadii`），在凸包里面时回目标点本身——两种不一致。
- `HeightFieldData` / 桥里 `countX * countZ`、凸包 `3 * count` 是 int 乘法，极端大的数会溢出（今天 Java 的数组长度挡着）；读凸包点之后没查有没有挂起的 JNI 异常。
- 桥的内存安全靠 Java 先查（例：`b3Shape_SetMeshMaterial` 断言后照样越界写）：只要没人绕过 `physics` 直接调 `B3` 就没事。
- `worldGetJointEvents` / `shapeGetSensorOverlaps` 每个元素一次 `SetLongArrayRegion`。
- `setGeometry` 认定「原生抛了 = 什么都没存」；高 1 修了以后够不着，保守的做法是把世界标成出错。
- 高度场在世界关闭时、在建到动态刚体失败后的释放没断言；`ShapeApiTest` 有一处 `HeightFieldData` 留给清理器。
- 命名 / 一致性：没有读重力的 `gravity()`（桥有）；`JointDef.userData` 只写不读（同 Body / Shape）；`WeldJointDef.springs()` 和别处的 `spring()`；`DistanceJointDef` 没有 `motor()`；`SphericalJointDef.coneAngle` 是限位而 `SphericalJoint.coneAngle()` 是当前角（Box3D 的命名）；`BodyDef.safetyFactor` 收 ≥ 0 而 `setSafetyFactor` 要 0.01–0.5；`setContactTuning` 要 > 0（Box3D 收 ≥ 0）；`Joint.other()` 对不相干的刚体回 bodyA；`Hull.MAX_VERTICES` 写死 128；`Hull.points()` 是可改的数组、记录的 `equals` 比数组身份；`JointParam` 注释里写的测试名不对。
- `Body.toWorld` / `toLocal` 每次分配一个四元数和一个向量。
- Box3D 有、这批没接的（要不要接你定，不自己加）：`WorldDef` 里已有的几个设置的运行时 setter（反弹迭代 / 传播、各向同性惯量容差）、接触回收距离、warm starting、推测接触开关；`b3World_OverlapAABB`；`b3Shape_RayCast` / `ComputeMassData` / `ApplyWind` / `GetContactData`；按给定位姿查一个刚体；本地点的速度。
- ✅ 实体代理变大小改成 `setGeometry` 原地改（用户 2026-10-29「好，认可」，做法和测试见 04 §9.3）：同一个刚体、别人挂的关节 / 拿着的引用不断，代理记着的推动状态也不丢。⚠️ 接触照样重建——Box3D 的 `b3Shape_Set*` 走 `b3ResetProxy`，把这个形状的接触全删了重找（`shape.c` 1393），和删了重建一样；问用户时我说成「接触接着算」，是错的，已当面更正。

修完全量：JUnit 131、GameTest 26 全绿。
