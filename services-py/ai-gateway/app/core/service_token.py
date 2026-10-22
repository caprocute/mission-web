"""Token của chính ai-gateway (client credentials) cho việc nền không có người dùng, như consumer EVT-24 đọc văn
bản chỉ đạo từ lõi. Token phải chỉ mang vai trò chỉ đọc `ai_readonly`, nếu không thì không dùng (AC-12)."""

from __future__ import annotations

import asyncio
import time

import httpx

from app.core.auth import AuthError, JwtVerifier, roles_of
from app.core.exchange import ExchangeError, ReadOnlyToken, only_role


class ServiceToken:
    def __init__(self, http: httpx.AsyncClient, verifier: JwtVerifier, *, token_url: str, client_id: str,
                 client_secret: str, audience: str, required_role: str) -> None:
        self._http = http
        self._verifier = verifier
        self._url = token_url
        self._id = client_id
        self._secret = client_secret
        self._audience = audience
        self._role = required_role
        self._tok: ReadOnlyToken | None = None
        self._lock = asyncio.Lock()

    async def get(self) -> ReadOnlyToken:
        async with self._lock:
            if self._tok and self._tok.expires_at - 30 > time.time():
                return self._tok
            try:
                r = await self._http.post(self._url, auth=(self._id, self._secret),
                                          data={"grant_type": "client_credentials", "audience": self._audience})
            except httpx.HTTPError as e:
                raise ExchangeError(type(e).__name__) from e
            if r.status_code != 200 or not isinstance(r.json().get("access_token"), str):
                raise ExchangeError(f"máy chủ cấp token trả {r.status_code}")
            tok = r.json()["access_token"]
            try:
                claims = await self._verifier.verify_claims(tok, self._audience or None)
            except AuthError as e:
                raise ExchangeError("token dịch vụ không hợp lệ") from e
            if not only_role(roles_of(claims), self._role):
                raise ExchangeError("token dịch vụ mang vai trò ngoài vai trò chỉ đọc")
            self._tok = ReadOnlyToken(tok, str(claims.get("sub", "")), float(claims["exp"]))
            return self._tok
