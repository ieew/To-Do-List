"""dsh-kit 的网页对端（P3）：一个页面搞定「发通知 / 发剪贴板 / 收消息」。

页面是纯静态 HTML（同源加载，不用配 CORS），所有请求都走已有的 `/v1/dsh/*`。
放在服务端里而不是单独起服务：联调时开一个地址就够了。
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, RedirectResponse

from app.core_domain.errors import AppError

HTML = Path(__file__).with_name("web").joinpath("dsh.html")

router = APIRouter(tags=["dsh-web"])


@router.get("/dsh", include_in_schema=False)
async def dsh_page_redirect() -> RedirectResponse:
    return RedirectResponse(url="/dsh/")


@router.get("/dsh/", include_in_schema=False)
@router.get("/dsh/index.html", include_in_schema=False)
async def dsh_page() -> FileResponse:
    if not HTML.exists():
        # 部署漏了静态文件时给出明确原因，而不是 500
        raise AppError("not_implemented", message=f"缺少页面文件：{HTML}")
    return FileResponse(HTML, media_type="text/html; charset=utf-8")
