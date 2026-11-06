# MineBox3D · 约束关节（`b3_constraintJoint`）+ 物理资产用的批量接口和体对表

状态：**现行**（2026-10-31，fork `0461202` 起、review 轮 `8d8f198` `7d514eb` `0704530` `c6edf55`；MineBox3D 0.3.0，mavenLocal）。2026-11-01：三轴全锁的点改推 A 自己的锚点（fork `0f7bdf4`，§11，MineBox3D 0.3.1）。
来由：EntityStudio doc 56 第 2 批（物理资产 + 编辑器）§17.3，用户 10-31「好 开工」= 一个关节 = 一个 UE 约束（新关节，不给 spherical 打补丁）。调研：ES `docs/design/56-research/box3d-joints-vs-ue-constraint.md`（下称研究 J）。

## 1. 一句话

一个 Box3D 关节 = 一个 UE 物理资产约束（`FConstraintInstance` / `FConstraintProfileProperties`）：六个自由度各 Free / Limited / Locked，摆角两种形状，三组限位各自的软度和恢复系数，线性逐轴驱动 + 角驱动（SLERP 或 twist / swing），parent dominates，质量调理；**设置整份换**（换 profile 不删关节）。新文件 `native/box3d/src/constraint_joint.c`，老文件只在分派点加分支（02 §3.2）。

## 2. 约定

- A = 父、B = 子。frame B 的 x = twist 轴；Swing1 绕 frame A 的 z、Swing2 绕 frame A 的 y——**和 UE / Chaos 一样**，物理资产的帧和数字原样传。
- 相对旋转 q = qA⁻¹·qB，分解 q = swing·twist（twist 绕 x）。swing 的旋转向量：swing1 = 绕 z 的分量、swing2 = 绕 y 的分量。
- 弧度、米。线性 = frame B 的锚点在 frame A 轴上的分量。
- 弹簧（软限位、驱动）写 (k, c)：加速度模式 1/s²、1/s（**乘行的有效质量**，角向行 = 刚体绕质心的转动惯量，不是绕关节的——UE 一样）；物理单位模式 N/m、N·m/rad。k = 0 是纯阻尼（Box3D 的 `b3MakeSoft(hertz)` 表达不了）。Hz + 阻尼比的写法在 ES 换算（doc 56 §17.2）。
- MaxForce 0 = 不限（照 UE；和 Box3D motor 的「0 = 关」相反）；按轴夹（SLERP 三根 B 轴各夹、swing 两根各夹，照 UE）。**加速度模式下上限也乘这一行的有效质量**（UE 默认的线性求解器 `FAxisConstraintDatas::SetMaxForce` 除以行的逆质量；UE 的非线性求解器不乘，UE 自己两套不一致，照默认的）。

## 3. 每子步解什么（顺序 = 硬的最后、优先级最高）

1. **角驱动**：三行绕 **frame B 的 x / y / z**（UE 的）。误差 = 「B 相对目标姿势」：SLERP 取它的旋转向量；twist / swing 取它的 swing-twist 分解。速度目标：SLERP 是 frame A 里的向量投到 B 轴；twist / swing 是 x = twist 速率、y = swing2、z = swing1。**任一角锁住时 SLERP 什么都不驱动**（UE：`PBDJointConstraints.cpp` Sanitize 把 SLERP 标志清掉；twist / swing 标志在 SLERP 模式下本来就是关的）。锁住的角上的 twist / swing 驱动不驱动。
2. **twist 限位**：Jacobian = (xA + xB) / (1 + xA·xB)（swing-twist 分解里 twist 的精确导数）。
3. **swing 限位**：
   - 两个都 Limited、椭圆：两限位差 < 1e-3 = 圆，沿 swing 轴（精确）；否则 **UE 的椭圆锥**：swing 旋转向量到椭圆的最近点（Eberly 的方程，牛顿从区间左端单调逼近，几步收敛；原来的二分到浮点精度要几十步），沿那点的法线推回（UE `GetEllipticalConeAxisErrorLocal` 同理）。**够不着、手里也没冲量就整行跳过**：椭圆是一个范数的单位球，在它 r 处的点离边至少 (1 − r) × 短半轴，swing 点的速度不超过 |(rate1·w, rate2·w)| ⇒ 这一子步（prepare 用整步）走不到边，这一行没有冲量要放 ⇒ 它推的就是 0，跳过和解一遍完全一样（第一版只省最近点、还拿旧轴解这一行，旧轴把 twist 算进来——§10.1）。
   - 两个都 Limited、金字塔：各管各的。
   - 一个 Limited、另一个 Locked：只管这一个。
   - 一个 Limited、另一个 Free：**dual cone**——frame B 的 x 偏出 free 那个 swing 转动的平面多远（Chaos 同名）。
   - swing 分量的速率用**精确**导数（`b3GetSwingRates`：由 b = qA⁻¹·xB 推出，含绕 frame A 的 x 转时 swing 被带着转的那一项）。只用 zA / yA 近似时，椭圆上压着的刚体停的位置偏（实测见 §6）。
