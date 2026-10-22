"""Khoá chống lặp `Idempotency-Key` cho API POST của trợ lý (baseline mục 13, TL-03 "Bảng idempotency_key"): giữ
48 giờ trong phạm vi (tenant, người gọi); cùng khoá cùng nội dung trả lại phản hồi lần đầu, khác nội dung trả 422,
lần đầu còn đang xử lý trả 409. Gợi ý AI không ghi gì vào schema nghiệp vụ nên khoá được ghi ở giao dịch riêng
trước và sau khi gọi mô hình, không bọc cả lượt suy luận trong một giao dịch."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from vtplatform.db import in_tenant_tx

KEEP = timedelta(hours=48)


class IdempotencyMismatch(Exception):
    """Cùng khoá nhưng nội dung yêu cầu khác."""


class IdempotencyInProgress(Exception):
    """Lần gọi đầu với khoá này chưa xong."""


@dataclass(frozen=True)
class Stored:
    code: int
    body: Any
    location: str | None


def request_hash(method: str, path: str, body: Any) -> bytes:
    canon = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(f"{method.upper()} {path}\n{canon}".encode()).digest()


class IdempotencyStore:
    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def begin(self, tenant_id: str, actor_id: str, key: str, digest: bytes) -> Stored | None:
        """None: lần đầu, đã giữ khoá. Stored: phản hồi đã lưu của lần đầu."""
        now = datetime.now(UTC)
        t, a = uuid.UUID(tenant_id), uuid.UUID(actor_id)
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            await tx.execute("SELECT ai.fn_ensure_day_partitions($1::date, 1)", now.date())
            rows = await tx.fetch(
                """SELECT request_hash, status, response_code, response_body, resource_ref, created_at
                     FROM ai.idempotency_key
                    WHERE tenant_id = $1 AND actor_id = $2 AND idem_key = $3 AND created_day >= $4
                    ORDER BY created_at DESC LIMIT 1""",
                t, a, key, (now - KEEP).date())
            if rows and rows[0]["created_at"] >= now - KEEP:
                r = rows[0]
                if bytes(r["request_hash"]) != digest:
                    raise IdempotencyMismatch
                if r["status"] != "XONG":
                    raise IdempotencyInProgress
                body = r["response_body"]
                return Stored(int(r["response_code"]), json.loads(body) if isinstance(body, str) else body,
                              r["resource_ref"])
            await tx.execute(
                """INSERT INTO ai.idempotency_key (tenant_id, actor_id, idem_key, created_day, request_hash, status,
                       created_at) VALUES ($1, $2, $3, $4, $5, 'DANG_XU_LY', $6)
                   ON CONFLICT (tenant_id, actor_id, idem_key, created_day) DO NOTHING""",
                t, a, key, now.date(), digest, now)
        return None

    async def finish(self, tenant_id: str, actor_id: str, key: str, code: int, body: Any,
                     location: str | None) -> None:
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            await tx.execute(
                """UPDATE ai.idempotency_key SET status = 'XONG', response_code = $4, response_body = $5::jsonb,
                          resource_ref = $6
                    WHERE tenant_id = $1 AND actor_id = $2 AND idem_key = $3 AND status = 'DANG_XU_LY'""",
                uuid.UUID(tenant_id), uuid.UUID(actor_id), key, code, json.dumps(body, ensure_ascii=False), location)

    async def release(self, tenant_id: str, actor_id: str, key: str) -> None:
        """Lần đầu lỗi: bỏ khoá để client thử lại cùng khoá."""
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            await tx.execute(
                """DELETE FROM ai.idempotency_key
                    WHERE tenant_id = $1 AND actor_id = $2 AND idem_key = $3 AND status = 'DANG_XU_LY'""",
                uuid.UUID(tenant_id), uuid.UUID(actor_id), key)
