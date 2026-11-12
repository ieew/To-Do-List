# Android 实现说明与验收记录

> 对照 `docs/安卓端设计文档.md`（内嵌 4 份子规格）与 `docs/android-ui-demo.html`。本文件记录**已完成**、**偏差**与**未完成**，避免后续重复讨论。

## 一、已完成（P1）

### 领域层 `core/domain`（纯 JVM，Konsist 等价门禁）
- `Hlc`（`next` / `observe`，observe 与 Windows `storage.rs::observe_hlc` 同构）、`HlcClock`。
- `Op` 全序 `(physicalMs, counter, nodeId, opId)`；`MergeState` 字段 LWW + `__tag`/`__subtask` OR-Set（add-wins）+ 子任务字段 LWW。
- `Projection`：工作区/成员/清单/标签/任务，`tagIds` 不按标签删除过滤；`Timeline`（`__undo_of` 判 undone）。
- `RestorePlanner`：快照恢复意图（只恢复目标中存在的实体）。
- `TaskScoping`：今天=已开始、全部、清单、标签、搜索；分组只看开始时间，另加已完成。

### 数据层 `core/data`
- Room v1：`op_log`（18 列 + `op_id` 唯一索引 + 3 条复合索引）、`meta`、`preferences`、`local_prefs`、`task_local`、`local_hlc`（schema 入仓 `core/data/schemas`）。
- `OpRepository.appendOps` 单事务；重复 `op_id` 幂等；`load_ops` 过滤 rejected；pull 落库与 cursor 同事务。
- `TaskCommands`：任务/字段/标签/子任务/清单/工作区（含 4 清单 5 标签种子）/撤销。
- `SettingsStore`：单条 JSON，新增字段有默认值。

### 同步层 `core/sync` + `core/network`
- Retrofit `v1/auth|sync` 接口；自定义 kotlinx.serialization 转换器（无 Moshi）。
- `BaseUrl.normalize` + `BaseUrlInterceptor` 运行时改写服务器地址（接口路径写 `v1/...`，不会 `/v1/v1`）。
- `SessionManager`：60 秒预刷新、Mutex 单飞、refresh token 轮换即存；`TokenAuthenticator` 401 重放一次。
- 令牌：Keystore AES/GCM 加密后写 DataStore，不进 SQLite/日志/备份。
- `SyncEngine.runOnce()`：Mutex；先 push（批 100，413 减半）后 pull（页 200，单轮 ≤20 页，不带 workspace_id）；`server_hlc` → `observeHlc`；错误按 `code` 分支。
- `SyncNotifier`：前台 SharedFlow、后台 OneTimeWork(REPLACE)、周期 15 分钟；`@HiltWorker` + `HiltWorkerFactory`。

### UI
- 设计令牌与四档主题（浅色/深色/纯黑 OLED/跟随系统），减少动效开关。
- S5：工作区切换、问候语、进度卡、快速添加两条路径（`+` 打开新建详情；回车按 `开始=创建时刻 / 截止=+7 天 / 优先级中 / 当前清单` 创建）、今天/全部、搜索、空态、骨架屏、同步提示条。
- 工作区 BottomSheet：切换 / 新建（1–24）/ 重命名 / 删除（最后一个禁用并提示）/ 角色徽章 / 成员数。
- S6：编辑/新建双模式、优先级/开始/截止/提醒/清单/标签、子任务增删勾选、备注、历史入口、底部操作栏。
- S7：任务时间线（操作者/AI 徽章、diff 旧值删除线、op_id/model/source、撤销按钮）。
- 日历（按 dueAt 排布 + 逾期 banner + 只显示当前范围）、统计（环/数字卡/优先级与清单分布/7 日趋势，同一范围视图）。
- 设置：通用（账户/工作区/语言/启动）、外观（四档主题/减少动效）、同步（状态/cursor/待推送/立即同步/间隔）；登录/注册对话框。

## 二、与设计文档的偏差（有意为之，已评估）

