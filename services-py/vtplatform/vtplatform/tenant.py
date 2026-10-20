"""Multi-tenant JWT claim extraction, contextvars propagation, and ASGI/FastAPI middleware."""

from __future__ import annotations

import base64
from contextlib import contextmanager
import contextvars
import json
import re
from typing import Any, Callable, Dict, Iterator, Mapping, Optional, Sequence, Tuple

from .otel import current_trace_id
from .problem import ProblemDetails, ProblemException, forbidden, send_asgi_problem


DEFAULT_CLAIM_NAME = "tenant_id"
FALLBACK_CLAIM_NAMES: Tuple[str, ...] = ("tenant", "tid")

_UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

_TENANT_VAR: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("vtplatform_tenant_id", default=None)
_ACTOR_VAR: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("vtplatform_actor_id", default=None)
_CLAIMS_VAR: contextvars.ContextVar[Optional[Dict[str, Any]]] = contextvars.ContextVar(
    "vtplatform_jwt_claims", default=None
)


class MissingTenantError(LookupError):
    """Raised when no valid tenant_id is present in the active execution context."""


class InvalidTenantUUIDError(ValueError):
    """Raised when tenant_id is not a canonical 36-char UUID."""


def is_valid_uuid(value: Optional[str]) -> bool:
    if not isinstance(value, str):
        return False
    return bool(_UUID_RE.match(value.strip()))


def normalize_uuid(value: Optional[str]) -> str:
    if not isinstance(value, str):
        raise InvalidTenantUUIDError("tenant: tenant_id must be a valid UUID string")
    cleaned = value.strip().lower()
    if not _UUID_RE.match(cleaned):
        raise InvalidTenantUUIDError(f"tenant: invalid UUID format: {value!r}")
    return cleaned


def set_current_tenant_id(tenant_id: str) -> contextvars.Token[Optional[str]]:
    return _TENANT_VAR.set(normalize_uuid(tenant_id))


def reset_current_tenant_id(token: contextvars.Token[Optional[str]]) -> None:
    _TENANT_VAR.reset(token)


@contextmanager
def tenant_context(tenant_id: str, actor_id: Optional[str] = None) -> Iterator[str]:
    norm = normalize_uuid(tenant_id)
    t_tok = _TENANT_VAR.set(norm)
    a_tok = _ACTOR_VAR.set(actor_id.strip() if actor_id else None)
    try:
        yield norm
    finally:
        _ACTOR_VAR.reset(a_tok)
        _TENANT_VAR.reset(t_tok)


def get_optional_tenant_id() -> Optional[str]:
    val = _TENANT_VAR.get()
    if val and is_valid_uuid(val):
        return val
    return None


def must_get_tenant_id() -> str:
    val = get_optional_tenant_id()
    if not val:
        raise MissingTenantError("tenant: missing tenant_id in active context")
    return val


def get_optional_actor_id() -> Optional[str]:
    return _ACTOR_VAR.get()


def parse_verified_jwt_payload(raw_token: str) -> Dict[str, Any]:
    parts = (raw_token or "").strip().split(".")
    if len(parts) != 3 or not parts[0] or not parts[1]:
        raise ValueError("tenant: malformed JWT token")
    payload_b64 = parts[1]
    pad = "=" * ((4 - len(payload_b64) % 4) % 4)
    try:
        decoded = base64.urlsafe_b64decode(payload_b64 + pad)
        claims = json.loads(decoded.decode("utf-8"))
    except Exception as exc:
        raise ValueError(f"tenant: invalid JWT payload encoding: {exc}") from exc
    if not isinstance(claims, dict):
        raise ValueError("tenant: JWT payload must be a JSON object")
    return claims


