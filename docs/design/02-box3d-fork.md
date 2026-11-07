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
| `0db645d` | `src/joint.c` `b3Joint_GetLinearSeparation` / `GetAngularSeparation` | 三处（2D 移植留下的）：① 角度偏差拿两个**刚体**的朝向比，不是两个**关节坐标系**的 ⇒ 坐标系转过的关节（两刚体朝向不同时几乎总是）一建好就报偏差（实测：刚建的焊接关节报 0.7 rad）；② 棱柱 / 轮子关节的轴取刚体 A 的 x 轴而不是坐标系 A 的，离轴距离只量了一个垂直方向（3D 有两个）；③ 轮子关节的角度偏差 `B3_ASSERT( false )`（todo）⇒ 我们的构建里问一下就抛异常。修法：坐标系相乘后再比；离轴 = 去掉轴向分量后的长度；轮子先返回 0 不断言 | 写关节测试（`JointTest.separationsAreMeasuredInTheJointsFrames`） | 未报 |
| `f005141` | `src/joint.c` `b3Joint_GetAngularSeparation` | 接着上一条（review 抓到）：① 刚体的四元数积分从不翻符号 ⇒ 相对旋转可能取了「远的那一边」，自由转的铰链每转半圈读成 2π；② 铰链 / 平行关节「去掉铰链角」是把 z 清零再求角，铰链转到快半圈时一点点偏摆就放大（179° 时 0.01 rad 读成 1.04）；③ 铰链超过限位时报整个转角，不是超出的那部分。修法：相对旋转先取近的一边；铰链 / 平行用摆角（swing-twist 分解的 swing）；超限 = 摆角 + 超出量 | review（`JointTest.aSpinningHingeIsNeverOffItsAxis`） | 未报 |
| `db02250` | `src/motor_joint.c` `b3PrepareMotorJoint` | 一部分的上限设成 0（或弹簧 hertz 设 0）后 solve 跳过它，但 setter 不清冲量、warm start 照旧施加四份冲量 ⇒ 旧冲量每子步一直推（放手的木偶还被托着）。修法：prepare 里 solve 会跳过的部分清掉冲量 | 物理资产调研（ES doc 56 研究 J §5.2；`JointTest.aMotorPartTurnedOffLetsGo`） | 上游 issue #190 |
| `b57da80` | `src/revolute_joint.c` / `prismatic_joint.c` / `spherical_joint.c` / `distance_joint.c` / `wheel_joint.c` 的 prepare | 同一类：弹簧开着时把 hertz 设 0，`b3MakeSoft(0)` 全 0 ⇒ solve 里冲量增量为 0、冲量冻住，warm start 每子步照施加（distance 软杆的弹簧分支干脆跳过）。修法：hertz 为 0 的弹簧在 prepare 里放掉冲量（distance 只在软杆时，刚杆共用这个冲量） | 同上（`JointTest.aSpringSetToZeroHertzLetsGo`） | 未报 |
| `7ec31b5` | `src/spherical_joint.c` `b3GetSphericalJointTorque`、`src/joint.c` `b3GetJointReaction` | 报出的力矩（也是断裂判断用的）里，锥限位的冲量是加上的、求解时是减的；twist 限位的冲量放在 B 的 z 轴上、求解时沿 twist Jacobian ⇒ 锥和 twist 同时出力时报的力矩和断裂都偏。另外 `b3GetJointReaction` 在求解的工作线程里读刚体变换重算轴。修法：两处都按施加时的轴（prepare 存的 `swingAxis` / `twistJacobian`）、锥减，抽成 `b3GetSphericalJointAngularImpulse` | 同上（`JointTest.aSphericalJointReportsTheTorqueItApplies`） | 未报 |
| `03b4ad6` | `src/joint.c` `b3SolveJoints_Overflow` | 图着色放不下（一个刚体上超过 20 个关节）的关节进 overflow，单线程解，但不按阈值检查 ⇒ 永远不出关节事件、断不了。修法：照 `b3SolveJointsTask` 检查，记在主求解线程（worker 0）的位表里 | 同上（`JointTest.aJointInTheSolversOverflowIsReportedToo`：去掉修复只报 20 / 40） | 未报 |
| `0704530` | `src/parallel_joint.c` `b3PrepareParallelJoint` | 和 `db02250` / `b57da80` 同一类，那一轮漏了：max torque 设 0 ⇒ solve 跳过、冲量照 warm start；hertz 设 0 ⇒ `b3MakeSoft(0)` 全 0、冲量冻住。修法：prepare 里两种都放掉冲量 | 约束关节 review（`JointTest.aParallelJointTurnedOffLetsGo`） | 未报 |
| `c6edf55` | `src/distance_joint.c` `b3PrepareDistanceJoint` | 同一类：限位和马达只在软杆分支里解，`b3DistanceJoint_EnableLimit` 不清冲量 ⇒ 绷直的弹簧绳关掉限位后旧限位冲量一直拉着；杆改成刚的（关弹簧）后限位 / 马达冲量也冻住。修法：prepare 里不解的那部分放掉冲量（`EnableMotor` 上游本来就清，留着） | 约束关节 review（`JointTest.aDistanceLimitTurnedOffLetsGo`） | 未报 |
| `4ff2d43` | `src/physics_world.c` `b3World_CastRay` / `b3World_CastRayClosest` / `b3World_CastShape` | 离原点远时世界射线 / 形状扫掠漏掉自己穿过的小形状：树按 float 走，射线起点舍入成 float，`b3TestBoundsRayOverlap` 的节点中心、半宽也在 float 里算——一千万格外一格一个数，半格线上往下打的射线穿过两个 0.4 的球，有时一个都不报（看树长成什么样）。上游的形状扫掠用了向外舍入的盒子，但节点测试自己的舍入照样会漏。修法：射线改成「一个点的盒子」走树（同形状扫掠那条路，回调里照旧从双精度原点精确打每个形状）；两种扫掠的盒子先按扫到的最远坐标长四个 float 步（`b3FarCastBox`：一千万格外几格，出生点附近约等于零）。`b3World_CastMover` 同一个写法没改（桥不用）。**⚠️ 这个放宽法被 `a2bbdf0` 取代** | EntityStudio 把受击查询搬到 MineBox3D 后，它的 GameTest 在随机的远坐标上偶发红（ES doc 56 §19.5）；`ShapeApiTest.castsTenMillionBlocksOutMeetTheSmallShapesTheyRunThrough`：200 处，不修漏 1 处 | 未报 |
| `18613b5` | `src/body.c` `b3Body_Disable` | 睡着的刚体被禁用：刚体挪进禁用集合，原来的睡眠集合空了却不释放（`b3DestroyBody` 会释放，Disable 没有）⇒ 没人再叫醒它，世界关之前也不释放：每禁用一次漏一个集合和它的数组。ES 的碰撞体改成一直睡着（ES doc 56 §19.10）后每次关掉都走这条；MineBox3D 地形流送「停放」静止刚体本来就在漏。修法：照 `b3DestroyBody` 释放变空的睡眠集合 | ES doc 56 §19.10 的 review；`BodyApiTest.aSleepingBodySwitchedOffAndOnKeepsNothingBehind`：不修时 1000 次开关多 447 296 字节 | 未报 |
| `a2bbdf0` | `src/dynamic_tree.c` 新 `b3DynamicTree_CastFrom`（声明在 `collision.h`）；`src/physics_world.c` `b3World_CastRay` / `CastRayClosest` / `CastShape` / `CastMover` | 取代 `4ff2d43` 的 `b3FarCastBox`：那个按最远坐标放宽，770 万格外每边 2 格 ⇒ 同一条碰 8 个的射线 6 µs（近处 1.4 µs）；`CastMover` 根本没放宽，远处会漏。修法：新遍历——每个节点先减去起点的 float 值（附近的节点这一步精确），再减去舍入余数和扫掠盒，裁到线段自己的包围盒里，最后才做上游的边轴测试；放宽 = 8 个 float 精度 ×（扫掠长度 + 1），和离原点多远无关。长度用 double 加、放宽夹在 `FLT_MAX` 内（review：1e20 的扫掠让放宽成无穷、空节点的边界成 NaN、被当成叶子 ⇒ 越界读）；空节点按标记跳过。四个世界扫掠都改用它，删 `b3FarCastBox`。同一条射线：770 万格 6 → 1.6 µs，100 万格 2.5 → 1.1 µs，近处 1.4 → 1.1 µs | ES doc 56 §19.9 / §19.10；`ShapeApiTest`：远坐标两个（加斜射线 / 斜扫掠、「节点从原点横跨过来」的一千万 / 两千五百万格）+ `aCastAsLongAsAFloatGoesDoesNotTakeTheEmptyNodeForAShape`。⚠️ 裁剪的必要性只在 review 的精确仿真里显出来（去掉裁剪 10 万例漏 1745）：真实形状的包围盒有余量，Java 测试里不裁剪也全中 | 未报 |

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
| `ecc0160` | `b3Shape_GetVoxelCell(shape, triangleIndex, …)` 换掉 `b3VoxelGrid_GetFeatureCell`：桥在查询 / 接触点 / 事件的同一次调用里把格子算好（调用方保证是同一个世界的 id） | `03-threading-and-api.md` §9.1 |
| `1c5d823` | `b3ContactHitEvent` 加 `triangleIndex`（`types.h` 末尾一个字段，`solver.c` 建命中事件时填）：命中点和材质取自哪个三角形，桥用它算地形格子，格子和材质就不会对不上 | 同上 §9.2 |
| `5495a21` | `b3Shape_CastShape(shape, origin, proxy, translation, canEncroach)`（`box3d.h` 一个声明，`shape.c` 挨着 `b3Shape_RayCast` 两个函数）：对一个形状扫。Box3D 的世界 / 刚体扫掠都会命中传感器、又没有「对一个形状扫」⇒ MineBox3D 让 MC 实体逐形状扫、跳过传感器。带半径的一对（球、胶囊）开 `canEncroach` 时走 `b3CastRoundedMover`：把 `b3ShapeCast` 给盒子移动者的「贴着只认往里、停在半个 slop 外」按**表面**距离算——`b3ShapeCast` 自己的规则按核心距离算，站在球上每扫一次往里进 5 mm、远处扫过来停在表面里面一个 slop。⚠️ 这一条对上游也算 bug（`canEncroach` 对带半径的一对），但只改了我们自己的新函数，`b3ShapeCast` 没动 | `04-entities-and-bodies.md` §11、§11.5 |
| `bf85691` | `b3Shape_GetUserMaterialId(shape, childIndex, triangleIndex)`（`box3d.h` 一个声明，`shape.c` 挨着 `b3Shape_CastShape`）：包一层内部的 `b3GetShapeUserMaterialId`。桥的逐形状扫掠原来报 `materials[0]`，网格 / 高度场 / 体素扫到哪个三角形都报同一个材质；现在和世界查询报的一样 | `04-entities-and-bodies.md` §12 |
| `0461202` | 约束关节 `b3_constraintJoint`（新文件 `src/constraint_joint.c`）：一个关节 = 一个 UE 物理资产约束——六个自由度各 Free / Limited / Locked、椭圆或金字塔摆角限位、软限位和恢复系数、线性驱动、SLERP 或 twist / swing 驱动、parent dominates、质量调理；设置整份换（换 profile 不删关节）；开始录制拒绝它 | `05-constraint-joint.md` |
| `8d8f198` | 约束关节 review 轮：线性限位 / 锁在父体上的力臂改成子体锚点（UE `ConnectorXs[1]`、Box3D 棱柱 `rA + d`；warm start 和恢复速度同改）；加速度模式的驱动上限乘这一行的质量（UE 默认求解器 `SetMaxForce`）；奇异阈值 -0.995 → -0.998（UE，原来比最大摆角还小）；椭圆最近点改牛顿 + 够不着就不算、没驱动的关节跳过驱动 | `05-constraint-joint.md` §9 |
| `bd2316d` | 约束关节第二轮 review：椭圆最近点的牛顿改在 t = s + 1 上从 t = z1 起步（原来在 s 上从 z1 − 1 起步，z1 小到浮点噪声时起点舍入成极点 ⇒ NaN ⇒ 返回区间右端；z1 ≈ 1e-6 时绝对停止条件一步就停）+ z1 < 1e-6 当长轴上；「够不着就不算」只在这一行没有冲量时用、并且整行跳过（原来照旧拿旧轴解，旧轴把自由的 twist 速度算进来、会刹住 twist），prepare 里跳过时弹回速度记 0 | `05-constraint-joint.md` §10.1 |
| `7d514eb` | `b3Shape_RefreshFilter`（`box3d.h` 一个声明，`shape.c` 挨着 `b3Shape_SetFilter`）：丢掉形状的接触、标记代理移动 ⇒ 下一步重新问过滤器。Box3D 只对没有的对问过滤器，体对表改了以后已经重叠的对原来要等分开才生效 | `05-constraint-joint.md` §7 |
| `0f7bdf4` | 约束关节三轴全锁（点约束）：父体推在**自己的**锚点（UE `InitLockedPositionConstraint`、Box3D 球关节）、冲量存成世界向量（`joint.h` 注释；warm start 的 `momentA` 同改）。原来推在子体锚点、冲量存在父体会转的轴上：重物挂在轻链节下、关节被拉长几厘米时，轻链节绕自己长轴一转，托着重物的冲量就横着推它，越推越转，0.4 秒 NaN（ES 云灵的腰链）。部分锁 / 限位不动 | `05-constraint-joint.md` §11 |

