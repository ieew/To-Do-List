# 交接提示词（可直接转发）

> 用法：整段复制发给接手方 AI。它没有我们这几轮对话的上下文，这份提示词就是它的任务书。

---

## 任务：实现 dsh 推送链路改造（按既有设计文档执行）

### 你接手的项目

To-Do-List —— 跨端待办清单系统：
- 服务端：FastAPI + aiosqlite（`server/`）
- Android App：Kotlin + Hilt + Room（`android/`）
- 输入法插件 dsh-kit：注入 Trime 的纯 Java dex（`ime-plugin/`）
- Windows 客户端：Tauri + Rust（`windows/`）

仓库 `https://git.ieew.cc:880/qianxue/To-Do-List.git`，分支 `main`，设计定稿在 `003df13`。

### 第一步：读文档（不要跳过）

完整读 `docs/dsh-push-reliability-design.md`（1261 行）。最低限度要读完：

| 章节 | 为什么必读 |
| --- | --- |
| §0.4 交接说明 | 你的工作约定、契约、命令、平台限制 |
| §2 设计原则 | 8 条原则，冲突时按它裁决 |
| §3.0 / §3.0.1 | 分级链路与信任模型，是全部设计的前提 |
| §12 阶段表 | 你的路线图 |
| 你要开工的那一阶段对应的章节 | 逐条实现依据（都带源码行号） |

**不要读完 §12 就动手**。设计契约写在各章里，跳过会被细节绊住。

### 你的任务

按 §12 阶段表，**从阶段 1 开始，一次只做一个阶段，不要并行**。
阶段之间有依赖（先落库 → 再降频 → 再上指标），跳步会产生不可验证的中间状态。

每完成一个阶段：跑该阶段验收（§11、§9.7）→ 更新对应文档 → 提交 → 报告。

### 阶段 1 的具体范围（§9 账号态数据安全）

这是数据丢失 bug，优先级最高，改动最小。四个 App 侧 + 一个桌面端：

1. **A18**（`SessionManager.kt:47-52`）：`ensureFresh()` 在 refresh 失败时返回 `null`，
   不要返回那张已过期的 cached token。`pushAll`（`SyncEngine.kt:158`）遇 401 不要无条件
   `sessionManager.clear()`，改为先强制 refresh 一次，仍失败才清，且只清 access token。
2. **A15**（`AccountManager.kt:96-128`）：`resetForAccount()` 与 `setSyncAccount()`
   合并进同一个 Room 事务，杜绝「清了库但没记 accountKey」导致的反复清库。
3. **A17**（`TasksViewModel.kt:138`）：登录过期改成常驻横幅 + 真实待推送条数
   （`pendingOps()` 计数），并修正「本地修改会稍后推送」这句在 A19 修好前不成立的文案。
4. **A19**（`SyncEngine.kt:186-198`）：`markRejected` 改为标记保留（记 `reject_code`），
   提供批量重试入口；只对确定不可恢复的错误自动丢弃。
5. **W1**（`windows/src-tauri/src/commands.rs:1386`）：桌面端同步做 A15/A16/A19。

### 硬约束（不得绕过）

1. 插件自持：主通道的关键路径（接收/落盘/展示/重试）不得依赖 App 进程
2. 送达与展示分离：ack 只表示「已落盘」，不表示「已展示」
3. 实时性优先于省资源：只优化空闲期的无效动作，不动有消息时的响应路径
4. 两条通道允许短暂双连，宁可重复不可空档
5. 撤销权威在服务端；App 撤销时**不得删除** `dsh_ime_device_id`
6. 插件只持设备凭证：废弃 `autoLogin`，`refresh_token` 不落盘
7. 不得合并 App 与插件的 `device_id`（投递隔离是可靠性基础）
8. 协议只增不改：新能力走新方法/新字段，旧端不认识就跳过
9. 发现某条契约无法实现，**先改文档、再改代码**，不要自行绕过
10. 前端/App 侧改动必须同步 `docs/ime-push-client.md`，接口改动同步 `docs/server-protocol.md`