4. **角锁**：三个都锁 = weld 的 3×3 块；一个 swing 锁、twist 不锁 = dual cone 为 0 的一行（用四元数分量会把 twist 混进来）；其余 = 锁住的四元数分量（revolute 锁两个分量的写法，含 twist 时精确）。
5. **线性驱动**（frame A 三轴，锁住的轴不驱动）→ **线性限位**（Limited 的轴合起来：三轴球、两轴圆柱、一轴平板，UE 同）→ **线性锁**（三轴点约束块、两轴 2×2、一轴标量）。**力臂**：驱动推在 A 自己的锚点（UE `:1875`）；限位和部分锁管的是「B 的锚点在跟着 A 转的轴上」，所以两个刚体都推在 **B 的锚点**（UE `ConnectorXs[1] - CurrentPs[0]`、Box3D 棱柱关节的 `rA + d`）——这样速率里有 A 转动带着轴转的那一项、角动量守恒。warm start 和 prepare 取的恢复速度同样分开。**三轴全锁例外**（§11）：就是一个点，各推在自己的锚点（UE `InitLockedPositionConstraint` 从 A 的力臂里减掉锁住的分量 = A 的锚点；Box3D 球关节），冲量存成世界向量。

没有驱动、也没有要放掉的驱动冲量的关节（大多数）跳过整个角驱动（不算旋转向量和三根轴）。

**硬限位** = Box3D 其它关节的写法：没到 = speculative，过了 = 关节的约束软度纠正，relax 刚性。**恢复系数**：relax 里这一行在推、且这一步开头是以超过阈值的速度冲过来的 ⇒ 目标速度 = −e × 来速（来速在 prepare 取，Box3D 接触的做法；阈值照 UE：线 0.01 cm/s、角 0.01 rad/s）。**软限位** = 弹簧阻尼，solve 和 relax 都用（不然回弹被 relax 抵掉），**只在越过后推**。

**放手**：关掉的驱动 / 没弹簧的软限位 / 奇异处（摆过 176°，UE 的 `p.Chaos.Joint.DegenerateRotationLimit` = −0.998；原来写的 −0.995 只有 174.3°，比最大摆角 174.6° 还小，review 抓到）不解的行，把上次的冲量放掉——不像上游的弹簧那样冻住一直推（§5 第 2 条就是那个 bug）。

**最小限位**（UE Sanitize）：硬角限位 < 0.01 rad 当锁；软的保持 0.01。硬线性限位 < 0.1 mm 当锁（没方向）。

## 4. 质量

- **parent dominates**：`parentInverseMassScale` × 父体的逆质量 / 逆惯量（0 = 父体不受子体影响；UE 的 bParentDominates 和 Chaos 的 ParentInvMassScale）。
- **质量调理**（UE 默认开，我们默认也开）：照 `FPBDJointUtilities::ConditionInverseMassAndInertia`——每个刚体的主惯量拉到 1:5 以内（UE 线性映射 [lo, hi] → [hi/5, hi]；我们的惯量是满张量，用同一个仿射映射 a·I + b，主轴不变）；父体质量不少于子体的 1/5，父体最大主惯量不少于子体的 1/5。⚠️ 副作用（UE 一样）：关节按改过的惯量算冲量，细长体绕自己长轴（如头发的一节）对关节的响应变慢——持续外力下停的位置会偏（实测 §6）。所以测量刚度 / 平衡位置的测试关掉它。

## 5. 换设置、warm start

- `b3ConstraintJoint_SetSettings` 整份换：**只清改了「这一行管什么」的冲量**（锁 / 限位位、软硬、形状、驱动开关和弹簧有无、模式），只改数值的保留——profile 只调刚度不会抽一下。
- warm start：每类冲量存在上次施加的方向上（线性 = frame A 的轴、驱动 = frame B 的轴、限位 = 各自的轴；**三轴全锁的点是世界向量**，§11），prepare 按当前姿势重算方向（Box3D 惯例）。

