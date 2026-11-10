"""备份的 HTTP 适配层。"""

from __future__ import annotations

from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

from app.api.deps import get_current_user, get_services
from app.core_domain.errors import AppError
from app.features.backup import usecase
from app.infra.security import constant_time_equals
from app.services import Services

router = APIRouter(tags=["backup"])


@router.get("/backup/export")
async def export_workspace(
    services: Annotated[Services, Depends(get_services)],
    user_id: Annotated[str, Depends(get_current_user)],
    workspace_id: str,
) -> FileResponse:
    path, filename = await usecase.export_workspace(services, user_id, workspace_id)
    return FileResponse(
        path,
        media_type="application/zip",
        filename=filename,
        background=BackgroundTask(_cleanup, path),
    )


def _cleanup(path: Path) -> None:
    path.unlink(missing_ok=True)


@router.post("/admin/backup", status_code=201)
async def admin_backup(
    services: Annotated[Services, Depends(get_services)],
    x_admin_token: Annotated[str | None, Header()] = None,
) -> dict:
    configured = services.settings.admin_token
    if not configured:
        # 未配置时隐藏接口存在性。
        raise HTTPException(status_code=404, detail="Not Found")
    if not x_admin_token or not constant_time_equals(x_admin_token, configured):
        raise AppError("forbidden")
    return await usecase.admin_backup(services)
