"""Khoá ký, token và máy chủ cấp token giả cho kiểm thử (không dùng khoá thật)."""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import Callable
from typing import Any

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

ISSUER = "https://idp.test/realms/mission"
JWKS_URL = ISSUER + "/protocol/openid-connect/certs"
TOKEN_URL = ISSUER + "/protocol/openid-connect/token"
TENANT_A = "0192f000-0000-7000-8000-00000000000a"
TENANT_B = "0192f000-0000-7000-8000-00000000000b"
USER_1 = "0192f000-0000-7000-8000-000000000001"
USER_2 = "0192f000-0000-7000-8000-000000000002"
SERVICE = "0192f000-0000-7000-8000-0000000000ff"

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_KID = "test-key"


def jwks() -> dict[str, Any]:
    pub = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(_KEY.public_key()))
    pub.update({"kid": _KID, "use": "sig", "alg": "RS256"})
    return {"keys": [pub]}


def token(user: str = USER_1, tenant: str = TENANT_A, roles: tuple[str, ...] = ("member",), **extra: Any) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {"iss": ISSUER, "sub": user, "tenant_id": tenant, "iat": now, "exp": now + 300,
                              "realm_access": {"roles": list(roles)}, "azp": "mission-web", "jti": str(uuid.uuid4())}
    claims.update(extra)
    return jwt.encode(claims, _KEY, algorithm="RS256", headers={"kid": _KID})


class FakeIdp:
    """JWKS và token exchange. `issue` quyết định token trả về từ token gốc; mặc định giữ danh tính, chỉ mang
    vai trò ai_readonly và audience mission-core như cấu hình client của DV-02-01."""

    def __init__(self) -> None:
        self.exchanges: list[dict[str, str]] = []
        self.issue: Callable[[dict[str, Any]], str | None] = lambda c: token(
            c["sub"], c["tenant_id"], ("ai_readonly", "default-roles-mission"), aud="mission-core", azp="ai-gateway")

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url == JWKS_URL:
            return httpx.Response(200, json=jwks())
        if request.url == TOKEN_URL and request.method == "POST":
            form = dict(httpx.QueryParams(request.content.decode()))
            form["authorization"] = request.headers.get("authorization", "")
            self.exchanges.append(form)
            if form.get("grant_type") == "client_credentials":
                svc = token(SERVICE, TENANT_A, ("ai_readonly",), aud="mission-core", azp="ai-gateway")
                return httpx.Response(200, json={"access_token": svc, "token_type": "Bearer", "expires_in": 300})
            subject = jwt.decode(form["subject_token"], options={"verify_signature": False})
            out = self.issue(subject)
            if out is None:
                return httpx.Response(403, json={"error": "access_denied"})
            return httpx.Response(200, json={"access_token": out, "token_type": "Bearer", "expires_in": 300})
        return httpx.Response(404)


class Router(httpx.AsyncBaseTransport):
    """Định tuyến theo host: host có trong `fakes` đi tới hàm giả, còn lại đi mạng thật (OPA trong container)."""

    def __init__(self, fakes: dict[str, Callable[[httpx.Request], httpx.Response]]) -> None:
        self.fakes = fakes
        self.real = httpx.AsyncHTTPTransport()
        self.seen: list[httpx.Request] = []

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        fake = self.fakes.get(request.url.host)
        if fake is not None:
            await request.aread()
            return fake(request)
        return await self.real.handle_async_request(request)
