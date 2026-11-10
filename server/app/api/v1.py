"""v1 路由聚合与健康检查。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.api.deps import get_services
from app.core_domain.errors import error_payload
from app.db import ops as ops_repo
from app.features.auth.handler import router as auth_router
from app.features.auth.handler import workspaces_router
from app.features.backup.handler import router as backup_router
from app.features.history.handler import router as history_router
from app.features.sync_pull.handler import router as pull_router
from app.features.sync_push.handler import router as push_router
from app.features.workspace_member.handler import router as workspace_router
from app.services import Services

health_router = APIRouter(tags=["health"])


@health_router.get("/health")
async def health(services: Annotated[Services, Depends(get_services)]) -> JSONResponse:
    try:
        async with services.read.acquire() as conn:
            server_seq = await ops_repo.latest_seq(conn)
            server_hlc = await ops_repo.server_hlc_snapshot(conn)
    except Exception:  # noqa: BLE001 - 健康检查需要把任何异常映射为 503
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "db_seq": 0, "writer_queue_depth": 0},
        )
    return JSONResponse(
        status_code=200,
        content={
            "status": "ok",
            "protocol_version": services.settings.protocol_version,
            "db_seq": server_seq,
            "server_hlc": server_hlc.to_wire(),
            "writer_queue_depth": services.writer.queue_depth,
        },
    )


router = APIRouter(prefix="/v1")
router.include_router(health_router)
router.include_router(auth_router)
router.include_router(workspaces_router)
router.include_router(push_router)
router.include_router(pull_router)
router.include_router(workspace_router)
router.include_router(history_router)
router.include_router(backup_router)

__all__ = ["router", "error_payload"]
