# 工程规范（唯一权威来源）

> 本文件是所有实现工作的唯一权威规范。实现过程中若想偏离任何一条（例如认为某条 lint 规则过严），必须先提出并等待确认，不得自行放宽。

## 一、分层约束

三端统一采用 **core-domain 横向纯核 + 业务功能纵向切片** 的混合结构。

- `core-domain`（Rust crate / Kotlin module / Python package）只包含：
  - HLC
  - op 模型
  - 字段级 LWW 合并
  - 投影 reducer
- `core-domain` 不得 import 数据库、网络、UI 库，只接受和返回纯数据结构。
- 业务功能按纵向切片组织：每个功能（`sync-push`、`sync-pull`、`history-restore`、`task-update` 等）自成一个目录，内含 `handler` / `port` / `usecase`。
- 禁止出现一个 `services/` 或 `utils/` 装所有业务。
- 依赖方向：`UI/API → 同步循环/命令队列 → core-domain → 存储访问层`。反向依赖禁止。
- UI 层禁止直接访问数据库和网络，只通过领域层暴露的接口。
- 服务端只有 Writer Worker 持有可写数据库连接，API handler 只能投递命令。

## 二、错误处理

- **Rust**：领域层用 `thiserror` 定义枚举错误，应用层用 `anyhow` 包裹上下文。非测试代码禁止 `unwrap` / `expect`。跨 IPC/FFI 边界的错误必须可序列化。
- **Kotlin**：领域错误用 `sealed class`，挂起函数返回 `Result` 或自定义 `Either`。禁止裸 `try-catch` 吞异常；网络/数据库异常必须映射为领域错误。
- **Python**：领域层定义异常层次，FastAPI 用 `exception handler` 统一映射为 JSON 错误响应。禁止裸 `except`。
- **三端统一**：错误必须携带英文 `code` 和中文 `message`；客户端可重试错误（网络、429、5xx）与不可重试错误（4xx 业务错误）必须在类型上区分。

## 三、测试要求

- **core-domain 三端一致性**：三端各自读取 `shared/testvectors/` 的同一组 op 序列，断言投影结果相同。
- 另加 property-based 测试覆盖：
  - 收敛性：任意排列结果相同
  - 幂等性：重复投递结果相同
  - 平局裁决确定性：交换两端位置结果不变
- **客户端**：领域层单测覆盖率 > 80%；同步循环用内存 fake 存储做集成测试，不依赖真实网络。
- **服务端**：Writer Worker 每个命令处理器单测；幂等性测试（重复 `op_id` 不产生重复数据）；迁移 runner 必须测试「从任意旧版本依次升级到最新」。
- **端到端**：至少覆盖双端离线并发冲突、历史恢复、墓碑与字段编辑并发、快照兜底触发。

## 四、CI 门禁（全部强制，不通过不允许合并）

1. `core-domain` 不得 import 数据库/网络/UI 库
2. `core-domain` 中不得出现 `Date.now`、`new Date`、`System.currentTimeMillis`、`time.time`
   - HLC 的物理分量只能来自传入的 HLC 结构
3. 三端读同一 `shared/testvectors/` 并断言相同结果
4. 服务端 `api/` 层不得获取可写数据库连接
5. 每个 op 信封的 schema 校验（拒绝未知字段或畸形载荷）
6. 迁移 runner 任意旧版本升级测试
7. 三端 `lint` + `format` 统一配置，CI 中跑同一套规则

## 五、命名与注释语言

- 标识符（变量、函数、类型、文件、目录）：英文
- 注释、文档、用户可见错误消息：中文
- 注释重点解释业务意图和边界（例如「此处禁止读取物理时钟，裁决只依赖逻辑时钟」），不解释代码字面意思
- 提交信息：Conventional Commits，`type` / `scope` 用英文，描述用中文（如 `feat(sync): 增加字段级 LWW 合并`）
- 数据库表名/列名：英文 `snake_case`；API 路径和 JSON 字段：英文 `snake_case`
