"""Client tới API lõi (mission-core) chỉ cho phép đọc. Mọi lời gọi mang token `ai_readonly` đã đổi; phương thức
ghi bị chặn ở hai lớp: API của lớp chỉ có GET, và móc sự kiện của httpx từ chối mọi yêu cầu khác GET, HEAD
nếu có ai gọi thẳng client bên dưới. Ghi luôn đi qua API nghiệp vụ dưới danh tính người dùng sau khi người
dùng xác nhận (TL-01 "Nguyên tắc")."""

from __future__ import annotations

import json
import pathlib
import uuid
from typing import Any

import httpx

from app.core.auth import Principal
from app.core.exchange import ReadOnlyToken, TokenExchanger

READ_METHODS = frozenset({"GET", "HEAD"})
# Thao tác đọc mà hợp đồng định nghĩa bằng POST vì thân truy vấn phức tạp (queryAnalytics, BR-16-02).
READ_POSTS = frozenset({"/api/v1/analytics/query"})


class WriteForbiddenError(Exception):
    """ai-gateway không bao giờ ghi vào dữ liệu nghiệp vụ."""


class C3ForbiddenError(Exception):
    """Dữ liệu C3 không bao giờ vào ngữ cảnh của trợ lý (BR-17-07, TL-01 "Giới hạn tìm kiếm, phân tích và AI")."""


MASK = "[ĐÃ CHE]"
# Tên thuộc tính mang x-classification C2, C3 trong hợp đồng, sinh bằng app.guard.sync_c2_fields.
C2_FIELDS: frozenset[str] = frozenset(
    json.loads((pathlib.Path(__file__).resolve().parents[1] / "guard" / "c2_fields.json").read_text("utf-8")))


def sanitize(data: Any, allow_c2: bool = False) -> Any:
    """Ném C3ForbiddenError nếu gặp đối tượng `classification` C3; che giá trị các trường C2 trừ khi allow_c2."""
    if isinstance(data, dict):
        if data.get("classification") == "C3" or data.get("dataClass") == "C3":
            raise C3ForbiddenError
        return {k: (MASK if not allow_c2 and k in C2_FIELDS and v is not None else sanitize(v, allow_c2))
                for k, v in data.items()}
    if isinstance(data, list):
        return [sanitize(v, allow_c2) for v in data]
    return data


async def _deny_writes(request: httpx.Request) -> None:
    if request.method.upper() == "POST" and request.url.path in READ_POSTS:
        return
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
        tok = await self._ex.exchange(principal)
        return await self.get_with(tok, path, params)

    async def get_with(self, tok: ReadOnlyToken, path: str, params: dict[str, Any] | None = None) -> httpx.Response:
        """Đọc bằng một token chỉ đọc đã có (token đổi của người dùng hoặc token dịch vụ ai_readonly)."""
        if not path.startswith("/api/v1/"):
            raise ValueError("đường dẫn API lõi phải bắt đầu bằng /api/v1/")
        return await self._http.get(path, params=params, headers={"Authorization": f"Bearer {tok.value}"})

    async def get_json_with(self, tok: ReadOnlyToken, path: str, params: dict[str, Any] | None = None) -> Any:
        r = await self.get_with(tok, path, params)
        r.raise_for_status()
        return sanitize(r.json())

    async def get_json(self, principal: Principal, path: str, params: dict[str, Any] | None = None, *,
                       allow_c2: bool = False) -> Any:
        """Đọc JSON từ lõi để đưa vào ngữ cảnh: lỗi HTTP ném ra, dữ liệu C3 bị từ chối, trường C2 bị che."""
        r = await self.get(principal, path, params)
        r.raise_for_status()
        return sanitize(r.json(), allow_c2)

    async def query_analytics(self, tok: ReadOnlyToken, query: dict[str, Any]) -> Any:
        """queryAnalytics trên lớp ngữ nghĩa: truy vấn đọc dùng POST, analytics-api áp phạm vi của chủ token."""
        r = await self._http.post("/api/v1/analytics/query", json=query, headers={
            "Authorization": f"Bearer {tok.value}", "Idempotency-Key": str(uuid.uuid4())})
        r.raise_for_status()
        return sanitize(r.json())

    async def download(self, principal: Principal, object_id: str, max_bytes: int) -> tuple[str, bytes]:
        """Tải tệp người dùng đính kèm: xin URL ký trước qua lõi (lõi kiểm quyền, ghi nhật ký), rồi tải không kèm
        token. Trả (tên tệp suy từ kiểu nội dung, nội dung)."""
        meta = await self.get_json(principal, f"/api/v1/files/{object_id}/download-url")
        if int(meta.get("sizeBytes") or 0) > max_bytes:
            raise ValueError("tệp vượt giới hạn")
        ext = {"application/pdf": ".pdf"}.get(str(meta.get("mediaType")), ".docx")
        return f"{object_id}{ext}", await self.fetch_signed(str(meta["url"]), max_bytes)

    async def fetch_signed(self, url: str, max_bytes: int) -> bytes:
        """Tải từ URL ký trước của lõi, không kèm token người dùng."""
        r = await self._http.get(url, headers={"Authorization": ""})
        r.raise_for_status()
        if len(r.content) > max_bytes:
            raise ValueError("tệp vượt giới hạn")
        return r.content

    def __getattr__(self, name: str) -> Any:
        if name in ("post", "put", "patch", "delete", "request", "send", "stream"):
            raise WriteForbiddenError(f"CoreClient không có thao tác {name}")
        raise AttributeError(name)
