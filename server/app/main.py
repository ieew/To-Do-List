"""应用工厂：生命周期、异常映射、限流与路由注册。"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.v1 import router as v1_router
from app.config import ServerSettings
from app.core_domain.errors import AppError, error_payload
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker
from app.infra.clock import now_ms
from app.infra.locking import ProcessLock
from app.infra.rate_limit import SlidingWindowLimiter
from app.services import Services

logger = logging.getLogger("todo.server")


def _validation_details(exc: RequestValidationError) -> list[dict[str, str]]:
    details: list[dict[str, str]] = []
    for error in exc.errors()[:20]:
        location = ".".join(str(part) for part in error.get("loc", ()))
        details.append({"field": location, "reason": str(error.get("msg", "字段不合法"))})
    return details


def create_app(settings: ServerSettings | None = None) -> FastAPI:
    server_settings = settings or ServerSettings()
    logging.basicConfig(
        level=getattr(logging, server_settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        server_settings.validate_for_startup()
        lock = ProcessLock(
            server_settings.db_path.with_suffix(server_settings.db_path.suffix + ".lock")
        )
        lock.acquire()
        writer = WriterWorker(server_settings.db_path)
        await writer.start(now_ms=now_ms())
        read_pool = ReadPool(server_settings.db_path, size=4)
        await read_pool.start()
        services = Services(settings=server_settings, writer=writer, read=read_pool)
        app.state.services = services
        logger.info("服务端已启动：db=%s", server_settings.db_path)
        try:
            yield
        finally:
            for task in list(services.background):
                task.cancel()
            await writer.stop()
            await read_pool.close()
            lock.release()
            logger.info("服务端已停止")

    app = FastAPI(
        title="待办清单同步服务端",
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/docs",
        openapi_url="/openapi.json",
    )

    limiter = SlidingWindowLimiter(server_settings.auth_rate_limit)
    app.state.limiter = limiter

    if server_settings.allowed_cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=server_settings.allowed_cors_origins,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    @app.middleware("http")
    async def body_limit(request: Request, call_next):
        if request.method in {"POST", "PATCH", "PUT"}:
            raw_length = request.headers.get("content-length")
            if raw_length and raw_length.isdigit():
                if int(raw_length) > server_settings.max_body_bytes:
                    return JSONResponse(
                        status_code=413,
                        content={"error": error_payload("payload_too_large")},
                    )
        return await call_next(request)

    @app.middleware("http")
    async def rate_limit(request: Request, call_next):
        if request.url.path.startswith("/v1/auth/"):
            client = request.client.host if request.client else "unknown"
            if not await limiter.allow(client):
                return JSONResponse(
                    status_code=429,
                    content={"error": error_payload("rate_limited")},
                    headers={"Retry-After": "60"},
                )
        return await call_next(request)

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content={"error": exc.payload()})

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        code = (
            "invalid_op_envelope" if request.url.path.endswith("/sync/push") else "invalid_request"
        )
        return JSONResponse(
            status_code=400,
            content={"error": error_payload(code, details=_validation_details(exc))},
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_request: Request, exc: StarletteHTTPException) -> JSONResponse:
        message = "接口不存在" if exc.status_code == 404 else str(exc.detail)
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": error_payload("invalid_request", message=message)},
        )

    @app.exception_handler(Exception)
    async def unhandled_handler(_request: Request, exc: Exception) -> JSONResponse:
        logger.exception("未处理异常: %s", exc)
        return JSONResponse(status_code=500, content={"error": error_payload("internal_error")})

    app.include_router(v1_router)
    return app


app = create_app()