## 6. 实测（`ConstraintJointTest`，60 Hz × 4 子步，8 kg、0.8 m 的杆挂在静态锚点下）

| 场景 | 结果 |
|---|---|
| 球窝 + 重力摆 3 秒 | 锚点漂 < 5 mm |
| twist 限位 [−0.2, 0.5]，30 N·m 绕杆自己的细轴顶 | 停在限位 ±0.02 rad（软约束在大力下的稳态误差 τ/(Iω²)：杆绕长轴 I = 0.013 kg·m²） |
| 椭圆 0.6 / 0.3，(25, 25) N·m 斜着顶 | 一直在椭圆上（≤ 1%）；无摩擦地沿边滑，加阻尼后停在 swing1 / swing2 = 4.09，**正是「法线顺着推力」那点**（推算 4.0–4.15；径向推回会停在 1 : 1） |
| 金字塔 0.6 / 0.3 同样顶 | 两个都到限位、都不超 0.02 |
| 铰链（twist + swing2 锁、swing1 0.4） | swing1 到限位、不超 0.02；锁住的两个 < 0.01 |
| dual cone（swing1 0.4、swing2 Free） | 偏出不超 0.02；swing2 照样转过 0.8 |
| 一个 swing 锁、twist 自由 | 锁住的偏差 < 0.02，另外两个随便转 |
| 线性球 0.2 m，400 N 拉 | 0.2 ± 0.01 m |
| 软 twist 限位 50 N·m/rad（物理单位）、5 N·m | 越过 0.1 rad = τ/k ✓ |
| 硬 twist 限位，3 rad/s 撞上，e = 0.8 / 0 | 2.4 rad/s 弹回 / 停住 |
| SLERP、twist&swing、速度驱动、线性驱动 | 到目标 ±0.01；锁一个角时 SLERP 不动 |
| 驱动上限 20 N·m 顶 40 N·m | 顶不住（UE 按轴夹） |
| 上限 60：加速度模式 vs 物理单位（同一根弹簧），顶 40 N·m | 加速度模式 = 60 × 0.43 kg·m² = 26 N·m 顶不住；物理单位 60 N·m 顶得住 |
| 关驱动 / 位置驱动刚度 0 | 关节力矩立刻 0 |
| parent dominates（0 vs 1） | 父体被拉动 < 1 mm vs > 5 cm |
| 质量调理（0.02 kg 链节吊 320 kg） | 开着比关着拉开得少 |
| 滑轨（x 限 1 m、y / z 锁）挂在转着的父体上，子体 1 m 外，无重力转 2 秒 | 离轨 < 2 mm，绕 z 的角动量守恒到 1% |
| 体对表：重叠着重新允许 / 碰着时禁掉 / 删表 | 推开 / 穿过去 / 推开（`b3Shape_RefreshFilter`） |
| 两个关节一批设目标（睡着的） | 各自醒来转到自己的目标；目标里有 NaN ⇒ 抛异常、什么都没存 |

## 7. 桥和 Java

- 定义表加两种字段：`'E'` Java 枚举 ↔ C 枚举（取 ordinal；写回用 `Class.getEnumConstants()` 缓存）、`'O'` 嵌套对象（自己的一张表）。`ConstraintJointSettings` 是独立对象（换 profile 只传它），里面的 `ConstraintDrive` × 6、`ConstraintLimitResponse` × 3 各是对象。字段名两边对不上 = 启动时 `NoSuchFieldError`（和其它定义一样）。
- `ConstraintJoint`：`settings / setSettings / setDriveTargets / angles / linearOffset`。
- **批量**：`ConstraintJointBatch`（一次设全部关节的驱动目标 13 float、可顺便叫醒——睡着的物理动画 ragdoll 要醒了才跟新目标；一次读全部关节的状态 8 float）；`BodyBatch`（一次设全部运动学目标）。两个批量的值在 Java 里按单个调用的规矩先查（NaN、旋转），Box3D 的断言会先存下坏值。读刚体位姿不用批：`Body.position` 本来就不进 Box3D。
- **体对表** `BodyPairTable`（`native/src/mb3_pairs.c`）：一组体之间哪些对不碰；Box3D 的 custom filter 在 C 里查（不回调 Java、多线程只读），只问开了 `enableCustomFiltering` 的形状。一个体最多在一张表里、表里不许重复；体销毁后编号被复用 ⇒ 按 id 的 generation 认不出、当不在表里。表改了（`apply`）或删了，行变了的体用 fork 的 `b3Shape_RefreshFilter` 丢掉接触、下一步重新找对（Box3D 只问没有的对）⇒ **已经重叠的对也立刻按新表**（编辑器边模拟边改碰撞组要的）；代价是这些体这一步的接触 warm start 没了。