`0461202` 碰到的老文件（合上游时逐个核对）：

| 文件 | 改了什么 |
|---|---|
| `include/box3d/types.h` | `b3JointType` 末尾加 `b3_constraintJoint`；`b3DefaultWheelJointDef` 后面加约束关节的枚举、设置、定义和两个默认函数 |
| `include/box3d/box3d.h` | `wheel_joint` 组后面加 `constraint_joint` 组 |
| `src/joint.h` | `b3ConstraintJoint` 结构、`b3JointSim` union 成员、声明 |
| `src/joint.c` | 默认定义、建（录制时拒绝）、反力、力 / 力矩、两个偏差、prepare / warm start / solve、绘制各一个分支 |
| `src/physics_world.c` | 开始录制拒绝约束关节（和体素同一处） |
| `src/CMakeLists.txt` | 加 `constraint_joint.c` |

`2d87e17` 碰到的老文件和位置（合上游时逐个核对；新文件 `include/box3d/voxel.h`、`src/voxel.h`、`src/voxel.c` 不会冲突）：

| 文件 | 改了什么 |
|---|---|
| `include/box3d/types.h` | `b3ShapeType` 末尾加 `b3_voxelShape`；`b3ContactHitEvent` 末尾加 `triangleIndex`（`1c5d823`） |
| `src/solver.c` | 建命中事件时填 `triangleIndex`（`1c5d823`） |
| `src/shape.h` | 几何 union 加 `voxel` 指针 |
| `src/contact.h` | `b3MeshContact` 加 `voxelTriangles`；`b3DeclareArray( b3Triangle )` |
| `src/contact.c` | 接触注册 3 行；`b3_simMeshContact`；`b3UpdateContact` 走 mesh 分支；销毁时释放 `voxelTriangles` |
| `src/mesh_contact.c` | `b3RefreshCache` 取体素三角形；`b3ComputeMeshManifolds` 两处取三角形、平边鬼碰撞规则（`b3IsFlatFeatureGhost`）、体素顶点鬼碰撞（`b3IsVoxelGhost`，`b9e9f98`）、近似面法线的边接触改成面法线（`1caa529`）、推出接触、材质 |
| `src/shape.c` | margin / 创建 / 只许静态与非 sensor / 包围盒 / 质心 / `b3CreateVoxelShape` / 射线 / 扫掠 / 重叠 / mover / CCD / `b3GetShapeUserMaterialId` |
| `src/body.c` | `b3Body_SetType` 拒绝体素；两个刚体射线 / 扫掠的材质 id |
| `src/physics_world.c` | 世界销毁释放 `voxelTriangles`；调试绘制跳过体素；开始录制拒绝体素；`b3ValidateContacts` 分支；世界射线 / 扫掠的材质 id |
| `src/CMakeLists.txt` | 加三个新文件 |

