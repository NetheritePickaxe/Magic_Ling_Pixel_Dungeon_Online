# 局域网联机 —— 实现说明与使用方式

对应规划：`docs/lan-multiplayer-plan.md`
实现形态：独立模块 `:lan` + core 侧极薄的 `LanHooks` 桥接（无 javaagent、无字节码织入）

---

## 1. 怎么用

### 房主（开放房间）

1. 正常进入一局游戏（单人玩到任意楼层）。
2. 游戏内菜单 → **「对局域网开放」**（位于「兑换码」按钮下方），直接打开开放设置。
3. 按需勾选 **PvP**（默认关闭）与 **局部回合制**（默认**开启**），并可点 **端口** 按钮修改监听端口 → **开放联机**。
   局部回合制开放后也可随时在同一窗口开关，切换会广播提示给所有玩家。
4. 窗口里会显示 `本机IP:端口`，让房客在标题界面输入这个地址。

> 开放时会先把当前进度存档，**并自动在「存档备份」里生成一份备份**（文件名前缀 `SP-`，
> 例如 `SP-slot1-DTK-KSC-PVS-20261027-204500.mlsp`）。联机过程继续用原来的单人槽位存档，
> 出问题可以从这份备份恢复。端口配置会持久化到游戏本地目录，下次默认沿用。

### 房客（加入房间）

1. 标题界面 **「多人游戏」** 按钮（横屏排在第 1 行的第 3 列，竖屏在底部整行），进入多人游戏页面。
2. 进入 **「服务器列表」** 页面：已保存的服务器会列在这里，点击即可连接。
   - **直接加入**：输入 `192.168.x.x` 或 `192.168.x.x:端口` 并连接，不保存地址。
   - **添加服务器**：只保存地址，保存后回到服务器列表（最多 8 条）。
3. 主机校验协议版本与游戏版本 → 分配 playerId → 下发全量快照 → 房客自动进入游戏。
4. 职业取「上次游玩的职业」，名字取设置里的英雄名。

> 标题「多人游戏」是独立页面，下设「服务器列表」；
> 想开房直接进单人游戏，在局内菜单点「对局域网开放」，不再经过多人游戏页面。

### 存档备份（与联机的关系）

联机不再使用单独的联机存档槽位，改为复用单人存档 + 自动备份：

- **开放联机时**：先存档，再把该槽位打包成 `.mlsp` 备份放进「存档备份」目录，文件名带 `SP-` 前缀。
- **备份不覆盖**：文件名带时间戳（`yyyyMMdd-HHmmss`），同一槽位可以备份无限份，不再一个槽只留一份。
- **重命名**：标题界面「存档备份」→ 点任意备份 → 「重命名」，只改名字、扩展名仍是 `.mlsp`。
  重命名后丢了 `slot`/`whole-save`/`SP-` 前缀也没关系，导入时按包内容判断是局内还是局外存档。
- **恢复**：点备份 → 「导入备份」→ 选目标槽位 → 确认覆盖。

### 本机双实例自测

```
./gradlew desktop:debug          # 实例 A：进游戏 → 菜单 → 对局域网开放
./gradlew desktop:debug          # 实例 B：标题 → 多人游戏 → 127.0.0.1
```

---

## 2. 实现结构