def extract_tenant_from_claims(
    claims: Mapping[str, Any],
    claim_name: str = DEFAULT_CLAIM_NAME,
    fallback_claim_names: Sequence[str] = ("tenant", "tid"),
) -> str:
    if not claims:
        raise MissingTenantError("tenant: empty JWT claims")
    primary = (claim_name or DEFAULT_CLAIM_NAME).strip() or DEFAULT_CLAIM_NAME
    candidates = [primary] + [c.strip() for c in fallback_claim_names if c and c.strip() != primary]
    for name in candidates:
        if name in claims and claims[name] is not None:
            raw_val = claims[name]
            if not isinstance(raw_val, str) or not raw_val.strip():
                raise InvalidTenantUUIDError(f"tenant: claim {name!r} is not a non-empty string")
            return normalize_uuid(raw_val)
    raise MissingTenantError(f"tenant: claim {primary!r} not found in JWT")


class TenantMiddleware:
    """ASGI & FastAPI middleware extracting tenant_id from verified Bearer JWT or returning 403 Problem Details."""

    def __init__(
        self,
        app: Callable[..., Any],
        claim_name: str = DEFAULT_CLAIM_NAME,
        fallback_claim_names: Sequence[str] = ("tenant", "tid"),
        token_verifier: Optional[Callable[[str], Dict[str, Any]]] = None,
    ) -> None:
        self.app = app
        self.claim_name = (claim_name or DEFAULT_CLAIM_NAME).strip() or DEFAULT_CLAIM_NAME
        self.fallback_claim_names = tuple(fallback_claim_names)
        self.token_verifier = token_verifier

    def resolve_request_headers(self, headers: Mapping[str, str], path: str = "/") -> Tuple[str, Optional[str], Dict[str, Any]]:
        trace_id = current_trace_id()
        lower_headers = {k.lower(): v for k, v in headers.items()}
        auth_header = (lower_headers.get("authorization") or "").strip()
        if not auth_header.lower().startswith("bearer "):
            raise ProblemException(
                forbidden(
                    "Thiếu thông tin định danh đơn vị thuê bao (tenant_id) trong phiên xác thực.",
                    instance=path,
                    trace_id=trace_id,
                )
            )
        raw_token = auth_header[len("Bearer ") :].strip()
        try:
            if self.token_verifier is not None:
                claims = self.token_verifier(raw_token)
            else:
                claims = parse_verified_jwt_payload(raw_token)
        except Exception as exc:
            raise ProblemException(
                forbidden(
                    "Không thể trích xuất thông tin đơn vị thuê bao từ phiên xác thực.",
                    instance=path,
                    trace_id=trace_id,
                )
            ) from exc

        try:
            tenant_id = extract_tenant_from_claims(claims, self.claim_name, self.fallback_claim_names)
        except (MissingTenantError, InvalidTenantUUIDError) as exc:
            raise ProblemException(
                forbidden(
                    "Phiên xác thực không chứa định danh đơn vị thuê bao (tenant_id) hợp lệ.",
                    instance=path,
                    trace_id=trace_id,
                )
            ) from exc

        sub = claims.get("sub")
        actor_id = sub.strip() if isinstance(sub, str) and sub.strip() else None
        return tenant_id, actor_id, claims

    async def __call__(self, scope: Dict[str, Any], receive: Callable[..., Any], send: Callable[..., Any]) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "/")
        raw_headers = {
            k.decode("latin1").lower(): v.decode("latin1")
            for k, v in scope.get("headers", [])
        }
        try:
            tenant_id, actor_id, claims = self.resolve_request_headers(raw_headers, path=path)
        except ProblemException as exc:
            await send_asgi_problem(send, exc.problem)
            return

        c_tok = _CLAIMS_VAR.set(claims)
        try:
            with tenant_context(tenant_id, actor_id=actor_id):
                await self.app(scope, receive, send)
        finally:
            _CLAIMS_VAR.reset(c_tok)


def fastapi_tenant_dependency(
    authorization: Optional[str] = None,
    claim_name: str = DEFAULT_CLAIM_NAME,
    path: str = "/",
) -> str:
    """FastAPI Depends() compatible callable returning validated tenant_id or raising ProblemException(403)."""
    existing = get_optional_tenant_id()
    if existing:
        return existing
    mw = TenantMiddleware(app=lambda *a, **kw: None, claim_name=claim_name)
    headers = {"authorization": authorization} if authorization else {}
    tenant_id, _, _ = mw.resolve_request_headers(headers, path=path)
    return tenant_id
