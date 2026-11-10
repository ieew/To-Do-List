"""成员管理的 HTTP 适配层。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Response, status

from app.api.deps import get_current_user, get_services
from app.features.workspace_member import usecase
from app.features.workspace_member.schemas import InviteRequest, RemoveRequest, RoleRequest
from app.services import Services

router = APIRouter(prefix="/workspace", tags=["workspace"])


@router.get("/members")
async def list_members(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
    workspace_id: str,
) -> dict:
    return await usecase.list_members(services, user_id, workspace_id)


@router.post("/invite")
async def invite(
    payload: InviteRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.invite(services, user_id, payload)


@router.patch("/role")
async def set_role(
    payload: RoleRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.set_role(services, user_id, payload)


@router.post("/remove", status_code=status.HTTP_204_NO_CONTENT)
async def remove(
    payload: RemoveRequest,
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> Response:
    await usecase.remove(services, user_id, payload.workspace_id, payload.user_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