- **摩擦 / 弹性的合并方式**（`SurfaceMaterial.frictionCombine` / `restitutionCombine`，`native/src/mb3_materials.c`）：UE 物理材质的 Average / Min / Multiply / Max，两面取枚举里靠后的那个（UE 的 `max`），没设的一面当 Average。两面都没设 ⇒ Box3D 原样（摩擦 √(a·b)、弹性取大）——地形、别人的刚体一点不变。Box3D 的混合回调不带上下文、只给两边的 `userMaterialId` ⇒ 模式（序号 + 1，0 = 没设）藏在 `userMaterialId` 的最高字节，每个世界建的时候装回调；Java 永远看不到这个字节（进去时打包、出来时查询结果 / 事件 / 读回材质都抹掉），用户的 id 用到最高字节 ⇒ `Checks.material` 拒绝。实测（`SurfaceCombineTest`）：0.9 的地面、0.1 的箱子滑行摩擦 无 / 平均 / 最小 / 乘 / 最大 = 0.3 / 0.5 / 0.1 / 0.09 / 0.9。

## 8. 偏离 UE（当面说过的在 ES doc 56 §17.7；这里是实现层的）

- 椭圆定义在 swing **旋转向量**上（UE 在 4·atan(tan(φ/4)·u) 上）：两个轴上完全一样，斜向的差随角度长——1 rad 1.1%、1.5 rad 2.5%、2 rad 4.2%（review 量的；原来写的「< 1%」只对小限位成立）。金字塔同样在旋转向量分量上（UE 在摆过的帧的轴上取 4·atan(MRP)），差 < 1°。
- SLERP / twist&swing 的误差用精确的旋转向量 / swing-twist 分解（UE 用小角近似）。行的方向、按轴夹上限、加速度模式都照 UE。
- twist 下 / 上限分开（UE 对称一个值）——超集。
- 一个 swing 锁、twist 自由：我们用 dual cone 为 0 的一行（只锁那个摆）；UE 锁四元数分量 R01.Y / Z（`InitLockedRotationConstraints`），twist 自由时会把 twist 混进来。
- 硬线性限位 < 0.1 mm 当锁：UE 的 Sanitize 没有这一条（我们这时没方向）。
- 恢复系数在 relax 里按这一步开头的来速给（UE 在速度阶段按初始速度给），阈值相同。⚠️ 角限位的恢复系数在 UE 默认求解器里**实际不起作用**（转动的 motion type 从不设成 Limited，只有位置约束调 `SetMotionType`，`:516`；`:1733` 的角分支走不到）——我们的角弹回是超出 UE 的，研究 J 当时没核实（review 核实）。
- 不做：ContactDistance（speculative 已起作用）、ShockPropagation（UE 默认关）、Projection Alpha；**teleport projection** 和塑性放 ES / Java（研究 J §5.3）。
- 录制 / 回放不认约束关节：建的时候在录 ⇒ 断言并返回空；有约束关节时开始录制 ⇒ 拒绝（和体素一样）。

## 9. 没做 / 之后

- `b3JointSim` 是所有关节类型的 union：新关节最大（review 手算 ~640 字节，其中设置副本 292），所有关节的 sim 从 ~444 变 ~830 字节（+85%，不是研究 J 估的 +60%），休眠 / 唤醒 / 重新着色时整块拷。要省就把求解器不读的设置挪出 sim。没量。
- parent dominates 的缩放和质量调理的先后和 UE 反了（UE 先缩放：solver body 的 `InvM()` 已含 `InvMScale`）：0 和 1 两个值结果一样（`bParentDominates` 只给这两个），0–1 之间不一样（1 kg 父、10 kg 子、0.5：UE 2 kg、我们 4 kg）。ES 只给 0 / 1。
- 上游：棱柱关节的弹簧 / 马达 / 限位都在 `fixedRotation` 判断里（`prismatic_joint.c:466/490/512`）：锁了转动的刚体在滑轨上这几行不解、之后解锁还冻住。我们不用，没修。
- 测试覆盖不到的（review 列的）：`SetSettings` 的「只清改了管什么的冲量」没有测试（全清 / 全不清都过）；质量调理只断言「开着比关着少」；运动学父体、睡眠唤醒、overflow 里的约束关节、奇异附近都没测。

