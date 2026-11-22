"""dsh 推送通道的 HTTP 适配层（`/v1/dsh/*`）。

鉴权有两套：
- 登录用户：`Authorization: Bearer <access_token>`
- 设备票据：`X-Dsh-Device-Id: <device_id>` + `X-Dsh-Ticket: <ticket>`（只用于 pull/ack）
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Header, Query, Request, status

from app.api.deps import get_current_user, get_services
from app.core_domain.errors import AppError
from app.features.dsh import usecase
from app.features.dsh.schemas import AckRequest, ClipRequest, PushRequest, RegisterDeviceRequest
from app.features.dsh.ticket import verify_ticket
from app.infra.clock import now_ms
from app.infra.security import constant_time_equals, decode_access_token
from app.services import Services

router = APIRouter(prefix="/dsh", tags=["dsh"])


@dataclass(frozen=True, slots=True)
class _Identity:
    user_id: str
    device_id: str | None
    scope: Literal["user", "device"]


async def get_dsh_identity(
    request: Request,
    x_dsh_device_id: Annotated[str | None, Header()] = None,
    x_dsh_ticket: Annotated[str | None, Header()] = None,
    authorization: Annotated[str | None, Header()] = None,
) -> _Identity:
    services = request.app.state.services
    if x_dsh_ticket and x_dsh_device_id:
        claims = verify_ticket(
            services.settings.jwt_secret,
            device_id=x_dsh_device_id,
            ticket=x_dsh_ticket,
            now_ms=now_ms(),
        )
        if claims is None:
            raise AppError("unauthorized", message="设备票据无效或已过期，请重新注册设备")
        return _Identity(user_id=claims.user_id, device_id=claims.device_id, scope="device")
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization.split(" ", 1)[1].strip()
        if token:
            try:
                return _Identity(
                    user_id=decode_access_token(services.settings.jwt_secret, token),
                    device_id=None,
                    scope="user",
                )
            except AppError:
                pass
    raise AppError("unauthorized")


Identity = Annotated[_Identity, Depends(get_dsh_identity)]
CurrentUser = Annotated[str, Depends(get_current_user)]
Container = Annotated[Services, Depends(get_services)]


# ---------------------------------------------------------------- 设备


@router.post("/devices/register", status_code=status.HTTP_201_CREATED)
async def register_device(
    payload: RegisterDeviceRequest,
    services: Container,
    user_id: CurrentUser,
) -> dict:
    return await usecase.register_device(services, user_id, payload)


@router.get("/devices")
async def list_devices(services: Container, user_id: CurrentUser) -> dict:
    return await usecase.list_devices(services, user_id)


@router.post("/devices/{device_id}/revoke")
async def revoke_device(device_id: str, services: Container, user_id: CurrentUser) -> dict:
    return await usecase.revoke_device(services, user_id, device_id)


# ---------------------------------------------------------------- 投递 / 拉取 / 确认


@router.post("/push")
async def push(
    payload: PushRequest,
    request: Request,
    services: Container,
    authorization: Annotated[str | None, Header()] = None,
    x_admin_token: Annotated[str | None, Header()] = None,
) -> dict:
    admin_token = services.settings.admin_token
    is_admin = bool(
        admin_token and x_admin_token and constant_time_equals(x_admin_token, admin_token)
    )
    if is_admin:
        if not payload.user_id:
            raise AppError("invalid_request", message="admin 推送必须指定 user_id")
        user_id = payload.user_id
    else:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise AppError("unauthorized")
        token = authorization.split(" ", 1)[1].strip()
        if not token:
            raise AppError("unauthorized")
        user_id = decode_access_token(services.settings.jwt_secret, token)
        if payload.user_id and payload.user_id != user_id:
            raise AppError("forbidden")
    return await usecase.push(services, user_id=user_id, payload=payload)


@router.post("/clip")
async def clip(
    payload: ClipRequest,
    services: Container,
    identity: Identity,
) -> dict:
    # 设备票据：来源就是票据里的设备，显式 device_id 只是它的副本。
    # 登录用户：device_id 是**目标**设备（对端 CLI/网页要发给某台手机），不是来源。
    source = identity.device_id
    if source:
        if payload.device_id and payload.device_id != source:
            raise AppError("forbidden")
        payload.device_id = source
    return await usecase.clip(
        services, user_id=identity.user_id, payload=payload, exclude_device_id=source
    )


@router.get("/pull")
async def pull(
    services: Container,
    identity: Identity,
    device_id: str | None = None,
    since: int = Query(0, ge=0),
    limit: int = Query(100, ge=1, le=500),
) -> dict:
    target = _resolve_device(identity, device_id)
    return await usecase.pull(
        services,
        user_id=identity.user_id,
        device_id=target,
        since_seq=since,
        limit=limit,
    )


@router.post("/ack")
async def ack(
    payload: AckRequest,
    services: Container,
    identity: Identity,
) -> dict:
    target = _resolve_device(identity, payload.device_id)
    return await usecase.ack(
        services, user_id=identity.user_id, device_id=target, seqs=sorted(set(payload.seqs))
    )


def _resolve_device(identity: _Identity, requested: str | None) -> str:
    target = identity.device_id or requested
    if not target:
        raise AppError("invalid_request", message="缺少 device_id")
    if identity.device_id and requested and requested != identity.device_id:
        raise AppError("forbidden")
    return target


# ---------------------------------------------------------------- 状态


@router.get("/state")
async def state(services: Container, user_id: CurrentUser) -> dict:
    return await usecase.state(services, user_id)