| 项 | 文档 | 实现 | 原因 |
|---|---|---|---|
| 周期 Worker 网络约束 | `NetworkType.CONNECTED` | 不设约束 | 模拟器/内网常缺 `NET_CAPABILITY_VALIDATED`，Worker 会永不执行；离线由 SyncEngine 判 `offline/retrying` |
| 投影时间字段 | 与服务端 9.3 完全一致（字符串） | 额外提供 `startAtMs/dueAtMs/remindAtMs/createdAtMs/completedAtMs` | 领域层禁止 `java.time`，毫秒由数据层补齐，格式化在 UI 层 |
| 架构门禁 | Konsist | 等价的源码扫描 JUnit（`ArchitectureTest`） | 避免新依赖与 Kotlin 版本对齐风险，断言内容一致（C1/C2） |
| 日历/统计模块 | 文档模块树未列举 | 独立 `feature:calendar` / `feature:stats` | 单屏单模块，避免 `feature` 互相引用 |
| 搜索入口 | demo 无搜索栏，但要求 `search` 状态 | 顶栏加搜索图标 + 内联搜索行 | 满足空态文案与搜索能力 |
| 深色主题 | design-tokens 标「MVP 预留」 | 实现深色 + 纯黑 OLED 档 | 用户要求 OLED 省电配色 |

## 三、未完成（P2）

- AI 助手（`core/ai` + `feature/ai`）：API 直连、草案预览、≥10 条确认摘要、密钥管理。
- 提醒/通知：`AlarmScheduler`（精确闹钟降级）、`BootReceiver` 重建、pull 后补发、免打扰跨天。
- Glance 桌面卡片三尺寸。
- 设置「数据」：导出 zip、导入摘要 + `importOps`、清缓存。
- 成员管理页：目前只保留入口 Toast（文档本身无页面规格）。
- 代码风格门禁：`ktlint` 插件尚未接入（当前以编译 + 单测为准）；接入时补 `.editorconfig` 与 CI。

## 三（补充）、P2 实际完成情况（2026-11-11 追加）

- **AI 助手**：`core/ai`（OpenAI 兼容客户端、意图 schema、清单/标签解析与字段告警、`source=ai` 写入）+ `feature/ai`（API 直连 / 网页桥接占位、图片最多 5 张、草案预览、批量确认、写入）。设置「AI」分页可配置端点/模型/阈值/密钥（Keystore 加密）。
- **提醒/通知**：`core/reminder`（精确闹钟可用性检查 + `setWindow` 降级、唯一 data URI 的 PendingIntent、开机重建、免打扰跨天静默仍记 `last_fired_at`）；启动、同步成功、设置变更都会重建提醒；API 33+ 启动时申请通知权限。
- **桌面卡片**：`feature/widget` 已实现三种尺寸自适应（2×2 / 4×2 / 4×4 同一 Provider）、今天未完成进度、勾选走 op、点任务深链详情、点添加打开 App、数据变更后自动刷新。
  - **偏差**：Glance 1.2/1.3-alpha 与当前 Compose 运行时在编译期不兼容（`CompositionLocal.getCurrent` 内联方法缺失），改用 **RemoteViews** 实现，功能等价。
- **备份/导入**：`core/data/backup/BackupService` 导出 `manifest.json + workspace/ops.jsonl + projection.json`，导入走 SAF 摘要对话框 → `importOps`（重复 op_id 跳过、不覆盖本地、不删除数据）；设置「数据」分页已接入，含数据库大小与清缓存。
- **图标**：`android/tools/generate-icon.ps1` 生成各密度 PNG；自适应图标为渐变底 + 白色对勾 + 提醒点（`drawable/ic_launcher_foreground.xml`）。

## 四、2026-11-12 交互与设置迭代

### 字号与任务行
- `Tokens.Type` 除清单页标题外整体 +1sp；新增 `taskTitleList`（16/24）只用于清单页；`TaskRow` 增加 `titleStyle` 参数，日历/统计/详情仍 14sp。标题保持 `maxLines = 2`。
- `TaskRow` 内容改为垂直居中（原顶对齐），上下内边距 4→10dp、卡高 `heightIn(min = 58.dp)`、复选框 22dp。

### 左右滑快捷操作
- `core/designsystem/SwipeTaskRow`（`SwipeToDismissBox`，阈值 0.32，两向均回弹）：右滑=切换完成状态，左滑=呼出快捷操作面板。
- `feature/tasks/TaskQuickActionSheet`：删除任务 / 优先级 低中高 / 标签多选。
- 删除写 `__deleted=true` 墓碑，Toast 提供「撤销」（**仅 3 秒可点**，写回 `__deleted=false`）。

