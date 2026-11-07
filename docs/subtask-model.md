# 子任务建模规格：父任务内部 checklist

## 一、定位

- 子任务不是独立 `task` 实体，不携带 `parent_id`。
- 子任务是父任务内部的一个 checklist 集合，挂在父任务的 `entity_id` 下。
- 列表视图查询父任务时，子任务作为父任务属性一起取出，不需要过滤。

## 二、数据表达

- 子任务存在性：`operation = "add"` / `"remove"`，`field = "__subtask"`，`value = "<subtask_id>"`。
- 子任务字段：`operation = "set"`，`field = "__subtask:<subtask_id>:text"` / `":done"` / `":order"`。
- 每个子任务字段是独立 LWW 单元格，按 `(hlc, node_id, op_id)` 全序裁决。
- 子任务 `order` 用 `fractional indexing`，并发插入按 `(order, subtask_id)` 确定性平局裁决。

## 三、字段范围（刻意限制）

- 子任务只有 `text`、`done`、`order` 三个字段。
- 不支持独立截止日期、标签、提醒、权限、历史恢复入口。
- 需要完整能力的子任务应「提升为任务」，而非给子任务加字段。

## 四、合并机制

- 子任务增删复用标签关联的 OR-Set 机制（`operation = add` / `remove`，add-wins）。
- 子任务字段修改复用 `resolveField` 的 LWW。
- 合并函数不需要为子任务新增独立逻辑，只依赖 field 命名约定和 `operation` 分派。

## 五、提升为任务

- 客户端读取子任务的 `text` / `done`，生成新的独立 `task` 实体（新 `entity_id`、`__created` op），
  同时 remove 原子任务。走标准 op 管道，不破坏日志只追加原则。

## 六、测试向量必须覆盖

- 并发添加不同子任务、并发添加同一子任务。
- 并发 `add` / `remove` 同一子任务（add-wins）。
- 并发修改同一子任务的不同字段（都存活）。
- 并发修改同一子任务的同一字段（LWW 裁决）。
- 并发插入子任务导致 `order` 冲突（确定性平局裁决）。
- 子任务提升为任务时与并发字段编辑的交错。
