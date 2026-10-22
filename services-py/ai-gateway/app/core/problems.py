"""Phản hồi lỗi RFC 9457 của ai-gateway trên thư viện vtplatform.problem (NEN-018)."""

from __future__ import annotations

from fastapi import Request
from fastapi.responses import Response
from vtplatform import problem as vp
from vtplatform.otel import current_trace_id

# Hai mã chung chưa có trong danh mục error-codes.json của thư viện Python (suy luận, ghi ở wiki/14).
CODE_TOO_MANY = "COMMON-429-TOO-MANY-REQUESTS"
CODE_UNAVAILABLE = "COMMON-503-UNAVAILABLE"
# Mã của hợp đồng ocrEvidenceObject chưa có trong error-codes.json (suy luận, ghi ở wiki/14).
CODE_C3 = "AI-403-C3-FORBIDDEN"

_FALLBACK = {
    CODE_TOO_MANY: (429, "Có quá nhiều yêu cầu, đề nghị thử lại sau ít phút."),
    CODE_UNAVAILABLE: (503, "Dịch vụ tạm thời gián đoạn, đề nghị thử lại sau."),
    CODE_C3: (403, "Trợ lý không xử lý tài liệu mật."),
}


class ApiError(Exception):
    """Lỗi mang sẵn ProblemDetails và tiêu đề phản hồi bổ sung."""

    def __init__(self, problem: vp.ProblemDetails, headers: dict[str, str] | None = None) -> None:
        super().__init__(problem.detail)
        self.problem = problem
        self.headers = headers or {}


def make(code: str, detail: str, instance: str = "/") -> vp.ProblemDetails:
    if vp.lookup_code(code) is None and code in _FALLBACK:
        status, title = _FALLBACK[code]
        return vp.ProblemDetails(
            type=vp.PROBLEM_BASE_URI + code.lower(),
            title=title,
            status=status,
            detail=vp.sanitize_detail(detail, title),
            instance=instance,
            code=code,
            trace_id=current_trace_id(),
        )
    return vp.from_code(code, detail=detail, instance=instance, trace_id=current_trace_id())


def unauthorized(instance: str) -> ApiError:
    return ApiError(
        make(vp.CODE_UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.", instance),
        {"WWW-Authenticate": 'Bearer error="invalid_token"'},
    )


def forbidden(instance: str) -> ApiError:
    return ApiError(make(vp.CODE_FORBIDDEN, "Bạn không có quyền thực hiện thao tác này.", instance))


def unavailable(instance: str, retry_after: int = 5) -> ApiError:
    return ApiError(make(CODE_UNAVAILABLE, "", instance), {"Retry-After": str(retry_after)})


def too_many(instance: str, retry_after: int = 10) -> ApiError:
    return ApiError(make(CODE_TOO_MANY, "", instance), {"Retry-After": str(retry_after)})


def render(p: vp.ProblemDetails, headers: dict[str, str] | None = None) -> Response:
    h = {"Cache-Control": "no-store", **(headers or {})}
    if p.trace_id:
        h["X-Trace-Id"] = p.trace_id
    return Response(p.to_json_bytes(), status_code=p.status, media_type=vp.PROBLEM_MEDIA_TYPE, headers=h)


async def handle_api_error(_: Request, exc: Exception) -> Response:
    if not isinstance(exc, ApiError):
        raise exc
    return render(exc.problem, exc.headers)