### 日历月视图
- `feature/calendar/MonthGrid`：6×7 周一起始的月网格（纯函数，时间与时区由调用方注入；放 UI 层是因为 `core/domain` 架构门禁禁止 `java.time`）。附 `MonthGridTest` 3 项。
- 手势：使用 `nestedScroll` 累积列表溢出滚动，在顶部继续下拉 >72dp 展开、展开状态下上推 >72dp 收起；点月份标题同样可切换。展开时左右箭头改为按月翻页，点日格选中并收回周视图。

### 后台保活（三层 + 提醒缺陷修复）
- L1 `KeepAliveManager.scheduleHeartbeat()`：15 分钟链式 `setAndAllowWhileIdle`，`HeartbeatReceiver` 醒来重建提醒、记账并续订；关闭开关后自我停止。
- L1b `KeepAliveWorker`：15 分钟 Periodic（无网络约束），进程被杀/重启后由系统恢复调度。
- L2 `KeepAliveService`：`service` 模式启用，`START_STICKY`，`onTaskRemoved` 后 **2 秒**用精确闹钟经 `KeepAliveRestartReceiver` 重拉（吞掉 Android 12+ 后台启动限制异常）。
- 常驻通知走独立渠道 `keepalive`（IMPORTANCE_MIN + 静音 + ongoing），设置页提供跳转渠道设置，可单独关闭该渠道隐藏通知、服务继续运行。
- L3 引导：电池优化白名单（申请 → 列表 → 应用详情三级回退）与厂商自启页（小米/华为/荣耀/OPPO/vivo/三星/魅族/一加，探测失败回退应用详情）。
- 自愈：`BootReceiver` 增加 `MY_PACKAGE_REPLACED` / `TIME_SET` / `TIMEZONE_CHANGED`。
- 可观测：`local_prefs` 记录 `keepalive_last_beat` / `keepalive_beat_count`，设置页显示上次心跳与累计次数。
- 设置项 `keepAliveMode`：`off` / `heartbeat`（默认）/ `service`。
- **修 `rescheduleAll` 漏提醒**：原实现对 `remindAt <= now` 直接跳过，进程死亡、Doze 延迟、改系统时间、开机重建时已过点的提醒会永久丢失。现抽出 `ReminderNotifier` 统一发通知；逾期 ≤60 分钟补发「补发提醒 · 已逾期 X」，超窗只静默记账；免打扰/通知关闭时同样静默记账；幂等靠 `last_fired_at`。
- **修 `cancelAll`**：原用 `pendingIntent(taskId, 0L)` 与已排的 `todo://reminder/$taskId/$remindAt` 不相等，实际取消不掉；改为按任务真实 `remindAtMs` 取消。
- 边界：系统设置里「强行停止」应用后广播/闹钟/Job 全被冻结，只能下次手动打开恢复（设置页以普通说明文字提示，无红色警示）。

### 设置页结构
- 取消「同步」Tab，5 个分页：通用 / 外观 / AI / 提醒 / 数据；`HorizontalPager` 支持左右滑动，点击标签与滑动双向同步。
- 原同步分页的状态行、立即同步、同步间隔并入通用页；**删除同步页的「登录 / 注册」行**，只保留通用页「我的账号」唯一登录入口。
- 提醒页新增「后台存活」分组：保活方式、电池白名单、自启动权限、常驻通知设置、心跳记录。

### 成员管理
- `core/network`：`MemberApi`（`GET v1/workspace/members`、`POST invite`、`PATCH role`、`POST remove`）+ wire DTO；`MemberApiTest` 覆盖路径/蛇形字段/204/403。
- `core/sync/MembersManager`：写成功后立刻 `engine.runOnce()`，把服务端生成的 membership/user op 拉回本地投影再刷新。
- 新模块 `feature:members`：成员列表（首字母头像 + 账号 + 角色徽章）、邀请对话框、角色修改与移除（仅 owner，自己不可移除）、未登录空态。
- 导航：`Screen.Members(workspaceId)`；入口为设置 → 通用 →「工作区与成员」，以及工作区抽屉的「管理成员」（原 Toast 占位已移除）。

### 仍未完成
- ktlint/format 门禁未接入 CI。
- `SwipeToDismissBox` 的 `confirmValueChange` 在新版 Compose 被标记弃用（仅警告，行为正确）。

