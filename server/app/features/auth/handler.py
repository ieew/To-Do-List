"""鉴权切片的 HTTP 适配层。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Response, status

from app.api.deps import get_current_user, get_services
from app.features.auth import usecase
from app.features.auth.schemas import (
    LoginRequest,
    LoginResponse,
    LogoutRequest,
    MeResponse,
    RefreshRequest,
    RefreshResponse,
    RegisterRequest,
    RegisterResponse,
    WorkspaceListResponse,
)
from app.services import Services

router = APIRouter(prefix="/auth", tags=["auth"])
workspaces_router = APIRouter(tags=["workspace"])


@router.post("/register", response_model=RegisterResponse, status_code=status.HTTP_201_CREATED)
async def register(
    payload: RegisterRequest,
    services: Annotated[Services, Depends(get_services)],
) -> dict:
    return await usecase.register(services, payload)


@router.post("/login", response_model=LoginResponse)
async def login(
    payload: LoginRequest,
    services: Annotated[Services, Depends(get_services)],
) -> dict:
    return await usecase.login(services, payload)


@router.post("/refresh", response_model=RefreshResponse)
async def refresh(
    payload: RefreshRequest,
    services: Annotated[Services, Depends(get_services)],
) -> dict:
    return await usecase.refresh(services, payload)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    payload: LogoutRequest,
    services: Annotated[Services, Depends(get_services)],
) -> Response:
    await usecase.logout(services, RefreshRequest(refresh_token=payload.refresh_token))
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", response_model=MeResponse)
async def me(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.me(services, user_id)


@workspaces_router.get("/workspaces", response_model=WorkspaceListResponse)
async def list_workspaces(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
) -> dict:
    return await usecase.workspaces(services, user_id)
