# 待办清单同步服务端

Python 3.12+ / FastAPI / aiosqlite（SQLite WAL）实现的 op-log 同步服务：

- 业务状态由**只追加的 op_log** 确定性投影得出，合并原语只有字段级 LWW 与集合 OR-Set；
- 只有 Writer Worker 持有可写连接，读接口走 `query_only` 连接池；
- 服务端是唯一授权入口（按 op 的 HLC 时刻成员角色裁决）；
- 支持账号、增量推拉、成员管理、历史时间线、快照、恢复到快照、工作区备份导出。

协议见 [`../docs/server-protocol.md`](../docs/server-protocol.md)，设计见 [`../docs/server-design.md`](../docs/server-design.md)。

## 快速开始

```powershell
cd server
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"

$env:TODO_SERVER_JWT_SECRET = "<至少 32 字节随机串>"
uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

> **必须单进程**：Writer Worker 依赖进程内唯一可写连接；启动时会抢 `<db>.lock` 文件锁，多实例会拒绝启动。

健康检查：`GET http://127.0.0.1:8000/v1/health`。

## 配置

全部配置项见 [`.env.example`](./.env.example)，环境变量前缀 `TODO_SERVER_`。必填项只有 `TODO_SERVER_JWT_SECRET`（≥32 字节）。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `TODO_SERVER_DB_PATH` | `./data/server.sqlite3` | SQLite 文件，目录自动创建 |
| `TODO_SERVER_JWT_SECRET` | 无 | HS256 密钥，启动时校验长度 |
| `TODO_SERVER_ADMIN_TOKEN` | 空 | `/v1/admin/backup` 令牌；空 = 关闭接口 |
| `TODO_SERVER_ACCESS_TTL_MINUTES` | 30 | 访问令牌有效期 |
| `TODO_SERVER_REFRESH_TTL_DAYS` | 30 | 刷新令牌有效期（每次刷新轮换） |
| `TODO_SERVER_MAX_OPS_PER_PUSH` | 500 | 单次 push 上限 |
| `TODO_SERVER_HLC_MAX_FUTURE_MS` | 300000 | 时钟超前阈值 |
| `TODO_SERVER_SNAPSHOT_EVERY_OPS` | 500 | 自动快照间隔；0 关闭 |
| `TODO_SERVER_RESTORE_MAX_OPS` | 5000 | 单次恢复生成 op 上限 |
| `TODO_SERVER_AUTH_RATE_LIMIT` | 10 | 鉴权接口每分钟每 IP 上限；0 关闭 |
| `TODO_SERVER_DSH_MQTT_ENABLED` | true | dsh 推送通道（内嵌 MQTT broker）总开关 |
| `TODO_SERVER_DSH_MQTT_BIND` / `_PORT` | `0.0.0.0` / `1883` | broker 监听地址；`0` 表示随机端口（测试用） |
| `TODO_SERVER_DSH_MQTT_PUBLIC_HOST` | 空 | 返回给客户端的 broker 地址；留空时用 bind 值，通配地址回退 `127.0.0.1` |
| `TODO_SERVER_DSH_MQTT_TLS_CERT` / `_KEY` | 空 | 都填才启用 TLS（8883 常用） |
| `TODO_SERVER_DSH_TICKET_TTL_MIN` | 10 | 设备 MQTT 票据有效期（分钟） |
| `TODO_SERVER_DSH_MSG_TTL_HOURS` | 72 | 推送消息默认过期时间 |
| `TODO_SERVER_DSH_MSG_MAX_PENDING` | 1000 | 单设备待投上限，超出淘汰最旧 |
| `TODO_SERVER_DSH_PUSH_RATE` | 30 | 每分钟每用户推送上限；0 关闭 |
| `TODO_SERVER_DSH_CLIP_RATE` | 60 | 每分钟每用户剪贴板上行上限；0 关闭 |
| `TODO_SERVER_DSH_CLIP_PERSIST` | false | 剪贴板类消息是否落库（默认不落库，只直传） |
| `TODO_SERVER_DSH_REMINDERS_ENABLED` | true | 待办到点提醒是否推送到设备 |
| `TODO_SERVER_DSH_REMINDER_LEVEL` | 6 | 提醒用的通知等级 0–7 |
| `TODO_SERVER_DSH_REMINDER_LEAD_S` | 0 | 提前多少秒提醒 |
| `TODO_SERVER_DSH_REMINDER_WINDOW_S` | 3600 | 错过多久内补发（超出不提醒） |
| `TODO_SERVER_DSH_REMINDER_INTERVAL_S` | 30 | 扫描提醒的间隔 |

