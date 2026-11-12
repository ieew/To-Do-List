# 待办清单 · Android 客户端

Jetpack Compose + Room + Hilt + Retrofit + WorkManager 的离线优先客户端，与 `docs/安卓端设计文档.md`、`docs/android-ui-demo.html` 对齐。

## 构建与运行

```powershell
cd android
.\gradlew.bat :app:assembleDebug        # 产物 app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat test                      # 全部 JVM 单测（含 Robolectric、MockWebServer）
.\gradlew.bat :core:domain:test         # 三端一致性向量 + 性质测试
.\gradlew.bat :core:data:testDebugUnitTest
.\gradlew.bat :core:network:testDebugUnitTest
```

- 版本：AGP 9.5.1、Gradle 9.7.1、Kotlin 2.4.10（AGP 内置，**不要**应用 `kotlin-android`）、KSP 2.3.13、Hilt 2.60.1、Compose BOM 2026.09.00、Room 2.8.5。
- `minSdk 26` / `compileSdk & targetSdk 37`；JDK 17+。
- 仓库路径含中文，`gradle.properties` 里必须保留 `android.overridePathCheck=true`。

## 模块

```text
core/domain        纯 Kotlin：HLC、op、LWW/OR-Set、投影、时间线、范围分组
core/data          Room op_log（唯一事实来源）+ OpRepository + TaskCommands + 设置
core/network       wire DTO、Retrofit、错误映射、Keystore 令牌、刷新单飞
core/sync          SyncEngine（先 push 后 pull）、Worker、账户生命周期
core/designsystem  令牌/主题（浅色/深色/纯黑/系统）+ 共享组件
feature/tasks      S5 清单、左右滑快捷操作、工作区 BottomSheet、快速添加、搜索
feature/detail     S6 详情（编辑/新建）+ S7 历史撤销
feature/calendar   日历（周视图 + 下拉展开月视图）
feature/stats      统计（与列表共用同一范围视图）
feature/settings   设置（通用/外观/AI/提醒/数据，支持左右滑翻页）
feature/members    工作区成员（列表 / 邀请 / 改角色 / 移除）
core/reminder      提醒调度 + 补发补偿 + 后台保活（心跳/WorkManager/常驻服务）
app                导航、Tab、启动、通知渠道、WorkManager 配置
```

## 联调

```powershell
# 服务端
cd server
$env:TODO_SERVER_JWT_SECRET="<32 字节以上>"
.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1

# 模拟器：宿主机 8000 端口转发
adb reverse tcp:8000 tcp:8000
```

App 默认服务器地址 `http://127.0.0.1:8000`。首次启动会在本地创建「我的空间」（4 清单 5 标签，真实 op 写入），登录后按账户切换：不同账户会先整库备份再清空本地 op。

## 说明

- 所有写操作经 `OpRepository.appendOps`（单 Room 事务）→ 触发同步；UI 不直接发 HTTP。
- `op_log` 是唯一事实来源，没有业务表；删除只写 `__deleted` 墓碑（清单页删除后 3 秒内可撤销）。
- 深色主题含「纯黑」档（OLED 省电）：`#000000` 底 / `#0A0A0A` 卡。
- 字体：`Tokens.Type.taskTitleList`（16sp）只用于清单页任务标题，其余位置用 `taskTitle`（14sp）。
- 手势：任务行右滑=完成/取消，左滑=删除/优先级/标签面板；日历列表滑到顶继续下拉（>72dp）=展开月视图，上推收起，点月份标题也可切换。
- 成员管理走服务端 `v1/workspace/{members,invite,role,remove}`；写成功后立刻 `runOnce()` 把服务端生成的 membership op 拉回本地。
- 后台存活三层：15 分钟心跳闹钟（`HeartbeatReceiver`）+ 15 分钟 WorkManager 周期任务（`KeepAliveWorker`）+ 可选常驻前台服务（`KeepAliveService`，划掉最近任务后 2 秒重拉）。
  常驻通知使用独立渠道 `keepalive`（IMPORTANCE_MIN + 静音），可在设置页单跳渠道设置关掉它而不影响提醒。
  设置里「强行停止」应用后，广播/闹钟/Job 会被系统冻结，需手动重新打开应用才能恢复。
- 提醒补偿：`rescheduleAll` 对已过点但未发过的提醒，逾期 ≤60 分钟补发一条「补发提醒」，超过只静默记账（`last_fired_at` 幂等）。
- 应用图标：`tools/generate-icon.ps1` 生成各密度 PNG；自适应图标资源在 `res/drawable/ic_launcher_*.xml`，重新生成执行 `powershell -File tools/generate-icon.ps1`。
- 桌面卡片使用 RemoteViews（Glance 与当前 Compose 运行时编译不兼容），见实现说明。
- 与设计文档的差异与未完成项见 `docs/android-implementation-notes.md`。
