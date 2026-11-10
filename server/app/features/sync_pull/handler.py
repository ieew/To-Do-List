"""增量拉取的 HTTP 适配层。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.api.deps import get_current_user, get_services
from app.features.sync_pull import usecase
from app.services import Services

router = APIRouter(prefix="/sync", tags=["sync"])


@router.get("/pull")
async def pull(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
    workspace_id: str | None = None,
    since_seq: int = Query(0, ge=0),
    limit: int = Query(200, ge=1, le=1000),
) -> dict:
    return await usecase.pull(
        services,
        user_id,
        workspace_id=workspace_id,
        since_seq=since_seq,
        limit=limit,
    )
