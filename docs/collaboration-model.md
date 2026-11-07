# 协作权限模型规格

## 一、实体

- `workspace`：协作边界。用户注册时自动创建私有 workspace，`owner` = 本人。
- `membership`：`(workspace_id, user_id, role)`，`role ∈ {owner, editor, viewer}`。
- `list` / `task` 均带 `workspace_id`。隔离键为 `workspace_id`，不再用 `user_id`。

## 二、权限变更走 op_log

- `entity_type = "membership"`，`entity_id = "<workspace_id>:<user_id>"`
- `field = "__role"`（值 `owner` / `editor` / `viewer`）或 `field = "__removed"`（值 `true`）
- 权限 op 与业务 op 走同一同步管道，离线端重连后可拉到权限变更并更新本地投影。

## 三、授权校验（唯一入口）

- 客户端不做授权裁决；UI 的按钮隐藏只是体验优化，不是安全边界。
- 服务端 Writer Worker 在追加 op 之前，逐条校验：
  1. 由 `entity_id` 反查 `workspace_id`
  2. 取该 op 的 HLC 时刻该 `user_id` 的成员角色
  3. 按权限矩阵判断是否允许该 `field` 的写入
- 投影层和读接口不做写权限判断，只按 workspace 成员关系做读过滤。
- 非法 op 必须被拒绝，禁止「先写入再靠投影掩盖」。

## 四、离线 + 权限变更

- 重连推送的 op 若因权限被拒，返回 `403 workspace_access_revoked`，携带 `server_hlc`。
- 客户端将该批 op 标记 `REJECTED`，保留在本地供导出，不自动重试，UI 提示权限已变更。
- 降权与移除在授权校验上等价：以 op 的 HLC 时刻的角色为准。
- `REJECTED` 是终态，不得与网络错误混为一类。
