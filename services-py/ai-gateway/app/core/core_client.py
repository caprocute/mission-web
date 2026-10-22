"""Client tới API lõi (mission-core) chỉ cho phép đọc. Mọi lời gọi mang token `ai_readonly` đã đổi; phương thức
ghi bị chặn ở hai lớp: API của lớp chỉ có GET, và móc sự kiện của httpx từ chối mọi yêu cầu khác GET, HEAD
nếu có ai gọi thẳng client bên dưới. Ghi luôn đi qua API nghiệp vụ dưới danh tính người dùng sau khi người
dùng xác nhận (TL-01 "Nguyên tắc")."""

from __future__ import annotations

from typing import Any

import httpx

from app.core.auth import Principal
from app.core.exchange import TokenExchanger

READ_METHODS = frozenset({"GET", "HEAD"})


class WriteForbiddenError(Exception):
    """ai-gateway không bao giờ ghi vào dữ liệu nghiệp vụ."""


async def _deny_writes(request: httpx.Request) -> None:
    if request.method.upper() not in READ_METHODS:
        raise WriteForbiddenError(f"ai-gateway không được gọi {request.method} tới API lõi")


def new_http(base_url: str, timeout_s: float, transport: httpx.AsyncBaseTransport | None = None) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=timeout_s,
        transport=transport,
        event_hooks={"request": [_deny_writes]},
        follow_redirects=False,
    )


class CoreClient:
    def __init__(self, http: httpx.AsyncClient, exchanger: TokenExchanger) -> None:
        self._http = http
        self._ex = exchanger

    async def get(self, principal: Principal, path: str, params: dict[str, Any] | None = None) -> httpx.Response:
        if not path.startswith("/api/v1/"):
            raise ValueError("đường dẫn API lõi phải bắt đầu bằng /api/v1/")
        tok = await self._ex.exchange(principal)
        return await self._http.get(path, params=params, headers={"Authorization": f"Bearer {tok.value}"})

    def __getattr__(self, name: str) -> Any:
        if name in ("post", "put", "patch", "delete", "request", "send", "stream"):
            raise WriteForbiddenError(f"CoreClient không có thao tác {name}")
        raise AttributeError(name)
