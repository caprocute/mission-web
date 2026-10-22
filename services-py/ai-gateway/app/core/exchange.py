"""Bước 2: đổi token người dùng (RFC 8693) sang token của client chỉ đọc mang vai trò `ai_readonly` và giữ danh
tính người dùng, để AI chỉ đọc được những gì người dùng đọc được (TL-01 "Kiến trúc cổng mô hình", AC-12)."""

from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass, field

import httpx

from app.core.auth import AuthError, JwtVerifier, Principal, roles_of

GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token"  # noqa: S105 - tên loại token, không phải bí mật


_IGNORED_ROLES = ("default-roles", "offline_access", "uma_authorization")


def only_role(roles: tuple[str, ...], role: str) -> bool:
    """Token mang vai trò `role` và không mang vai trò nghiệp vụ nào khác (bỏ qua vai trò mặc định của Keycloak)."""
    return role in roles and all(r == role or r.startswith(_IGNORED_ROLES) for r in roles)


class ExchangeError(Exception):
    """Không đổi được token; lời gọi tới API lõi không được thực hiện."""


@dataclass(frozen=True)
class ReadOnlyToken:
    value: str = field(repr=False)
    user_id: str
    expires_at: float


class TokenExchanger:
    """Đệm token đã đổi theo băm của token gốc tới 30 giây trước khi hết hạn."""

    def __init__(
        self,
        http: httpx.AsyncClient,
        verifier: JwtVerifier,
        *,
        token_url: str,
        client_id: str,
        client_secret: str,
        audience: str,
        scope: str,
        required_role: str,
    ) -> None:
        self._http = http
        self._verifier = verifier
        self._url = token_url
        self._client_id = client_id
        self._secret = client_secret
        self._audience = audience
        self._scope = scope
        self._role = required_role
        self._cache: dict[bytes, ReadOnlyToken] = {}
        self._lock = asyncio.Lock()

    async def exchange(self, principal: Principal) -> ReadOnlyToken:
        key = hashlib.sha256(principal.token.encode()).digest()
        hit = self._cache.get(key)
        if hit and hit.expires_at - 30 > time.time():
            return hit
        try:
            r = await self._http.post(
                self._url,
                data={
                    "grant_type": GRANT,
                    "subject_token": principal.token,
                    "subject_token_type": ACCESS_TOKEN,
                    "requested_token_type": ACCESS_TOKEN,
                    "audience": self._audience,
                    "scope": self._scope,
                },
                auth=(self._client_id, self._secret),
            )
        except httpx.HTTPError as e:
            raise ExchangeError(type(e).__name__) from e
        if r.status_code != 200:
            raise ExchangeError(f"máy chủ cấp token trả {r.status_code}")
        tok = r.json().get("access_token")
        if not isinstance(tok, str):
            raise ExchangeError("thiếu access_token")
        try:
            claims = await self._verifier.verify_claims(tok, self._audience or None)
        except AuthError as e:
            raise ExchangeError("token đổi về không hợp lệ") from e
        # Token đổi về phải là của cùng người dùng và chỉ mang vai trò chỉ đọc.
        if claims.get("sub") != principal.user_id or claims.get("tenant_id") != principal.tenant_id:
            raise ExchangeError("token đổi về không cùng danh tính")
        if not only_role(roles_of(claims), self._role):
            raise ExchangeError("token đổi về mang vai trò ngoài vai trò chỉ đọc")
        out = ReadOnlyToken(tok, principal.user_id, float(claims["exp"]))
        async with self._lock:
            if len(self._cache) > 10_000:
                now = time.time()
                self._cache = {k: v for k, v in self._cache.items() if v.expires_at > now}
            self._cache[key] = out
        return out
