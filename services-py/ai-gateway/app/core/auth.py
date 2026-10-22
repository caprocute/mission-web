"""Bước 1 của mọi yêu cầu (TL-01 "Kiến trúc cổng mô hình"): kiểm chữ ký JWT người dùng qua JWKS Keycloak và
dựng chủ thể theo cùng cách đọc claim với AuthzSubjectResolver của vt-platform-starter."""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import jwt
from vtplatform.tenant import is_valid_uuid, normalize_uuid

_ALGS = ["RS256", "PS256", "ES256"]


class AuthError(Exception):
    """Token không hợp lệ; chi tiết chỉ ghi nhật ký, không trả cho client."""


@dataclass(frozen=True)
class Principal:
    user_id: str
    tenant_id: str
    roles: tuple[str, ...]
    token: str = field(repr=False)
    unit_id: str | None = None
    acr: str | None = None
    auth_time: int | None = None
    flows: tuple[str, ...] = ("DANG", "NHA_NUOC")
    client_id: str | None = None

    def opa_subject(self) -> dict[str, Any]:
        s: dict[str, Any] = {
            "kind": "user",
            "user_id": self.user_id,
            "tenant_id": self.tenant_id,
            "roles": list(self.roles),
            "flows": list(self.flows),
        }
        if self.unit_id:
            s["unit_id"] = self.unit_id
        if self.acr:
            s["acr"] = self.acr
        if self.auth_time is not None:
            s["auth_time"] = self.auth_time
        return s


def roles_of(claims: dict[str, Any]) -> tuple[str, ...]:
    out: list[str] = []
    ra = claims.get("realm_access")
    if isinstance(ra, dict) and isinstance(ra.get("roles"), list):
        out += [r for r in ra["roles"] if isinstance(r, str)]
    if isinstance(claims.get("roles"), list):
        out += [r for r in claims["roles"] if isinstance(r, str) and r not in out]
    return tuple(out)


def normalize_acr(acr: Any) -> str | None:
    if not isinstance(acr, str) or not acr.strip():
        return None
    return "aal2" if acr.lower() in ("aal2", "urn:viettel:aal2") else acr


def principal_from_claims(claims: dict[str, Any], token: str) -> Principal:
    sub, tenant = claims.get("sub"), claims.get("tenant_id")
    if not isinstance(sub, str) or not is_valid_uuid(sub):
        raise AuthError("thiếu hoặc sai claim sub")
    if not isinstance(tenant, str) or not is_valid_uuid(tenant):
        raise AuthError("thiếu hoặc sai claim tenant_id")
    unit = claims.get("unit_id") or claims.get("active_unit_id")
    at = claims.get("auth_time")
    flows = claims.get("flows")
    flow_list = tuple(f for f in flows if isinstance(f, str)) if isinstance(flows, list) else ()
    return Principal(
        user_id=normalize_uuid(sub),
        tenant_id=normalize_uuid(tenant),
        roles=roles_of(claims),
        token=token,
        unit_id=unit if isinstance(unit, str) else None,
        acr=normalize_acr(claims.get("acr")),
        auth_time=int(at) if isinstance(at, int | float) else None,
        flows=flow_list or ("DANG", "NHA_NUOC"),
        client_id=claims.get("azp") if isinstance(claims.get("azp"), str) else None,
    )


class JwtVerifier:
    """Kiểm chữ ký, issuer, hạn và audience (nếu cấu hình). Bộ khoá JWKS được đệm, làm mới theo chu kỳ và khi
    gặp kid lạ (tối đa một lần mỗi 10 giây để token rác không làm dội máy chủ cấp token)."""

    def __init__(self, http: httpx.AsyncClient, jwks_url: str, issuer: str, audience: str = "",
                 leeway_s: int = 30, ttl_s: float = 300) -> None:
        self._http = http
        self._url = jwks_url
        self._issuer = issuer
        self._audience = audience or None
        self._leeway = leeway_s
        self._ttl = ttl_s
        self._keys: dict[str, Any] = {}
        self._fetched = 0.0
        self._lock = asyncio.Lock()

    async def _refresh(self, force: bool) -> None:
        async with self._lock:
            age = time.monotonic() - self._fetched
            if (not force and age < self._ttl) or (force and age < 10):
                return
            try:
                r = await self._http.get(self._url)
                r.raise_for_status()
                ks = jwt.PyJWKSet.from_dict(r.json())
            except (httpx.HTTPError, ValueError, jwt.PyJWTError) as e:
                raise AuthError("không tải được JWKS") from e
            self._keys = {k.key_id: k.key for k in ks.keys if k.key_id}
            self._fetched = time.monotonic()

    async def _key(self, token: str) -> Any:
        try:
            kid = jwt.get_unverified_header(token).get("kid")
        except jwt.PyJWTError as e:
            raise AuthError("token sai định dạng") from e
        if not isinstance(kid, str):
            raise AuthError("thiếu kid")
        await self._refresh(force=False)
        if kid not in self._keys:
            await self._refresh(force=True)
        if kid not in self._keys:
            raise AuthError("kid không có trong JWKS")
        return self._keys[kid]

    async def verify_claims(self, token: str, audience: str | None = None) -> dict[str, Any]:
        key = await self._key(token)
        try:
            claims: dict[str, Any] = jwt.decode(
                token, key, algorithms=_ALGS, issuer=self._issuer, audience=audience, leeway=self._leeway,
                options={"require": ["exp", "iat", "sub"], "verify_aud": audience is not None},
            )
        except jwt.PyJWTError as e:
            raise AuthError(type(e).__name__) from e
        return claims

    async def verify(self, token: str) -> Principal:
        return principal_from_claims(await self.verify_claims(token, self._audience), token)