## 接口速查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/health` | 健康检查（含 `db_seq`、`server_hlc`） |
| POST | `/v1/auth/register` | 注册；同事务写入默认工作区 + 4 清单 + 5 标签 |
| POST | `/v1/auth/login` / `refresh` / `logout` | 令牌签发、轮换、吊销 |
| GET | `/v1/auth/me`、`/v1/workspaces` | 当前用户与工作区列表 |
| POST | `/v1/sync/push` | 批量推送；逐 op 返回 `accepted`/`duplicate`/`rejected` |
| GET | `/v1/sync/pull` | 按 `workspace_id` + `since_seq` 增量拉取 |
| GET | `/v1/workspace/members` | 成员列表（任意成员可读） |
| POST/PATCH | `/v1/workspace/invite`、`/role`、`/remove` | 仅 owner |
| GET | `/v1/history/timeline` | 历史时间线 |
| POST/GET | `/v1/history/snapshot` | 生成 / 读取快照 |
| POST | `/v1/history/restore` | 恢复到快照（只追加反向 op） |
| GET | `/v1/backup/export` | 工作区 zip 导出（排除密钥） |
| POST | `/v1/admin/backup` | 全库快照（`X-Admin-Token`） |
| POST | `/v1/dsh/devices/register` | 注册/复用推送设备，返回 `device_id` + 短期 MQTT 票据 |
| GET | `/v1/dsh/devices` | 设备列表（含 `online`、`revoked`、`last_seen_at`） |
| POST | `/v1/dsh/devices/{id}/revoke` | 吊销设备并踢掉在线连接 |
| POST | `/v1/dsh/push` | 投递一条消息，写库并发 nudge；`level` 0–7 只决定客户端提醒方式 |
| POST | `/v1/dsh/clip` | 剪贴板上行：默认不落库，只实时转发给同用户其他设备 |
| GET | `/v1/dsh/pull` | 该设备未确认的消息（`since` + `limit` 分页） |
| POST | `/v1/dsh/ack` | 逐设备确认，幂等 |
| GET | `/v1/dsh/state` | broker 连接数、待投条数、发布队列深度 |
| GET | `/dsh/` | **网页对端**：发通知（0–7 级）/发剪贴板/收消息/看设备状态 |

## 开发与验证

```powershell
# 单元 / 集成测试（临时库，无需外部依赖）
.\.venv\Scripts\python.exe -m pytest -q

# lint 与格式（CI 门禁）
.\.venv\Scripts\python.exe -m ruff check app tests
.\.venv\Scripts\python.exe -m ruff format --check app tests

# 端到端冒烟（需先启动 uvicorn）
.\.venv\Scripts\python.exe scripts\smoke.py

# dsh 推送通道冒烟：注册设备 → MQTT 收 nudge → pull → ack
.\.venv\Scripts\python.exe scripts\dsh_smoke.py --username alice --password password123 --register
.\.venv\Scripts\python.exe scripts\dsh_smoke.py --no-mqtt   # 只验纯 HTTP 通道
```

### 服务端提醒

待办任务的 `remind_at` 到点后，服务端主动给该工作区成员推一条通知（等级默认 6）。
账号体系完全复用待办的 `users` / `memberships`：没有 dsh 专用账号，设备也必须先在该账号下注册。

- 只读 `op_log` 里任务的 `remind_at`/`status`/`__deleted`/`title` 最新值，不维护第二份任务表
- `(workspace_id, task_id, remind_at, user_id)` 只推一次；改了 `remind_at` 会再提醒
- 默认 30 秒扫一次；错过超过 1 小时不补发，避免开机轰炸

### 网页对端

服务端自带一个单页对端：`http://<host>:8000/dsh/`（登录用待办账号）。

- 「发送」：通知标题/正文 + 0–7 级滑杆，或直接发一段剪贴板；目标可选「全部设备」或某台设备
- 「收件箱」：页面会注册一个 `platform=web` 的「网页」设备，每 5 秒 `pull` 一次并 `ack`，
  于是它自己也能收到推给它的通知/剪贴板（调试时很方便）
- 「设备」：在线/吊销/最近活跃；「状态」：broker 指标与待投条数

页面是同源静态文件（`server/app/features/dsh/web/dsh.html`），不需要额外起服务，也不需要配 CORS。