明确否决过的方案（不要重新提出）：
- App 打包并释放插件 dex 到输入法目录（用户自行安装）
- 插件把消息写进 App 的 `dsh_inbox` 表（会绑定到最弱的一环）
- 插件定时上报心跳（provider 调用会拉起 App 进程，反向耗电）
- 降级通道只轮询不建长连接（牺牲实时性）

### 环境与命令

```bash
# 插件（本机可编译）
cd ime-plugin && bash tools/build.sh     # → out/dsh-kit.dex
cd ime-plugin && bash tools/test.sh      # JVM 测试，当前 69 项

# 服务端（venv 在仓库根 .venv）
cd server && ../.venv/bin/python -m pytest tests -q    # 当前 102 项
bash ime-plugin/tools/run-server.sh      # 本机联调：HTTP 8000 + MQTT 1883

# Android —— 必须在有完整 SDK 的机器上，手机环境编译不了
cd android && ./gradlew assembleRelease

# Windows
cd windows/src-tauri && cargo test
```

### 签名文件（构建前必须先解决）

`android/app/build.gradle.kts` 里 debug 与 release **共用同一个 keystore**（`signingConfigs.dsh`），
保证两者能互相覆盖安装。

**使用的证书**（`qianxue.jks`，JKS 格式）：

```
alias:   qianxue
SHA-256: 47:37:B2:F7:22:C4:81:75:46:3E:60:9A:A7:24:BA:3F:7F:BE:39:06:A1:90:E6:F9:B0:EC:00:3B:E8:AF:A3:4E
```

**keystore 文件不进仓库**（`android/.gitignore` 已忽略 `*.jks`）。构建方需要拿到文件本身，
或者用环境变量指向自己机器上的副本：

| 变量 | 作用 | 默认 |
| --- | --- | --- |
| `DSH_KEYSTORE` | keystore 绝对路径或相对项目根的路径 | `/storage/emulated/0/MT2/keystore/qianxue.jks` |
| `DSH_KEYSTORE_PASS` | store 密码 | 见 `build.gradle.kts` |
| `DSH_KEY_ALIAS` | 别名 | `qianxue` |
| `DSH_KEY_PASS` | key 密码 | 同 store 密码 |

也可以写在 `android/local.properties`（不进 git）：

```properties
dsh.keystore=/absolute/path/to/qianxue.jks
```

**换过签名意味着旧版本无法覆盖安装**：手机上已装的旧 APK（证书 `CN=Android Debug`，
SHA-256 `54583c8b...`）必须卸载后才能装新的，而卸载会清掉本地数据库。
卸载前务必把未同步的修改推完。

构建后必须验证：

```bash
apksigner verify --print-certs app-release.apk | grep SHA-256
# 期望：47:37:b2:f7:22:c4:81:75:46:3e:60:9a:a7:24:ba:3f:
#       7f:be:39:06:a1:90:e6:f9:b0:ec:00:3b:e8:af:a3:4e
```

### 已知的坑（都是审设计时踩出来的）

1. **提醒索引 UPSERT 必须带 `COALESCE`**：`WHERE excluded.remind_hlc > COALESCE(t.remind_hlc,'')`。
   漏了它，NULL 比较会让首次写入被静默吞掉 —— 索引永远为空、提醒永不触发，且不报错。
2. **Room 数据库不能降级**：`todo.db` 现为 v2，阶段 8 升到 v3 后，装回旧 APK 会直接崩溃。
3. **升级插件 dex 后必须强停 `com.nirenr.trime`**，否则进程里还是旧代码。
4. **服务端必须 `--workers 1`**：多进程会被 `ProcessLock` 拒绝，且 broker 内存态会分裂。
5. **Paho 的 `isAutomaticReconnect` 不能用**：固定票据重连必然认证失败且永不退出，
   必须改成自管重连（§5.3）。
6. **App 与插件的 `device_id` 是两个**（`dsh-app-*` / `dsh-ime-*`），服务端 ack 按
   `(device_id, seq)` 分别记录，别当成重复数据去重。

### 交付标准

每个阶段报告三件事：

1. **改了什么**：文件 + 行号 + 关键决策
2. **怎么验证的**：验收用例逐条结果（不接受「应该没问题」）
3. **指标对比**：涉及资源优化的阶段，给出改动前后的实测值

如果某个验收过不了，如实说卡在哪、试过什么，不要为了收工把断言放宽。
