"""工作区投影：与客户端 `Projection::build` 同构（服务端只输出工作区视图部分）。"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime
from typing import Any

from app.core_domain.op import Op
from app.core_domain.reducer import State


def iso_from_ms(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


def workspace_role(state: State, workspace_id: str, user_id: str) -> str | None:
    membership_id = f"{workspace_id}:{user_id}"
    if state.flag("membership", membership_id, "__removed"):
        return None
    return state.text("membership", membership_id, "__role")


def build_workspace_summaries(state: State, user_id: str) -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    for workspace_id in state.entities("workspace"):
        if state.deleted("workspace", workspace_id):
            continue
        role = workspace_role(state, workspace_id, user_id)
        if role is None:
            continue
        member_count = 0
        for entity_id in state.entities("membership"):
            workspace_part, _, _user_part = entity_id.partition(":")
            if workspace_part != workspace_id:
                continue
            if state.flag("membership", entity_id, "__removed"):
                continue
            if state.text("membership", entity_id, "__role") is None:
                continue
            member_count += 1
        meta = state.meta_for("workspace", workspace_id)
        created_at = state.text("workspace", workspace_id, "__created") or iso_from_ms(
            meta.first_ms if meta else 0
        )
        summaries.append(
            {
                "workspace_id": workspace_id,
                "name": state.text("workspace", workspace_id, "name") or "未命名空间",
                "role": role,
                "member_count": member_count,
                "created_at": created_at,
            }
        )
    summaries.sort(key=lambda item: (item["name"], item["workspace_id"]))
    return summaries


def build_projection(ops: Iterable[Op], workspace_id: str, current_user_id: str) -> dict[str, Any]:
    state = State()
    state.apply_all(ops)
    return project_state(state, workspace_id, current_user_id)


def project_state(state: State, workspace_id: str, current_user_id: str) -> dict[str, Any]:
    lists, list_workspace = _project_lists(state, workspace_id)
    tags = _project_tags(state, workspace_id)
    members = _project_members(state, workspace_id, current_user_id)
    tasks = _project_tasks(state, workspace_id, list_workspace)
    return {
        "workspace_id": workspace_id,
        "lists": lists,
        "tags": tags,
        "members": members,
        "tasks": tasks,
    }


def _project_lists(state: State, workspace_id: str) -> tuple[list[dict[str, Any]], dict[str, str]]:
    lists: list[dict[str, Any]] = []
    list_workspace: dict[str, str] = {}
    for list_id in state.entities("list"):
        if state.deleted("list", list_id):
            continue
        owner = state.text("list", list_id, "workspace_id") or workspace_id
        list_workspace[list_id] = owner
        if owner != workspace_id:
            continue
        lists.append(
            {
                "id": list_id,
                "name": state.text("list", list_id, "name") or "未命名清单",
                "color": state.text("list", list_id, "color") or "#3B82F6",
                "order": state.number("list", list_id, "order", 0.0),
                "smart": list_id == "today" or list_id.endswith("-today"),
            }
        )
    lists.sort(key=lambda item: (item["order"], item["id"]))
    return lists, list_workspace


def _project_tags(state: State, workspace_id: str) -> list[dict[str, Any]]:
    tags: list[dict[str, Any]] = []
    for tag_id in state.entities("tag"):
        if state.deleted("tag", tag_id):
            continue
        owner = state.text("tag", tag_id, "workspace_id") or workspace_id
        if owner != workspace_id:
            continue
        tags.append(
            {
                "id": tag_id,
                "name": state.text("tag", tag_id, "name") or "标签",
                "bg": state.text("tag", tag_id, "bg") or "#EFF6FF",
                "fg": state.text("tag", tag_id, "fg") or "#2563EB",
                "show_in_sidebar": state.raw("tag", tag_id, "show_in_sidebar")
                if isinstance(state.raw("tag", tag_id, "show_in_sidebar"), bool)
                else None,
            }
        )
    tags.sort(key=lambda item: (item["name"], item["id"]))
    return tags


def _project_members(state: State, workspace_id: str, current_user_id: str) -> list[dict[str, Any]]:
    members: list[dict[str, Any]] = []
    for entity_id in state.entities("membership"):
        workspace_part, _, user_id = entity_id.partition(":")
        if workspace_part != workspace_id or not user_id:
            continue
        if state.flag("membership", entity_id, "__removed"):
            continue
        role = state.text("membership", entity_id, "__role")
        if role is None:
            continue
        meta = state.meta_for("membership", entity_id)
        members.append(
            {
                "user_id": user_id,
                "name": state.text("user", user_id, "name") or user_id,
                "initial": state.text("user", user_id, "initial") or "用",
                "color": state.text("user", user_id, "color") or "#3B82F6",
                "role": role,
                "email": state.text("user", user_id, "email") or f"{user_id}@todo.local",
                "joined_at": iso_from_ms(meta.first_ms if meta else 0),
            }
        )

    role_rank = {"owner": 0, "editor": 1}
    members.sort(
        key=lambda item: (
            0 if item["user_id"] == current_user_id else 1,
            role_rank.get(item["role"], 2),
            item["name"],
            item["user_id"],
        )
    )
    return members


def _project_tasks(
    state: State, workspace_id: str, list_workspace: dict[str, str]
) -> list[dict[str, Any]]:
    tasks: list[dict[str, Any]] = []
    for task_id in state.entities("task"):
        if state.deleted("task", task_id):
            continue
        list_id = state.text("task", task_id, "list_id") or ""
        if list_workspace.get(list_id) != workspace_id:
            continue

        meta = state.meta_for("task", task_id)
        status = state.text("task", task_id, "status") or "todo"
        created_at = state.text("task", task_id, "__created") or iso_from_ms(
            meta.first_ms if meta else 0
        )

        subtasks: list[dict[str, Any]] = []
        for subtask_id in state.elements("task", task_id, "__subtask"):
            subtasks.append(
                {
                    "id": subtask_id,
                    "text": state.text("task", task_id, f"__subtask:{subtask_id}:text") or "子任务",
                    "done": state.flag("task", task_id, f"__subtask:{subtask_id}:done"),
                    "order": state.number("task", task_id, f"__subtask:{subtask_id}:order", 0.0),
                }
            )
        subtasks.sort(key=lambda item: (item["order"], item["id"]))

        completed_at = None
        if status == "done":
            status_ms = state.field_physical_ms("task", task_id, "status")
            completed_at = iso_from_ms(status_ms or 0)

        tasks.append(
            {
                "id": task_id,
                "title": state.text("task", task_id, "title") or "未命名任务",
                "notes": state.text("task", task_id, "notes") or "",
                "start_at": state.text("task", task_id, "start_at"),
                "due_at": state.text("task", task_id, "due_at"),
                "remind_at": state.text("task", task_id, "remind_at"),
                "priority": state.text("task", task_id, "priority") or "mid",
                "status": status,
                "list_id": list_id,
                "tag_ids": state.elements("task", task_id, "__tag"),
                "subtasks": subtasks,
                "source": meta.source if meta else "human",
                "created_at": created_at,
                "completed_at": completed_at,
                "order": state.number("task", task_id, "order", 0.0),
                "model": meta.model if meta else None,
            }
        )

    tasks.sort(
        key=lambda item: (
            1 if item["status"] == "done" else 0,
            item["order"],
            item["id"],
        )
    )
    return tasks