## 4. 同步记录

### 4.1 2026-11-06：上游 `f36d061` → `3362633`（fork 合并提交 `ad60fa1`，用户还没推；合并前的头打了标签 `minebox3d-before-sync-2026-11-06`）

- 来由：用户「在开始修之前，我希望先 rebase 一下 box3d，确保有 latest 的 feature，我看到有 mover 相关的 support」。按 §2 用 merge（fork 已推送），没 rebase。
- 上游只多一个提交：**Dynamic Mover (#202)**——从 Box2D 移植的动态角色移动：角色是锁了旋转的动态胶囊刚体（会被推、也推别人），`b3_moverJoint` 在关节 B 坐标系的 xz 平面里把速度拉向目标、冲量按 `maxForce × h` 封顶；`b3_pogoJoint` 是沿 B 的 y 轴的弹簧（拉 / 压各有最大力）把它托离地面、上台阶不碰胶囊。示例 `samples/dynamic_mover.*`（「应该拷进你的项目里改」）、`geometric_mover.*`（原来的 `mover.*`，几何扫掠式）。其余是格式化（行宽 100）、录制 / 回放的 mover 操作、SIMD 头去掉一个函数。
- 冲突三个文件，全是「两边都在末尾加」：`types.h` 的 `b3JointType` 取上游的 mover / pogo 在前、我们的 `b3_constraintJoint` 在后（数值 9 → 11）；`box3d.h` 只是注释换行；`joint.c` 各个分派 switch 两边的 case 都留。`b3GetJointReaction` 的球关节：上游那边只是换行，留我们的 `7ec31b5`。
- ⚠️ 枚举值变了 ⇒ 桥和 Java 跟着改（MineBox3D 主仓库，未提交）：`JointType` 在 `WHEEL` 后加 `MOVER`、`POGO`（顺序 = Box3D 枚举，桥传序号；不加的话 Java 的 `CONSTRAINT` 序号 9 会建成 mover 关节）；`mb3_joint.c` 的 `s_kinds` 给两者占位（Java 暂不建）。
- 2026-11-07：Java 接上了 mover / pogo（`03-threading-and-api.md` 关节一行），`s_kinds` 的占位换成真的字段表。
- §3.1 的上游 bug：这次上游一个都没修（相关文件只有格式化），全部保留。
- 验证：重编 native；MineBox3D JUnit 210、GameTest 74、MP 3/3（61 项）全绿。