## 10. review（2026-10-31，两个只读 opus 并行审 `bf85691..03b4ad6` + 桥 + Java + 测试）

数学（swing-twist 分解、twist Jacobian、swing 速率、dual cone 导数、四元数分量锁、Eberly、(k, c) → softness、恢复系数、warm start / 放手、SetSettings 清冲量）两个都逐条推过，没问题。修了的（fork `8d8f198` `7d514eb` `0704530` `c6edf55` + 桥 / Java）：

| 级别 | 问题 | 修法 |
|---|---|---|
| 中高 | 线性限位 / 锁在父体上的力臂用父体自己的锚点：滑轨挂在转着的父体上看不到轴被带着转，跟不上、角动量不守恒 | §3 第 5 条 |
| 中（两个都报） | 加速度模式的驱动上限没乘行的质量（UE 默认求解器乘） | §2 |
| 中（两个都报） | 两个批量接口不查 NaN（单个调用查），坏值进求解器 | §7；顺手：约束批量加「叫醒」、体对表查下标和重复 |
| 中（性能） | 椭圆最近点二分到浮点精度（几十步），每关节每步算 ~10 次；没驱动的关节也算一遍旋转向量 | §3 第 3、5 条 |
| 中低 | 体对表改了以后已重叠的对不重新过滤（编辑器边模拟边改碰撞组时要等分开） | `b3Shape_RefreshFilter`，§7 |
| 中低（上游） | 平行关节、距离关节同一类「不解的行冻住冲量」，上一轮漏了 | 02 §3.1 |
| 低（两个都报） | 奇异阈值 −0.995 比最大摆角还小、和注释的 176° 不符 | 改成 UE 的 −0.998（一行常数、和注释矛盾，顺手） |

记下没修的低：§8 新加的四条偏离、§9 的 union 大小 / 缩放先后 / 棱柱上游 / 测试覆盖。

### 10.1 第二轮（修复本身也是新代码：一个只读 opus 审 `8d8f198`..`c6edf55` + 桥 / Java 的修复 + 合并方式）

| 级别 | 问题 | 修法（fork `bd2316d`） |
|---|---|---|
| 高 | 牛顿求椭圆最近点：另一个 swing 是浮点噪声（1e-9～1e-6，帧没对齐世界轴时就是这样）时起点舍入到极点 ⇒ NaN ⇒ 返回区间右端，或一步就停 ⇒ c 错 20 倍、法线歪 15–37°（审的人用 float 仿真了旧新两版）。二分没有这个问题 | 在 t = s + 1 上从 t = z1 起步（不相减）、相对停止；z1 < 1e-6 当在长轴上（Eberly 的 y1 = 0 分支）。测试 `anEllipsePressedAlongItsWideAxisStopsThereWithTheOtherSwingAtNoise`（整套转 0.7 rad，没推的 swing 读 1e-8～3e-7，停在 0.6004） |
| 中 | 「够不着就不算」不给轴、照旧沿旧轴解这一行：旧轴不垂直于 twist ⇒ 自由 twist 的速度进了这一行、被刹住（例：贴着椭圆边绕自己转 5 rad/s 刹到 4.3）；「结果完全一样」的说法不成立 | 只在这一行没有冲量时跳过，而且整行跳过（推的就是 0，真的完全一样）；prepare 跳过时弹回速度记 0。测试 `aBarSpinningOnItsOwnAxisBesideTheEllipseKeepsItsSpin` |

