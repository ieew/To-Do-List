"""历史切片的 HTTP 适配层。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query, status

from app.api.deps import get_current_user, get_services
from app.features.history import usecase
from app.features.history.schemas import RestoreRequest, SnapshotRequest
from app.services import Services

router = APIRouter(prefix="/history", tags=["history"])


@router.get("/timeline")
async def timeline(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
    workspace_id: str,
    task_id: str | None = None,
    limit: int = Query(100, ge=1, le=1000),
    before_seq: int | None = Query(None, ge=0),
) -> dict:
    return await usecase.timeline(
        services,
        user_id,
        workspace_id=workspace_id,
        task_id=task_id,
        limit=limit,
        before_seq=before_seq,
    )


@router.post("/snapshot", status_code=status.HTTP_201_CREATED)
async def create_snapshot(
    payload: SnapshotRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.create_snapshot(services, user_id, payload.workspace_id, payload.seq)


@router.get("/snapshot")
async def get_snapshot(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
    workspace_id: str,
    seq: int | None = Query(None, ge=0),
) -> dict:
    return await usecase.get_snapshot(services, user_id, workspace_id, seq)


@router.post("/restore")
async def restore(
    payload: RestoreRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.restore(
        services,
        user_id,
        workspace_id=payload.workspace_id,
        snapshot_id=payload.snapshot_id,
        dry_run=payload.dry_run,
    )
