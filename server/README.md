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

## 开发与验证

```powershell
# 单元 / 集成测试（临时库，无需外部依赖）
.\.venv\Scripts\python.exe -m pytest -q

# lint 与格式（CI 门禁）
.\.venv\Scripts\python.exe -m ruff check app tests
.\.venv\Scripts\python.exe -m ruff format --check app tests

# 端到端冒烟（需先启动 uvicorn）
.\.venv\Scripts\python.exe scripts\smoke.py
```

测试覆盖：共享向量（`../shared/testvectors`）、收敛/幂等、授权矩阵与 role-at-HLC、时钟拒绝、实体绑定、分页游标、成员生命周期、快照/恢复、备份脱敏、迁移幂等。

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
