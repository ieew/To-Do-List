"""历史时间线与恢复意图（纯函数）。"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.core_domain.op import Op
from app.core_domain.projection import project_state
from app.core_domain.reducer import State, field_key

_LIST_FIELDS = ("name", "color", "order")
_TAG_FIELDS = ("name", "bg", "fg", "show_in_sidebar")
_TASK_FIELDS = (
    "title",
    "notes",
    "start_at",
    "due_at",
    "remind_at",
    "priority",
    "status",
    "list_id",
    "order",
)


@dataclass(slots=True)
class RestoreChange:
    entity_type: str
    entity_id: str
    operation: str
    field: str
    value: Any
    old_value: Any = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "entity_type": self.entity_type,
            "entity_id": self.entity_id,
            "operation": self.operation,
            "field": self.field,
            "old_value": self.old_value,
            "new_value": self.value,
        }


def _time_label(ms: int, now_ms: int) -> str:
    minutes = max(0, now_ms - ms) // 60_000
    if minutes < 1:
        return "刚刚"
    if minutes < 60:
        return f"{minutes} 分钟前"
    return datetime.fromtimestamp(ms / 1000).astimezone().strftime("%m月%d日 %H:%M")


def _value_text(value: Any) -> str:
    if value is None:
        return "空"
    if value is True:
        return "是"
    if value is False:
        return "否"
    if isinstance(value, str):
        return value
    return str(value)


def _priority_text(value: str) -> str:
    return {"high": "高", "mid": "中", "low": "低"}.get(value, value)


def _status_text(value: str) -> str:
    return {"done": "已完成", "todo": "未完成"}.get(value, value)


def _display_value(field_name: str, value: Any) -> str:
    if field_name == "priority":
        return _priority_text(_value_text(value))
    if field_name == "status":
        return _status_text(_value_text(value))
    return _value_text(value)


def _action_of(op: Op, state: State) -> str:
    if op.field == "__created":
        return {
            "workspace": "创建了工作区",
            "list": "创建了清单",
            "tag": "创建了标签",
        }.get(op.entity_type, "创建了任务")
    if op.field == "__deleted":
        return {
            "workspace": "删除了工作区",
            "list": "删除了清单",
            "tag": "删除了标签",
        }.get(op.entity_type, "删除了任务")
    if op.field == "__undo_of":
        return "生成了撤销 op"
    if op.entity_type == "membership":
        return {
            "__role": "变更了成员角色",
            "__removed": "移除了成员",
        }.get(op.field, "更新了成员权限")
    if op.entity_type == "workspace":
        return "重命名了工作区"
    if op.operation == "add":
        return {"__tag": "添加了标签", "__subtask": "添加了子任务"}.get(op.field, "添加了内容")
    if op.operation == "remove":
        return {"__tag": "移除了标签", "__subtask": "删除了子任务"}.get(op.field, "移除了内容")
    if op.field.startswith("__subtask:"):
        if op.field.endswith(":done"):
            return "完成了子任务" if op.value is True else "恢复子任务"
        if op.field.endswith(":text"):
            return "修改了子任务"
    action = {
        "title": "修改了标题",
        "notes": "修改了备注",
        "start_at": "修改了开始时间",
        "due_at": "修改了截止时间",
        "remind_at": "修改了提醒时间",
        "priority": "修改了优先级",
        "list_id": "移动了清单",
    }.get(op.field, "更新了任务")
    if op.field == "status":
        return (
            "完成了任务"
            if state.text(op.entity_type, op.entity_id, "status") == "done"
            else "恢复为待办"
        )
    return action


def build_timeline(
    ops: Iterable[Op],
    *,
    workspace_id: str,
    current_user_id: str,
    task_id: str | None = None,
    limit: int = 100,
    before_seq: int | None = None,
    now_ms: int = 0,
) -> dict[str, Any]:
    workspace_ops = [op for op in ops if op.workspace_id == workspace_id]
    state = State()
    state.apply_all(workspace_ops)

    projection = project_state(state, workspace_id, current_user_id)
    task_titles = {task["id"]: task["title"] for task in projection["tasks"]}
    task_ids = set(task_titles)

    undone = {
        value
        for op in workspace_ops
        if op.field == "__undo_of" and isinstance(op.value, str)
        for value in [op.value]
    }

    last_values: dict[tuple[str, str, str], Any] = {}
    entries: list[dict[str, Any]] = []
    for op in sorted(workspace_ops, key=lambda item: item.key):
        if op.entity_type == "user" or op.field == "__workspace":
            continue
        if op.entity_type == "task" and op.entity_id not in task_ids:
            continue
        if task_id is not None and op.entity_id != task_id:
            continue

        key = field_key(op.entity_type, op.entity_id, op.field)
        old_value = last_values.get(key)
        last_values[key] = op.value

        title = (
            task_titles.get(op.entity_id)
            or state.text("workspace", op.entity_id, "name")
            or "工作区"
        )

        if op.source == "ai":
            actor_name = "AI 助手"
        elif op.actor_id == current_user_id:
            actor_name = "我"
        else:
            actor_name = state.text("user", op.actor_id, "name") or op.actor_id

        undoable = (
            op.operation == "set"
            and op.field not in {"__created", "__deleted", "__undo_of"}
            and op.source != "system"
        )

        entries.append(
            {
                "op_id": op.op_id,
                "seq": op.seq or 0,
                "entity_type": op.entity_type,
                "entity_id": op.entity_id,
                "task_id": op.entity_id,
                "task_title": title,
                "field": op.field,
                "operation": op.operation,
                "old_value": old_value,
                "new_value": op.value,
                "actor_id": op.actor_id,
                "actor_name": actor_name,
                "source": op.source,
                "model": op.model,
                "at_ms": op.physical_ms,
                "time_label": _time_label(op.physical_ms, now_ms),
                "action": _action_of(op, state),
                "undoable": undoable,
                "undone": op.op_id in undone,
                "system": op.source == "system",
            }
        )

    if before_seq is not None:
        entries = [entry for entry in entries if entry["seq"] < before_seq]
    entries.sort(key=lambda entry: (entry["at_ms"], entry["op_id"]), reverse=True)

    has_more = len(entries) > limit
    entries = entries[:limit]
    return {
        "entries": entries,
        "next_before_seq": entries[-1]["seq"] if entries else (before_seq or 0),
        "has_more": has_more,
    }


def diff_projections(target: dict[str, Any], current: dict[str, Any]) -> list[RestoreChange]:
    """生成把 current 恢复到 target 的意图；不删除 target 中不存在的实体。"""
    changes: list[RestoreChange] = []
    _diff_simple(changes, "list", target.get("lists", []), current.get("lists", []), _LIST_FIELDS)
    _diff_simple(changes, "tag", target.get("tags", []), current.get("tags", []), _TAG_FIELDS)
    _diff_tasks(changes, target.get("tasks", []), current.get("tasks", []))
    return changes


def _diff_simple(
    changes: list[RestoreChange],
    entity_type: str,
    target_items: list[dict[str, Any]],
    current_items: list[dict[str, Any]],
    fields: tuple[str, ...],
) -> None:
    current_map = {item["id"]: item for item in current_items}
    for item in target_items:
        entity_id = item["id"]
        existing = current_map.get(entity_id)
        if existing is None:
            changes.append(RestoreChange(entity_type, entity_id, "set", "__deleted", False, None))
            for field_name in fields:
                changes.append(
                    RestoreChange(
                        entity_type,
                        entity_id,
                        "set",
                        field_name,
                        item.get(field_name),
                        None,
                    )
                )
            continue
        for field_name in fields:
            if item.get(field_name) != existing.get(field_name):
                changes.append(
                    RestoreChange(
                        entity_type,
                        entity_id,
                        "set",
                        field_name,
                        item.get(field_name),
                        existing.get(field_name),
                    )
                )


def _diff_tasks(
    changes: list[RestoreChange],
    target_tasks: list[dict[str, Any]],
    current_tasks: list[dict[str, Any]],
) -> None:
    current_map = {task["id"]: task for task in current_tasks}
    for task in target_tasks:
        task_id = task["id"]
        existing = current_map.get(task_id)
        if existing is None:
            changes.append(RestoreChange("task", task_id, "set", "__deleted", False, None))
            for field_name in _TASK_FIELDS:
                changes.append(
                    RestoreChange("task", task_id, "set", field_name, task.get(field_name), None)
                )
            _restore_collections(changes, task, {})
            continue

        for field_name in _TASK_FIELDS:
            if task.get(field_name) != existing.get(field_name):
                changes.append(
                    RestoreChange(
                        "task",
                        task_id,
                        "set",
                        field_name,
                        task.get(field_name),
                        existing.get(field_name),
                    )
                )
        _restore_collections(changes, task, existing)


def _restore_collections(
    changes: list[RestoreChange],
    target: dict[str, Any],
    current: dict[str, Any],
) -> None:
    task_id = target["id"]
    target_tags = set(target.get("tag_ids") or [])
    current_tags = set(current.get("tag_ids") or [])
    for tag_id in sorted(target_tags - current_tags):
        changes.append(RestoreChange("task", task_id, "add", "__tag", tag_id, None))
    for tag_id in sorted(current_tags - target_tags):
        changes.append(RestoreChange("task", task_id, "remove", "__tag", tag_id, None))

    target_subtasks = {item["id"]: item for item in target.get("subtasks") or []}
    current_subtasks = {item["id"]: item for item in current.get("subtasks") or []}

    for subtask_id in sorted(current_subtasks.keys() - target_subtasks.keys()):
        changes.append(RestoreChange("task", task_id, "remove", "__subtask", subtask_id, None))

    for subtask_id, subtask in target_subtasks.items():
        if subtask_id not in current_subtasks:
            changes.append(RestoreChange("task", task_id, "add", "__subtask", subtask_id, None))
            changes.append(
                RestoreChange(
                    "task",
                    task_id,
                    "set",
                    f"__subtask:{subtask_id}:text",
                    subtask.get("text", ""),
                    None,
                )
            )
            changes.append(
                RestoreChange(
                    "task",
                    task_id,
                    "set",
                    f"__subtask:{subtask_id}:done",
                    bool(subtask.get("done", False)),
                    None,
                )
            )
            changes.append(
                RestoreChange(
                    "task",
                    task_id,
                    "set",
                    f"__subtask:{subtask_id}:order",
                    subtask.get("order", 0.0),
                    None,
                )
            )
            continue

        existing = current_subtasks[subtask_id]
        for name in ("text", "done", "order"):
            if subtask.get(name) != existing.get(name):
                changes.append(
                    RestoreChange(
                        "task",
                        task_id,
                        "set",
                        f"__subtask:{subtask_id}:{name}",
                        subtask.get(name),
                        existing.get(name),
                    )
                )
