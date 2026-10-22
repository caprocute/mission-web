"""Ghi và đọc `ai.suggestion_log`, `ai.guardrail_event` (TL-03 "Schema ai"). Lời nhắc chỉ lưu bản đã che dữ liệu
cá nhân và băm SHA-256 của bản gốc; không lưu C2 (BR-17-07)."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

from vtplatform.db import in_tenant_tx
from vtplatform.logging import scrub_text

LABEL = "Nội dung do AI tạo"


def redact(text: str) -> str:
    """Che số định danh, điện thoại, email và token trước khi lưu (lớp rào chắn đầy đủ thuộc DV-14-03)."""
    return scrub_text(text)


def prompt_digest(prompt: str) -> bytes:
    return hashlib.sha256(prompt.encode("utf-8")).digest()


@dataclass(frozen=True)
class Block:
    rule_code: str  # PII_OUT, PROMPT_INJECTION, OUT_OF_SCOPE, C2_REQUEST
    action: str  # CHAN, CHE, CANH_BAO
    message: str
    msg: str = "MSG-17-02"
    detail: dict[str, Any] | None = None

    def to_api(self) -> dict[str, str]:
        return {"ruleCode": self.rule_code, "action": self.action, "msg": self.msg, "message": self.message}


@dataclass
class Entry:
    tenant_id: str
    user_id: str
    use_case: str
    model: str
    prompt_template_version: str
    prompt: str = field(repr=False)
    output_text: str | None
    chunk_ids: list[str] = field(default_factory=list)
    sources: list[dict[str, Any]] = field(default_factory=list)
    kind: str | None = None
    target_ref: str | None = None
    confidence: float | None = None
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))


def to_api(row: Any) -> dict[str, Any]:
    sources = row["sources_json"]
    if isinstance(sources, str):
        sources = json.loads(sources)
    out: dict[str, Any] = {
        "id": str(row["id"]),
        "useCase": row["use_case"],
        "kind": row["kind"],
        "model": row["model"],
        "createdAt": row["created_at"].isoformat(),
        "aiGenerated": True,
        "label": LABEL,
        "outputText": row["output_text"],
        "sources": sources or [],
        "confidence": float(row["confidence"]) if row["confidence"] is not None else None,
        "decision": row["decision"] or row["user_action"],
    }
    return out


class SuggestionStore:
    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def ensure_partitions(self, tenant_id: str, day: date, days: int = 8) -> None:
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            await tx.execute("SELECT ai.fn_ensure_day_partitions($1::date, $2)", day, days)

    async def log(self, e: Entry, blocks: list[Block] | None = None) -> None:
        day = e.created_at.date()
        async with in_tenant_tx(self._pool, e.tenant_id) as tx:
            await tx.execute("SELECT ai.fn_ensure_day_partitions($1::date, 1)", day)
            await tx.execute(
                """INSERT INTO ai.suggestion_log (created_day, id, tenant_id, user_id, use_case, model,
                       prompt_template_version, prompt_sha256, chunk_ids, output_text, kind, target_ref,
                       prompt_redacted, sources_json, confidence, created_at)
                   VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::uuid[], $10, $11, $12, $13, $14::jsonb, $15, $16)""",
                day, uuid.UUID(e.id), uuid.UUID(e.tenant_id), uuid.UUID(e.user_id), e.use_case, e.model,
                e.prompt_template_version, prompt_digest(e.prompt), [uuid.UUID(c) for c in e.chunk_ids],
                redact(e.output_text) if e.output_text is not None else None, e.kind, e.target_ref,
                redact(e.prompt), json.dumps(e.sources, ensure_ascii=False), e.confidence, e.created_at,
            )
            for b in blocks or []:
                await tx.execute(
                    """INSERT INTO ai.guardrail_event (created_day, id, tenant_id, suggestion_log_id, rule_code,
                           action, detail, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8)""",
                    day, uuid.uuid4(), uuid.UUID(e.tenant_id), uuid.UUID(e.id), b.rule_code, b.action,
                    json.dumps(b.detail, ensure_ascii=False) if b.detail is not None else None, e.created_at,
                )

    async def get_own(self, tenant_id: str, user_id: str, suggestion_id: str) -> dict[str, Any] | None:
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            rows = await tx.fetch(
                """SELECT id, use_case, kind, model, created_at, output_text, sources_json, confidence, decision,
                          user_action
                     FROM ai.suggestion_log WHERE tenant_id = $1 AND id = $2 AND user_id = $3""",
                uuid.UUID(tenant_id), uuid.UUID(suggestion_id), uuid.UUID(user_id),
            )
        return to_api(rows[0]) if rows else None
