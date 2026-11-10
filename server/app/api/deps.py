"""FastAPI 依赖：服务容器与当前用户。"""

from __future__ import annotations

from typing import Annotated

from fastapi import Header, Request

from app.core_domain.errors import AppError
from app.infra.security import decode_access_token
from app.services import Services


def get_services(request: Request) -> Services:
    return request.app.state.services


async def get_current_user(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> str:
    services = request.app.state.services
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AppError("unauthorized")
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise AppError("unauthorized")
    return decode_access_token(services.settings.jwt_secret, token)
