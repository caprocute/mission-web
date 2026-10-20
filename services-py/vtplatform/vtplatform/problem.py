"""RFC 9457 Problem Details (application/problem+json) and canonical error catalog (NEN-009)."""

from __future__ import annotations

from dataclasses import dataclass, field
import json
import pathlib
import re
from typing import Any, Callable, Dict, List, Optional, Tuple


PROBLEM_MEDIA_TYPE = "application/problem+json"
PROBLEM_BASE_URI = "https://okrkpi.vn/problems/"

CODE_BAD_REQUEST = "COMMON-400-BAD-REQUEST"
CODE_UNAUTHORIZED = "COMMON-401-UNAUTHORIZED"
CODE_FORBIDDEN = "COMMON-403-FORBIDDEN"
CODE_NOT_FOUND = "COMMON-404-NOT-FOUND"
CODE_CONFLICT = "COMMON-409-CONFLICT"
CODE_UNPROCESSABLE = "COMMON-422-UNPROCESSABLE"
CODE_INTERNAL_ERROR = "COMMON-500-INTERNAL-ERROR"

_SQL_SENSITIVE_PATTERN = re.compile(
    r"(?is)("
    r"\b(?:select|insert\s+into|update|delete\s+from|alter\s+table|drop\s+table|create\s+table)\b"
    r"|\b(?:relation|column|constraint|table|schema|index)\s+\"[^\"]+\""
    r"|\bSQLSTATE\s*\(?[0-9A-Z]{5}\)?"
    r"|\bduplicate\s+key\s+value\s+violates\s+unique\s+constraint\b"
    r"|\bviolates\s+(?:foreign\s+key|check|not-null|row-level\s+security)\b"
    r"|\bpg_[a-z0-9_]+\b"
    r"|\bTraceback\s+\(most\s+recent\s+call\s+last\):"
    r"|\bFile\s+\"[^\"]+\",\s+line\s+\d+"
    r")"
)


@dataclass(frozen=True)
class ErrorCatalogEntry:
    code: str
    http_status: int
    slug: str
    default_title_vi: str
    msg: Optional[str] = None
    rule: Optional[str] = None

    @property
    def type_uri(self) -> str:
        return f"{PROBLEM_BASE_URI}{self.slug}"


@dataclass(frozen=True)
class FieldViolation:
    field: str
    code: str
    message: str

    def to_dict(self) -> Dict[str, str]:
        return {
            "field": self.field,
            "code": self.code,
            "message": self.message,
        }


@dataclass
class ProblemDetails:
    type: str
    title: str
    status: int
    detail: str
    instance: str = ""
    code: str = CODE_INTERNAL_ERROR
    trace_id: str = ""
    errors: List[FieldViolation] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "type": self.type,
            "title": self.title,
            "status": self.status,
            "detail": sanitize_detail(self.detail, self.title),
            "instance": self.instance or "/",
            "code": self.code,
        }
        if self.trace_id:
            payload["traceId"] = self.trace_id
        if self.errors:
            payload["errors"] = [e.to_dict() for e in self.errors]
        return payload

    def to_json_bytes(self) -> bytes:
        return json.dumps(self.to_dict(), ensure_ascii=False).encode("utf-8")


class ProblemException(Exception):
    """Exception carrying an RFC 9457 ProblemDetails payload."""

    def __init__(self, problem: ProblemDetails) -> None:
        super().__init__(problem.detail or problem.title)
        self.problem = problem


def _load_catalog() -> Dict[str, ErrorCatalogEntry]:
    here = pathlib.Path(__file__).resolve().parent
    catalog_path = here / "error-codes.json"
    if not catalog_path.is_file():
        from .sync_error_codes import sync_error_codes

        catalog_path = sync_error_codes()
    raw = json.loads(catalog_path.read_text(encoding="utf-8"))
    base_uri = raw.get("problemBaseUri", PROBLEM_BASE_URI)
    entries: Dict[str, ErrorCatalogEntry] = {}
    for item in raw.get("codes", []):
        code = str(item["code"]).strip()
        slug = str(item.get("slug") or code.lower()).strip()
        if base_uri and slug.startswith(base_uri):
            slug = slug[len(base_uri) :]
        entries[code] = ErrorCatalogEntry(
            code=code,
            http_status=int(item["httpStatus"]),
            slug=slug,
            default_title_vi=str(item["defaultTitleVi"]),
            msg=item.get("msg"),
            rule=item.get("rule"),
        )
    return entries


_CATALOG: Dict[str, ErrorCatalogEntry] = _load_catalog()


def catalog_size() -> int:
    return len(_CATALOG)


def lookup_code(code: str) -> Optional[ErrorCatalogEntry]:
    return _CATALOG.get((code or "").strip())


def sanitize_detail(detail: Optional[str], fallback_title: str = "Đã xảy ra lỗi khi xử lý yêu cầu.") -> str:
    cleaned = (detail or "").strip()
    if not cleaned:
        return fallback_title
    if _SQL_SENSITIVE_PATTERN.search(cleaned):
        return fallback_title
    return cleaned


def from_code(
    code: str,
    detail: Optional[str] = None,
    instance: str = "/",
    trace_id: str = "",
    errors: Optional[List[FieldViolation]] = None,
) -> ProblemDetails:
    entry = lookup_code(code)
    if entry is None:
        entry = lookup_code(CODE_INTERNAL_ERROR) or ErrorCatalogEntry(
            code=CODE_INTERNAL_ERROR,
            http_status=500,
            slug="common-500-internal-error",
            default_title_vi="Lỗi hệ thống nội bộ",
        )
    safe_detail = sanitize_detail(detail, entry.default_title_vi)
    return ProblemDetails(
        type=entry.type_uri,
        title=entry.default_title_vi,
        status=entry.http_status,
        detail=safe_detail,
        instance=instance or "/",
        code=entry.code,
        trace_id=trace_id,
        errors=list(errors or []),
    )


def forbidden(detail: str, instance: str = "/", trace_id: str = "") -> ProblemDetails:
    return from_code(CODE_FORBIDDEN, detail=detail, instance=instance, trace_id=trace_id)


def bad_request(
    detail: str,
    instance: str = "/",
    trace_id: str = "",
    errors: Optional[List[FieldViolation]] = None,
) -> ProblemDetails:
    return from_code(CODE_BAD_REQUEST, detail=detail, instance=instance, trace_id=trace_id, errors=errors)


def internal_error(detail: str = "", instance: str = "/", trace_id: str = "") -> ProblemDetails:
    return from_code(CODE_INTERNAL_ERROR, detail=detail, instance=instance, trace_id=trace_id)


async def send_asgi_problem(send: Callable[[Dict[str, Any]], Any], problem: ProblemDetails) -> None:
    body = problem.to_json_bytes()
    headers: List[Tuple[bytes, bytes]] = [
        (b"content-type", f"{PROBLEM_MEDIA_TYPE}; charset=utf-8".encode("ascii")),
        (b"content-length", str(len(body)).encode("ascii")),
        (b"cache-control", b"no-store"),
    ]
    if problem.trace_id:
        headers.append((b"x-trace-id", problem.trace_id.encode("ascii")))
    await send(
        {
            "type": "http.response.start",
            "status": problem.status,
            "headers": headers,
        }
    )
    await send(
        {
            "type": "http.response.body",
            "body": body,
        }
    )
