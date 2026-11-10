"""纯领域层测试：共享向量 + 收敛性 / 幂等性 + 时钟禁令。"""

from __future__ import annotations

import ast
import itertools
import json
from pathlib import Path

import pytest

from app.core_domain.op import Op
from app.core_domain.projection import build_projection
from app.core_domain.reducer import State
from app.core_domain.timeline import diff_projections

from .conftest import VECTOR_DIR


def load_vector(name: str) -> dict:
    return json.loads((VECTOR_DIR / f"{name}.json").read_text(encoding="utf-8"))


def build_state(vector: dict) -> State:
    state = State()
    state.apply_all([Op.from_wire(item) for item in vector["ops"]])
    return state


def assert_expectations(state: State, vector: dict) -> None:
    expect = vector.get("expect", {})
    for key, expected in expect.get("fields", {}).items():
        entity_type, entity_id, field_name = key.split(":", 2)
        assert state.raw(entity_type, entity_id, field_name) == expected, key
    for key, expected in expect.get("collections", {}).items():
        entity_type, entity_id, field_name = key.split(":", 2)
        assert state.elements(entity_type, entity_id, field_name) == expected, key


@pytest.mark.parametrize("name", ["lww", "orset_add_wins", "subtask_fields", "tag_association"])
def test_shared_vectors(name: str) -> None:
    vector = load_vector(name)
    assert_expectations(build_state(vector), vector)

    projection_expect = vector.get("expect", {}).get("projection")
    if projection_expect:
        projection = build_projection([Op.from_wire(item) for item in vector["ops"]], "ws-t", "u-1")
        if projection_expect.get("tags_empty"):
            assert projection["tags"] == []
        if "task_tag_ids" in projection_expect:
            tasks = {task["id"]: task for task in projection["tasks"]}
            assert tasks["task-1"]["tag_ids"] == projection_expect["task_tag_ids"]


def test_projection_shape() -> None:
    vector = load_vector("tag_association")
    projection = build_projection([Op.from_wire(item) for item in vector["ops"]], "ws-t", "u-1")
    assert len(projection["lists"]) == 1
    assert projection["lists"][0]["smart"] is False
    task = projection["tasks"][0]
    assert task["tag_ids"] == ["tag-1"]
    assert task["priority"] == "mid"
    assert task["status"] == "todo"


def test_restore_vector() -> None:
    vector = load_vector("restore")
    changes = diff_projections(vector["target"], vector["current"])
    actual = {
        (change.entity_id, change.operation, change.field, change.value) for change in changes
    }
    expected = {
        (entity_id, operation, field_name, value)
        for entity_id, operation, field_name, value in vector["expect"]["changes"]
    }
    assert actual == expected


def test_convergence_and_idempotency() -> None:
    vector = load_vector("orset_add_wins")
    ops = [Op.from_wire(item) for item in vector["ops"]]

    baseline = State()
    baseline.apply_all(ops)
    baseline_tags = baseline.elements("task", "task-1", "__tag")

    for permutation in itertools.permutations(ops, len(ops)):
        state = State()
        state.apply_all(list(permutation))
        assert state.elements("task", "task-1", "__tag") == baseline_tags

    duplicated = State()
    duplicated.apply_all(ops + ops)
    assert duplicated.elements("task", "task-1", "__tag") == baseline_tags


def test_core_domain_never_reads_clock() -> None:
    core_dir = Path(__file__).resolve().parents[1] / "app" / "core_domain"
    forbidden_modules = {"time", "asyncio", "fastapi", "aiosqlite", "pydantic"}
    for path in core_dir.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    assert alias.name.split(".")[0] not in forbidden_modules, (
                        f"{path.name} 不得 import {alias.name}"
                    )
            elif isinstance(node, ast.ImportFrom) and node.module:
                root = node.module.split(".")[0]
                assert root not in forbidden_modules, f"{path.name} 不得 import {node.module}"
            elif isinstance(node, ast.Attribute) and node.attr in {
                "now",
                "utcnow",
                "today",
                "time",
                "monotonic",
            }:
                # 只禁止读取时钟；fromtimestamp / isoformat 等格式化不受影响。
                if isinstance(node.value, ast.Name) and node.value.id in {"datetime", "time"}:
                    raise AssertionError(f"{path.name} 出现时钟读取: {node.value.id}.{node.attr}")
