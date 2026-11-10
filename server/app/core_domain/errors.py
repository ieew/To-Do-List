"""错误目录与统一异常。

`code` 为英文稳定标识，`message` 为中文可读文案；HTTP 状态与是否可重试由目录决定。
领域层不依赖 FastAPI，只暴露数据结构。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class ErrorInfo:
    status: int
    retryable: bool
    message: str


ERRORS: dict[str, ErrorInfo] = {
    "invalid_request": ErrorInfo(400, False, "请求参数不合法"),
    "invalid_op_envelope": ErrorInfo(400, False, "同步数据格式不正确"),
    "invalid_username": ErrorInfo(400, False, "用户名需为 3–32 位字母、数字、下划线或短横线"),
    "weak_password": ErrorInfo(400, False, "密码长度需为 8–128 位"),
    "invalid_email": ErrorInfo(400, False, "邮箱格式不正确"),
    "unknown_entity_type": ErrorInfo(400, False, "未知的实体类型"),
    "unknown_field": ErrorInfo(200, False, "未知字段"),
    "invalid_operation": ErrorInfo(200, False, "该字段不支持此操作"),
    "invalid_value": ErrorInfo(200, False, "字段值不合法"),
    "unknown_list": ErrorInfo(200, False, "目标清单不存在"),
    "unknown_user": ErrorInfo(200, False, "目标用户不存在"),
    "entity_not_created": ErrorInfo(200, False, "实体尚未创建"),
    "entity_workspace_mismatch": ErrorInfo(200, False, "实体不属于该工作区"),
    "actor_mismatch": ErrorInfo(200, False, "操作者与登录身份不一致"),
    "hlc_too_far_ahead": ErrorInfo(200, False, "本机时间偏差较大，已按服务器时间校正"),
    "workspace_access_revoked": ErrorInfo(403, False, "权限已变更，此操作未生效"),
    "forbidden": ErrorInfo(403, False, "当前角色无权执行此操作"),
    "unauthorized": ErrorInfo(401, False, "请先登录"),
    "token_expired": ErrorInfo(401, True, "登录已过期，正在刷新"),
    "invalid_access_token": ErrorInfo(401, False, "登录状态无效，请重新登录"),
    "invalid_credentials": ErrorInfo(401, False, "用户名或密码错误"),
    "invalid_refresh_token": ErrorInfo(401, False, "登录状态已失效，请重新登录"),
    "workspace_not_found": ErrorInfo(404, False, "工作区不存在"),
    "member_not_found": ErrorInfo(404, False, "成员不存在"),
    "account_not_found": ErrorInfo(404, False, "该账号尚未注册"),
    "snapshot_not_found": ErrorInfo(404, False, "找不到可用快照"),
    "username_taken": ErrorInfo(409, False, "该用户名已被占用"),
    "email_taken": ErrorInfo(409, False, "该邮箱已被占用"),
    "last_owner": ErrorInfo(409, False, "至少需要保留一个所有者"),
    "cannot_remove_self": ErrorInfo(409, False, "不能移除自己"),
    "restore_too_large": ErrorInfo(409, False, "恢复范围过大，请使用更近的快照"),
    "payload_too_large": ErrorInfo(413, False, "同步数据过大，请分批提交"),
    "rate_limited": ErrorInfo(429, True, "操作过于频繁，请稍后重试"),
    "internal_error": ErrorInfo(500, True, "服务端内部错误，请重试"),
    "not_implemented": ErrorInfo(501, False, "该功能尚未实现"),
    "service_unavailable": ErrorInfo(503, True, "服务暂不可用，请稍后重试"),
}


def info_for(code: str) -> ErrorInfo:
    return ERRORS.get(code, ERRORS["internal_error"])


def error_payload(
    code: str,
    *,
    message: str | None = None,
    retryable: bool | None = None,
    details: Any = None,
    server_hlc: Any = None,
) -> dict[str, Any]:
    info = info_for(code)
    body: dict[str, Any] = {
        "code": code,
        "message": message or info.message,
        "retryable": info.retryable if retryable is None else retryable,
    }
    if details is not None:
        body["details"] = details
    if server_hlc is not None:
        body["server_hlc"] = server_hlc
    return body


class AppError(Exception):
    """可映射为 HTTP 响应的业务异常。"""

    def __init__(
        self,
        code: str,
        *,
        message: str | None = None,
        status: int | None = None,
        retryable: bool | None = None,
        details: Any = None,
        server_hlc: Any = None,
    ) -> None:
        info = info_for(code)
        self.code = code
        self.message = message or info.message
        self.status = status if status is not None else info.status
        self.retryable = info.retryable if retryable is None else retryable
        self.details = details
        self.server_hlc = server_hlc
        super().__init__(self.message)

    def payload(self) -> dict[str, Any]:
        return error_payload(
            self.code,
            message=self.message,
            retryable=self.retryable,
            details=self.details,
            server_hlc=self.server_hlc,
        )


class OpRejected(Exception):
    """逐条 op 的拒绝原因（不中断整批）。"""

    def __init__(
        self,
        code: str,
        *,
        message: str | None = None,
        server_hlc: Any = None,
    ) -> None:
        self.code = code
        self.message = message or info_for(code).message
        self.server_hlc = server_hlc
        super().__init__(self.message)

    def payload(self) -> dict[str, Any]:
        return error_payload(self.code, message=self.message, server_hlc=self.server_hlc)
