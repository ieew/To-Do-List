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

## 五、本地多工作区与成员 UI 呈现

### 5.1 工作区列表

- 一个设备可同时拥有多个 `workspace`。前端启动时通过 `bootstrap` 命令读取工作区投影，禁止在组件中硬编码工作区名称或成员。
- 当前工作区 `active_workspace_id` 属于**设备本地偏好**，存客户端 SQLite 本地偏好表，不进 `op_log`、不同步。
- 切换工作区只改变前端作用域，不产生业务 op；切换后必须按 `workspace_id` 重新拉取投影，并重置搜索、标签筛选、详情抽屉与全局历史视图。
- 主窗口与所有便利贴窗口共享同一个 `active_workspace_id`；切换后由 Rust 广播 `projection-updated`，各窗口重新拉取。

### 5.2 工作区增改

- 新建工作区写两类 op：`entity_type="workspace"` 的 `name` 字段 op，以及 `entity_type="membership"` 的 `__role="owner"` op，二者同一事务。
- 重命名工作区写 `workspace` 的 `name` 字段 op，走 LWW；不新建实体。
- 原型阶段不提供删除工作区；后续以 `__deleted` 墓碑 op 实现，禁止物理删除 `op_log`。

### 5.3 成员列表来源

- 侧栏与设置页的成员列表**必须**来自 `membership` 投影，按当前 `workspace_id` 过滤，并排除 `__removed=true` 的成员。
- 成员资料（姓名、首字母、颜色、邮箱）来自本地 `user` 实体；`membership` 只负责角色与移除状态。
- 成员列表排序：`owner` → `editor` → `viewer`，同角色按加入顺序；当前用户置顶并标注「我」。
- 空态：工作区只有一个 owner 时显示「还没有其他协作成员」+「邀请成员」按钮；加载中显示 2 条骨架行。
- 角色徽章必须显示中文（所有者 / 可编辑 / 只读），不得直接输出 `owner`/`editor`/`viewer`。

### 5.4 角色变更与邀请

- 角色变更写 `membership` 的 `__role` op；移除成员写 `__removed=true` op。均走正常 op 管道。
- 客户端不得自行判断权限；UI 仅按当前用户角色决定是否展示「邀请成员」「修改角色」「移除」入口，服务端 Writer Worker 仍是唯一授权入口。
- 原型阶段成员与角色写本地 `op_log`，不接服务端；README 与设置页必须标注「本地模拟，未连接服务端」。
