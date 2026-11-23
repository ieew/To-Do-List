# 中文输入法插件（dsh-kit）

跑在手机输入法 **中文输入法2（`com.nirenr.trime`）** 进程里的扩展：Lua 薄壳 + 单个 dex，
提供通知推送（0–7 级）与剪贴板双向同步。**零安装**——不装 APK、不改主题、不绑键，
从输入法「工具」里点 `dsh` 即可。

设计与约束的唯一权威是 [`../docs/ime-push-client.md`](../docs/ime-push-client.md)；
协议见 [`../docs/server-protocol.md`](../docs/server-protocol.md) 第十五节；服务端实现在 `../server/app/features/dsh/`。

## 目录

```
ime-plugin/
├── src/java/com/dshkit/          dex 源码
│   ├── Boot.java Kit.java KitLog.java TrimeHost.java Ui.java
│   ├── core/                     L2+L3，零 Android 依赖（可纯 JVM 单测）
│   └── mod/                      L4：通知、剪贴板、网络监听、待办面板（Todo*）
├── src/java/org/eclipse/paho/    为 dex 化补的两个 Paho 依赖点（ResourceBundle / NetworkModule）
├── src/lua/
│   ├── dsh.lua                   键位入口（可选）
│   ├── dsh_todo.lua              待办面板入口（候选栏按钮 / 常驻通知）
│   └── tool_dsh.lua              工具入口（部署时改名 tools/dsh/main.lua）
├── tools/
│   ├── build.sh                  javac → 补 Paho jar → d8 → out/dsh-kit.dex
│   ├── test.sh                   纯 JVM 单测（69 项）
│   ├── e2e.sh                    起临时服务端 + broker，跑完整链路
│   ├── run-server.sh             手机本机起服务端给输入法联调
│   ├── dsh_cli.py                对端 CLI（登录/发通知/剪贴板/收消息）
│   ├── jvmtest/                  CoreTest / E2E
│   └── lib/                      Paho 1.2.5（已入库，构建不再下载）
└── build/ out/                   构建产物（git 忽略）
```

## 构建

依赖：

| 依赖 | 说明 |
| --- | --- |
| JDK 8+ | `javac` / `java` |
| `android.jar` | 默认 `/usr/lib/android-sdk/platforms/android-23/android.jar`，用 `AJAR=` 覆盖 |
| `d8` | 默认读 `R8=/tmp/r8.jar`；也可指向 Android SDK build-tools 里的 r8.jar |
| Paho | 已在 `tools/lib/`，无需下载 |

```bash
cd ime-plugin
bash tools/build.sh          # 产物 out/dsh-kit.dex（约 280 KB）
```

构建会：javac 编译 `src/java` → 复制一份 Paho jar 并删掉其 `NetworkModuleService.class`
（`META-INF/services` 在 dex 里不生效，改用我们自己的实现）→ d8 合并成单 dex。

## 测试

```bash
bash tools/test.sh    # 纯 JVM，69 项，不联网
bash tools/e2e.sh     # 需要仓库根的 .venv（服务端依赖），起临时服务端 + broker
```

`e2e.sh` 会自动起临时实例（HTTP 8099 / MQTT 18899）、跑「登录→注册→push→pull→ack→重连→剪贴板不回流」，
结束自动清理；`SERVER=` / `PY=` 可覆盖服务端目录与解释器。

## 部署（真机）

```bash
# 1) dex
cp ime-plugin/out/dsh-kit.dex /sdcard/Documents/rime/scripts/dsh-kit.dex
# 2) 键位入口（可选）
cp ime-plugin/src/lua/dsh.lua        /sdcard/Documents/rime/scripts/dsh.lua
# 2.5) 待办面板入口（候选栏按钮 / 常驻通知）
cp ime-plugin/src/lua/dsh_todo.lua   /sdcard/Documents/rime/scripts/dsh_todo.lua
# 3) 工具入口：必须是 tools/<名字>/main.lua 的目录结构
mkdir -p /sdcard/Documents/rime/tools/dsh
cp ime-plugin/src/lua/tool_dsh.lua   /sdcard/Documents/rime/tools/dsh/main.lua
# 4) 配置
mkdir -p /sdcard/Documents/rime/scripts/dsh.d
vi /sdcard/Documents/rime/scripts/dsh.d/config.json
```

`config.json` 最小可用：

```json
{"server": "http://127.0.0.1:8000", "username": "alice", "password": "password123", "default_level": 3}
```

- `server` 要能被手机访问；服务端 MQTT 端口（默认 1883）也必须可达，
  且服务端 `TODO_SERVER_DSH_MQTT_PUBLIC_HOST` 要写手机能访问到的地址。
- 登录成功后会拿 refresh_token 存到私有目录，之后可以从 `config.json` 删掉 `password`。
- 完整键表见设计文档第十三节。

**升级 dex 后强停一次输入法**：工具页每次打开都会新建 DexClassLoader，旧会话还持有旧代码，
不重启会出现「新 dex 旧行为」的错觉。

## 启动与自检

1. 输入法 → 工具 → `dsh`，出现面板即成功；
2. 「测试通知」按 0/1/3/6/7 各弹一条，逐档验证悬浮/震动/声音；
3. 「重连」后状态变「已连接」；「复制日志」把日志送到剪贴板；
4. 收不到消息时用 CLI 当对端排查：`python3 tools/dsh_cli.py notify --title 测试`；
5. 待办面板：工具页顶部的「启用待办面板 / 常驻通知」两个开关；
   悬浮窗权限未授予时点「悬浮窗权限」行去系统设置；候选栏按钮的配置用「复制候选栏配置」拿
   （需要 App 端 `feature/ime` 已编译安装，契约见设计文档第十七节）。

## 与服务端的关系

- 一个通道两种能力：MQTT 只发 nudge 唤醒，设备 `pull` 权威数据再 `ack`；数据库是唯一真相源。
- 账号复用待办：设备注册需要待办 JWT，`dsh_devices` 只是旁路表，不产生新账号。
- 剪贴板默认不落库（`clip_persist=false`），对端离线就丢；要可靠由服务端开关决定。
- 客户端不读写 `op_log`，不参与投影。

## 维护提醒

- 这个 LuaJ 开了 `LUA_JAVA_OO`：Java 方法用**点号**调用（`act.getFilesDir()`）。
- Android 14+ 禁止加载可写 dex：私有副本必须 `setReadOnly()`。
- L0 出任何异常都不能抛给宿主，只弹对话框 + 写 `/sdcard/Documents/rime/logs/dsh.log`。
- 改 L1（`TrimeHost`）以外的层时不要引入 `com.osfans.trime` / `com.androlua` 依赖。
- 剪贴板上行必须用**设备票据**（`HttpApi.clipAsDevice`）：服务端据此把本机当来源、不回显；
  用用户 token 时**不要**带自己的 `device_id`，那会被服务端当成「只发给这一台」。