```
core/src/main/java/.../lan/LanHooks.java        桥接接口（唯一接触面，默认空实现）
core: GameScene / Hero / Item / Mob / TitleScene / WndGame 共 10 处轻量调用点

lan/src/main/java/.../lan/
├── Protocol.java      帧协议（4 字节长度 + 1 字节类型 + Bundle JSON）
├── LanConnection.java 单连接的收发封装
├── Lan.java           门面：模式、房间设置、唤醒 Actor 线程
├── LanSnapshot.java   房客端快照落盘（写到内部槽位 game999）
├── LanNet.java        本机 IP / 地址解析
├── HostSession.java   主机：内嵌服务器、命令执行、增量广播、快照下发
├── HostPlayer.java    主机侧玩家会话
├── HostLanHero.java   主机侧远端玩家实体（时间线挂起/唤醒）
├── LocalTurns.java    局部回合制：活动玩家、独立时钟、范围冻结/恢复
├── ClientSession.java 房客：快照安装、增量应用、输入转命令
├── LanHeroSprite.java 远端玩家外观（继承 HeroSprite，隔离 Dungeon.hero 与镜头）
├── LanScene.java      多人游戏主页（服务器列表入口 + 开房说明）
├── LanServersScene.java 服务器列表页（已保存服务器 / 直接加入 / 添加服务器）
├── WndLanHost.java    「对局域网开放」窗口（PvP / 局部回合制 / 端口配置）
├── WndLanJoin.java    「直接加入」窗口（只连接，不保存地址）
├── WndLanAddServer.java 「添加服务器」窗口（只保存地址）
├── LanHosts.java      已保存服务器地址的持久化（最多 8 条）
├── LanBridge.java     LanHooks 的唯一实现
├── Ui.java / Log.java 小工具
```

`desktop` 依赖 `:lan`，`DesktopLauncher` 启动时一行装配：`LanHooks.install(new LanBridge())`。
`core` 完全不认识 `:lan`；去掉这行 + 去掉 desktop 的 `:lan` 依赖即为纯单机构建。

---

## 3. 同步模型

- **主机权威 + 行动命令 + 状态同步**，不做确定性锁步。
- 房客**不创建本地 Actor 线程**（`GameScene` 里由 `LanHooks.suppressActorThread()` 关闭），
  场景、动画、UI 照常跑，世界只由主机事件驱动。
- 房间命令（渲染线程入队 → 主机的 Actor 线程执行）：

| 命令 | 主机侧动作 |
| --- | --- |
| `CMD_CELL` | `HostLanHero.handle(cell)`，与单人输入完全同一条路径 |
| `CMD_WAIT` / `CMD_REST` | `hero.rest(false/true)` |
| `CMD_ITEM` | 按背包序号找到物品后 `item.execute(hero, action)` |

- 快照：`game.dat` + `depth*.dat` 两份 Bundle 原样下发，房客写进内部槽位 `game999/`
  （独立的内部槽位，不在存档列表里）后用现成的 `InterlevelScene(CONTINUE)` 加载，复用完整读档链路。
- 增量：每回合（主机英雄 ready 且远端英雄全部空闲）比对一次，
  只发变化的角色状态（坐标/HP/上限/存活）、新增角色（带类的 Bundle）、
  消失角色、地图数组（变化时）、物品堆（变化时）。
- 自愈：`seq` 递增，房客发现层级不一致或 `seq` 断裂会回退到请求全量快照。

### 时间线（关键设计）

`Actor.process()` 的平局判定是 `actPriority` 高者先行动，且**只有当前 actor 调 `next()` 才能清空
`Actor.current`**；一旦某个非主机英雄作为"暂停点"返回 false，主机就再也唤不醒 Actor 线程（死锁）。

因此：

- 远端英雄空闲时 `diactivate()`（时间 = `Float.MAX_VALUE`），彻底不参与选取；
- 收到命令时 `timeToNow()` + `actPriority = HERO_PRIO + 1`，保证与主机英雄同时间点时先被选中；
- `HostLanHero.act()` **永远返回 true**，世界暂停点因此恒定落在主机英雄身上。

### 局部回合制（可选，默认开）

开启后不再全局共享一条时间线，而是"谁行动，就推进谁附近的回合"：

- 以**活动玩家**（最近有待办行动的玩家）为基准，用原版英雄的**视野（FOV）**判定哪些角色参与 `Actor.process` 选取：
  调用 `Level.updateFieldOfView(活动英雄, ...)`，只有落在视野内（含墙体/黑暗/照明/远见天赋/守望者穿草/烟雾等原版规则）的角色与 buff 才推进；
  视野外全部冻结，**远处玩家的怪物、buff、冷却都不动**。