## dsh 推送通道

给手机输入法里的 dsh-kit 客户端用的旁路通道：通知走「MQTT nudge + HTTP pull + ack」，
剪贴板默认不落库、只做 MQTT 直传。与 `op_log` / 同步协议**完全隔离**，不参与投影。

- 设计：[`../dsh-ime/doc/SERVER-INTEGRATION.md`](../dsh-ime/doc/SERVER-INTEGRATION.md)
- 协议：本文件「dsh 推送通道」一节 + [`../docs/server-protocol.md`](../docs/server-protocol.md) 第十五节
- broker 内嵌在 FastAPI 进程里（amqtt，纯 Python），随 `lifespan` 起停；启动失败只降级为纯 HTTP，不影响待办主服务
- 鉴权：`username = device_id`，`password = 票据`（HMAC，TTL 默认 10 分钟）；只允许订/发自己那条 `dsh/v1/<user>/dev/<device>/in|out`
- 可靠性：数据库是唯一真相源，nudge 只带 `{t,seq,n}`，丢了也不影响正确性（连接后主动 pull）
- 退出/吊销：`revoked` 立即生效（内存缓存 + 踢线），票据到期后无法重连

测试覆盖：共享向量（`../shared/testvectors`）、收敛/幂等、授权矩阵与 role-at-HLC、时钟拒绝、实体绑定、分页游标、成员生命周期、快照/恢复、备份脱敏、迁移幂等。

## Linux systemd 部署

`deploy/todo-server.service.example` 是 systemd unit 模板。以下路径、用户和组均为示例，请按实际部署位置调整；应用目录应包含代码和 `.venv`。

```sh
sudo useradd --system --home /opt/todo-server --shell /usr/sbin/nologin todo-server
sudo install -d -o todo-server -g todo-server /opt/todo-server/server/data
sudo install -d -o root -g todo-server -m 0750 /etc/todo-server
sudo install -o root -g todo-server -m 0640 /dev/null /etc/todo-server/todo-server.env
```

将 `TODO_SERVER_JWT_SECRET`（至少 32 字节随机串）等服务配置写入 `/etc/todo-server/todo-server.env`，每行使用 `KEY=value` 格式。在 `server` 目录下将模板安装为 unit 文件：

```sh
sudo install -o root -g root -m 0644 deploy/todo-server.service.example /etc/systemd/system/todo-server.service
```

确认 unit 中的路径、用户和组正确后，执行：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now todo-server
sudo systemctl status todo-server
sudo journalctl -u todo-server -f
```

更新代码或配置后用 `sudo systemctl restart todo-server` 重启。该 unit 使用单个 Uvicorn worker；服务默认只监听 `127.0.0.1`，公网访问应通过启用 TLS 的反向代理。

dsh 推送通道的 MQTT 端口（默认 1883）**不能走 HTTP 反向代理**：要么自己放行、要么配
`TODO_SERVER_DSH_MQTT_TLS_CERT`/`_KEY` 用 TLS（常见 8883）。同时把
`TODO_SERVER_DSH_MQTT_PUBLIC_HOST` 设成手机能访问到的地址，否则客户端拿不到正确的 broker 地址。

## 设计要点

- **op 信封**：wire 为 snake_case + 嵌套 `hlc`，客户端同步层负责与 Windows `Op` 的 camelCase / 扁平 HLC 互转。
- **幂等**：`op_id` 全局唯一；重复投递返回 `duplicate` 与首次分配的 `seq`。
- **授权**：绑定表 `entities` 固定实体归属；`role-at-HLC` 按成员 op 的时间线判定；`workspace.__created` / 首个 owner 成员关系 / 工作区名属于创建引导特例。
- **快照**：每工作区累计 500 条新 op 自动生成（可配置）；`restore` 基于快照与当前投影差异追加反向 op，不删除快照之后新建的实体，不触碰成员/工作区/用户资料。
- **备份**：工作区导出为 zip（manifest + ops.jsonl + projection.json），排除口令哈希、刷新令牌与 JWT 密钥；admin 备份走 SQLite backup API。

## 运维注意事项

- 数据库迁移在启动时自动执行，迁移单向；回滚请使用备份。
- `data/` 与 `.env` 不应提交到版本库（见 `.gitignore`）。
- 公网部署请置于反向代理之后并启用 TLS；服务默认只监听 `127.0.0.1`。
- 单工作区建议 10 万条 op 以内；更大规模先缩短快照间隔或归档旧快照。
