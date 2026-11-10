"""同步推送的 HTTP 适配层。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.api.deps import get_current_user, get_services
from app.features.sync_push import usecase
from app.features.sync_push.schemas import PushRequest
from app.services import Services

router = APIRouter(prefix="/sync", tags=["sync"])


@router.post("/push")
async def push(
    payload: PushRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> JSONResponse:
    status_code, body = await usecase.push(services, user_id, payload)
    return JSONResponse(status_code=status_code, content=body)