### 真机/模拟器冒烟中发现并修复的两个问题（2026-11-12）
1. **APK 能编过但一启动就崩**：`Cannot create an instance of class com.todo.android.feature.tasks.TasksViewModel`
   （`Caused by: NoSuchMethodException: TasksViewModel.<init> []`）。
   根因是 `:feature:tasks:clean` 之后 KSP 任务仍被判为 UP-TO-DATE，`build/generated/ksp` 为空，
   Hilt 生成的 `TasksViewModel_HiltModules` / `_Factory` 丢失，运行时退化成反射无参构造。
   修复：`.\gradlew.bat :feature:tasks:kspDebugKotlin --rerun-tasks --no-build-cache` 后重新打包；
   交付前自检 `Test-Path feature\tasks\build\generated\ksp\debug\java` 应为 True。
2. **日历下拉展不开月视图**：原先用 `onPostScroll` 判溢出，且当日没有任务时列表被 `EmptyState` 替换，
   整个子树没有可滚动节点，嵌套滚动事件根本不会派发。改为：空态也放进 `LazyColumn` 里（列表始终存在），
   并在 `onPreScroll` 里按 `listState.canScrollBackward` 判断到顶后累计位移、直接消费掉（避免被 overscroll 拉伸吞掉）。
   展开态标题不再重复显示年份。

### 厂商 ROM（小米实测闪退）加固
- `TodoApplication.onCreate` 只保留"建通知渠道"这一件事（且 `runCatching` 包裹）；
  同步排程与后台保活改到首帧之后由 `AppViewModel.bootstrap()` 触发，避免启动期就触碰 WorkManager / AlarmManager。
- `BootReceiver` / `ReminderScheduler` 补发提醒 / `KeepAliveManager` 的 WorkManager 与通知操作全部 `runCatching`，
  厂商限制只降级功能，不带崩进程。
- 新增 `CrashLogger`：`attachBaseContext` 里安装 `Thread.setDefaultUncaughtExceptionHandler`，
  堆栈写入 `files/last_crash.txt`；下次启动时 `TodoApp` 弹出「上次运行遇到问题」对话框，
  可滚动查看并「复制」，便于在无法连接 adb 的手机上反馈闪退原因。

## 五、验证记录

- `android> .\gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- `android> .\gradlew.bat test` → **BUILD SUCCESSFUL**（领域层含架构门禁、Room Robolectric、网络层含新增 `MemberApiTest` 4 项、日历 `MonthGridTest` 3 项）。
- 测试向量：`shared/testvectors` 下 5 个 JSON 由 Gradle `Copy` 同步并做 SHA-256 一致性校验（禁止手抄）。
- 按用户要求，保活与手势均在真机手动验证，本轮**不启用模拟器**、不做截图对比。

### 模拟器闪退冒烟（2026-11-12，用户授权后执行）
设备：`Medium_Phone_API_37.0`（1080×2400）。手法：`adb install -r` + `am start` + 每步后 `adb logcat -b crash -d`，
并用 `uiautomator dump` 取节点坐标点击（脚本 `android/tools/smoke-ui.ps1`）。

| 场景 | 结果 |
|---|---|
| 冷启动首屏（清单） | 正常，无崩溃 |
| 底部四 Tab：日历 / 统计 / 我的 | 正常，无崩溃 |
| 输入标题回车快速创建 | 任务落库，进度 0/1，无崩溃 |
| 右滑任务行 | 切换完成，进度 1/1，无崩溃 |
| 左滑任务行 | 弹出面板（优先级 / 标签 / 删除），无崩溃 |
| 面板改优先级 | 写 op 成功，无崩溃 |
| 删除 + 撤销 | Toast 显示「撤销」并成功恢复任务，无崩溃 |
| 日历下拉展开月视图 / 上推收起 / 点月份标题 | 修复后均正常，无崩溃 |
| 设置 5 个分页左右滑动 | 正常翻页并同步高亮 |
| 工作区与成员 | 未登录空态正常，无崩溃 |
| 电池优化白名单 / 自启动入口 | 分别跳到系统申请页与应用详情，无崩溃 |
| 保活切「常驻」 | `KeepAliveService` 前台运行，通知渠道 `keepalive`（IMPORTANCE_MIN、静音、ONGOING） |
| 心跳调度 | `dumpsys alarm` 可见 `HeartbeatReceiver` 的 RTC_WAKEUP（约 15 分钟后） |
| 任务详情页 | 正常渲染 |

未逐项点击验证：历史时间线（坐标定位不稳）、外观页四档主题切换（分段控件坐标未命中）。
`logcat -b crash` 全程为空，`AndroidRuntime:E` 无 `todo` 记录。