- 靠近的其他玩家落在同一范围内，自然共享这段时间；房主与房客靠近时和原来的共享时间线手感一致。
- 每个玩家有独立局部时钟；切换活动玩家或角色进出范围时用 `Actor.shiftTime` 平移时间轴，
  **保留剩余冷却、不补跑冻结期间的回合**。关闭局部回合制时也会把所有冻结 actor 对齐回当前时钟。
- 局部模式下的暂停点：活动区域内已没有其他待办玩家时，远端英雄 `next()` 清掉 `Actor.current`
  后返回 false（不挂起到 MAX）；仍有其他玩家行动时才 `diactivate()` 让出调度。
- core 侧只新增 3 个轻量钩子：`LanHooks.beforeActorSelection / isActorSchedulable / onActorTimeFixed`，
  单机默认空实现，行为不变。
- 边界：`blob`、楼层 `respawner` 等无坐标 actor 不拆区域，跟随当前活动区域的时间；
  活动范围**跟随原版视野规则**（视距、墙体遮挡、黑暗、照明、天赋、烟雾等），不随窗口/摄像机像素大小变化，
  也与"探迷雾""怪物觉醒/notice"的判定基准一致。

---

## 4. 完成度（对照里程碑）

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 快照组装/恢复、房客无 Actor 线程跑 GameScene、双实例互连 | ✅ |
| M1 | 桥接点、开放/加入窗口、移动命令与状态增量、共享时间线 | ✅ |
| M2 | 战斗/物品/休息；全队换层（主机换层后自动给全员重发快照）；开放前自动备份存档 | ✅（投掷/法杖选格未做） |
| M3 | `seq` 校验 + 快照自愈、心跳、掉线提示、加入/离开提示 | ⚠️ 部分（无断线重连、无聊天、无 UDP 房间发现） |
| M4 | fat jar / jpackage 集成 | ✅（`-Plan` 纯单机开关未做，见 §2 末尾的替代做法） |

### 刻意未做 / 已知限制

1. **投掷、法杖施法、需要选格子的物品动作**：主机上会弹出它自己的选格 UI，语义不对，
   房客端直接提示「暂不支持」，不会执行（避免状态分叉）。
2. **房客换层必须由房主发起**：房客点楼梯会被拒绝并提示。
3. **死亡**：房客阵亡只是移出调度（不再行动、怪物不再攻击它），没有旁观/复活流程；
   房主阵亡仍然是整局结束（与单人一致）。
4. **视野**：MVP 由各端按自己的英雄位置本地算 FOV（局域网信任环境，不做信息裁剪）。
5. **远端英雄不入存档**：联机槽位只保存房主的英雄，房客角色在房主退出后丢失。
6. **断线重连**：掉线即移除该玩家，重连会被当作新玩家（新角色）。
7. 房主与房客的 `appVersionCode`、协议版本必须一致，握手强校验。
8. **局部回合制**：范围外的一切不推进（含其他玩家的 buff/冷却），非定位的全局效果
   （毒气/火焰等 blob、楼层刷怪器）仍跟随当前活动区域；跨区域的远程攻击、AI 追踪等
   以目标在实际活动范围内才会被结算，超出范围的交互不作特殊补偿。

---

## 5. 协议速查

```
帧：int32 长度 | uint8 类型 | payload(UTF-8 JSON, 内容是一个 Bundle)

C_HELLO 1      {ver, app, name, cls}
S_WELCOME 2    {pid, token, host, pvp, depth, branch, players}
S_REJECT 3     {reason}
S_SNAPSHOT 4   {seq, depth, branch, game, level, players[{pid,name,hero}], pvp}
C_COMMAND 5    {kind, cell|index+action+cls}
S_DELTA 6      {seq, depth, branch, chars, new, removed, map?, heaps?}
C_RESYNC 7
S_NOTICE 8     {text}
C_PING 9 / S_PONG 10
```