记下没修的低：
- 地形材质去重的键（`BlockColliders.MaterialKey`）没有两个合并方式：两种方块只差合并方式时共用第一个的材质。现在没有提供者设合并方式；有人设了再加进键。
- 体对表刷新比那一对宽：两个体所有形状的所有接触都丢（含地形、没开自定义过滤的形状），开了接触事件的会多一对结束 / 开始；**睡着的两个体重叠着被重新允许时，新接触进了睡眠集合，醒之前照样穿在一起**（编辑器无重力暂停时可能遇到）——要就在刷新时叫醒。
- 测试：`SurfaceCombineTest` 里 MIN（0.1）和 MULTIPLY（0.09）在 ±0.03 里分不开；两面都设了模式的「取后者」没测；弹性 MAX / MULTIPLY 没测；最高字节只在世界射线上测了抹掉。滑轨测试只覆盖了 solve 的那一半力臂（warm start 的 `momentA`、prepare 的 `armA` 改回去也过）。
- §3 第 5 条说「照 UE」略过了：三轴全锁时 UE 的 `InitLockedPositionConstraint` 从 A 的力臂里减掉锁住的分量，等于用 A 的锚点；我们用完整的 d——差别只在锁的误差上，我们的是精确 Jacobian。**⚠️ 记成低是错的：关节被拉长时这一条把轻链节转飞，2026-11-01 修了（§11）。**

## 11. 三轴全锁的点：A 自己的锚点 + 世界向量（2026-11-01，fork `0f7bdf4`，MineBox3D 0.3.1）

**来由**：ES 物理资产例子（doc 56 §17.13.5，云灵）模拟 0.4 秒必炸 `b3IsValidVec3(v)`（solver.c:736），先甩飞的总是腰链最上面一节。把那条链（8 节：最上 26 g，往下 50 / 69 / 126 / **383 g 的珠子** / 坠子 92 / 311 / 178 g；扭 ±20°、两个摆 45° 椭圆；现场导出的质量、惯量、关节帧）原样搬进 `ConstraintJointChainTest`，挂在不动的锚点下，0.43 秒 NaN——和编辑器无关。

**排查**（同一条链换设置，看 5 秒里最快的转速）：角度全放开（只剩点约束）也转到 366–500 rad/s；**同样的链换 Box3D 自带的球关节只到 113（0.17 秒时）、后两秒 55**；8 子步、或者每节一样重都不炸。差别只在点约束：

1. 父体推在 B 的锚点（§3 第 5 条）。重物挂在轻链节下，软关节被拉长好几厘米，父体被推的点离它自己的锚点几厘米 ⇒ 每个子步一个凭空的力矩；速率里还多一项 `wA × d`：轻链节绕长轴一转，这一项就是几 m/s，求解器去抵消它、又把转动推回链节——正反馈。**这是主因**：只改它，点约束和球关节逐子步一样（测试比的就是这个：半秒里每节差不到 1 mm；修之前差 25.8 cm）。
2. 累积冲量按 A 的三根轴存、warm start 按 A 现在的轴还原：A 一转，托着下面重量的冲量跟着转成横推。单改这一条不够（还是 0.55 秒 NaN），但和球关节、我们自己的三轴角锁（本来就是世界向量）不一致，一起改了。

**修法**：三轴全锁时不加 d（solve 不改 `rA`，warm start 的 `momentA` 用 `rA`），`linearLockImpulse` 存世界向量（`joint.h` 注释写了两种含义；`SetSettings` 换锁的位时本来就清零，两种存法不会混）。部分锁和限位照旧——它们的行就是 A 的轴，B 的锚点是对的；三轴的球形限位两种力臂结果一样（d 和法线平行）。

**修后**：原样设置（扭 / 摆限位 + 质量调理）5 秒不炸，但最快 160 rad/s、后两秒还有 139——**还在转**：26 g 的链节绕自己长轴的惯量只有坠子的 1/700，限位拦不住下面整串的扭转，关节被拉长 1–7 cm。这是软约束 + 极端质量比本身的事（球关节、不加限位也在 55 rad/s 转），不是这次的 bug；8 子步时 5 rad/s。ES 的例子里同一种零件每节一样重（UE 的 Mass Override）。

**还看到、没修**（记下，问用户）：
- 整条链都开 parent dominates 时链尾越甩越快（5 秒 5000–7500 rad/s，开不开质量调理都一样）：子体从父体拿能量而父体不损失，8 级下去在链尾放大。UE 一样不守恒；短链、有阻尼时看不出。
- 质量调理在速度层求解器里不守恒角动量（关节按调理过的惯量算冲量，积分器按真的）。这条链上开不开差不多（只剩点约束时都是 55），没看到它单独出问题。

测试：`ConstraintJointChainTest` 两个（`aPointLockedEveryWayIsHeldAsBox3DsSphericalJointHoldsIt`、`aLightChainUnderAHeavyBeadHangsWithoutBlowingUp`）；改回旧代码两个都红（一个 NaN、一个差 25.8 cm）。JUnit 全量 196/196。
