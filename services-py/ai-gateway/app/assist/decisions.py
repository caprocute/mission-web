"""Khép vòng đo chất lượng gợi ý AI (DV-14-06, BR-17-08, BR-17-09): ghi quyết định của người dùng, trả dữ liệu đã
dùng cho một gợi ý theo quyền đọc hiện tại, liệt kê gợi ý theo loại (tóm tắt tuần). Chỉ ghi ai.suggestion_log;
nội dung nghiệp vụ do người dùng lưu bằng API của module (BR-02-11, BR-05-05)."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import datetime
from typing import Any

from prometheus_client import Counter

from app.assist import extract
from app.rag.store import ACL_ALL, SHARED_TENANT
from app.store.suggestions import redact, to_api

DECISIONS = Counter("ai_suggestion_decision_total", "Quyết định của người dùng với gợi ý AI", ["use_case", "action"])

_COLS = """id, user_id, use_case, kind, model, created_at, output_text, sources_json, confidence, decision,
           user_action, edited_fields, chunk_ids, target_ref, action_reason"""


class NotFound(Exception):
    pass


class NotRecipient(Exception):
    """Gợi ý không phải của người gọi."""


class AlreadyDecided(Exception):
    pass


class ReasonRequired(Exception):
    pass


async def _row(tx: Any, tenant_id: str, sid: str) -> Any:
    rows = await tx.fetch(f"SELECT {_COLS} FROM ai.suggestion_log WHERE tenant_id = $1 AND id = $2",  # noqa: S608
                          uuid.UUID(tenant_id), uuid.UUID(sid))
    if not rows:
        raise NotFound
    return rows[0]


def _visible(row: Any, user_id: str) -> bool:
    """Người nhận gợi ý: người hỏi; nhiệm vụ trích từ văn bản thì là người giao của tenant (OPA kiểm vai trò)."""
    return str(row["user_id"]) == user_id or row["use_case"] == extract.USE_CASE


def task_suggestion(row: Any) -> dict[str, Any]:
    body = json.loads(row["output_text"])
    out = {"id": str(row["id"]), "paragraphNo": body["paragraphNo"],
           "confidence": float(row["confidence"]) if row["confidence"] is not None else 0.0,
           "decision": extract.DECISION.get(row["user_action"] or row["decision"], "CHO_XAC_NHAN"),
           "fields": body["fields"], "aiFields": body["fields"], "editedFields": list(row["edited_fields"] or []),
           "similar": []}
    if row["action_reason"]:
        out["reason"] = row["action_reason"]
    return out


async def decide(tx: Any, tenant_id: str, user_id: str, sid: str, body: dict[str, Any]) -> dict[str, Any]:
    row = await _row(tx, tenant_id, sid)
    if not _visible(row, user_id):
        raise NotRecipient
    if row["user_action"] is not None:
        raise AlreadyDecided
    action = body["decision"]
    reason = (body.get("reason") or "").strip()
    if action == "BO_QUA" and row["use_case"] == extract.USE_CASE and not reason:
        raise ReasonRequired
    edited = list(dict.fromkeys(body.get("editedFields") or [])) if action == "SUA" else []
    # target_ref của nhiệm vụ trích giữ mã văn bản và phiên bản; ca dùng khác nhận bản ghi đích client gửi.
    target = row["target_ref"] if row["use_case"] == extract.USE_CASE else (body.get("targetRef") or row["target_ref"])
    rows = await tx.fetch(
        f"""UPDATE ai.suggestion_log
               SET user_action = $3, decision = $3, edited_fields = $4::varchar[], accepted_by = $5,
                   action_reason = $6, target_ref = $7, decided_at = now()
             WHERE tenant_id = $1 AND id = $2 AND user_action IS NULL
         RETURNING {_COLS}""",  # noqa: S608
        uuid.UUID(tenant_id), uuid.UUID(sid), action, edited,
        uuid.UUID(user_id) if action in ("CHAP_NHAN", "SUA") else None, redact(reason)[:1000] or None, target)
    if not rows:
        raise AlreadyDecided
    DECISIONS.labels(row["use_case"], action).inc()
    new = rows[0]
    if new["use_case"] == extract.USE_CASE:
        return task_suggestion(new)
    return api(new)


def api(row: Any) -> dict[str, Any]:
    return to_api(row)


MASKED = {"kind": "VAN_BAN", "title": "Nguồn ngoài phạm vi quyền hiện tại", "masked": True}


async def sources(tx: Any, tenant_id: str, user_id: str, readers: list[str], sid: str) -> dict[str, Any]:
    """Nguồn đã dùng, lọc lại theo quyền hiện tại: đoạn ai.doc_chunk mà người xem không còn đọc được (ACL đổi,
    đoạn đã thay) trả dạng che."""
    row = await _row(tx, tenant_id, sid)
    if not _visible(row, user_id):
        raise NotRecipient
    items = row["sources_json"]
    items = json.loads(items) if isinstance(items, str) else (items or [])
    chunk_ids = [str(c) for c in row["chunk_ids"] or []]
    readable: set[str] = set()
    if chunk_ids:
        acl = [uuid.UUID(r) for r in {*readers, ACL_ALL}]
        got = await tx.fetch(
            """SELECT id FROM ai.doc_chunk WHERE id = ANY($1::uuid[]) AND tenant_id = ANY($2::uuid[])
                 AND acl_unit_ids && $3::uuid[]""",
            [uuid.UUID(c) for c in chunk_ids], [uuid.UUID(tenant_id), uuid.UUID(SHARED_TENANT)], acl)
        readable = {str(r["id"]) for r in got}
    out = []
    for s in items:
        if not isinstance(s, dict):
            continue
        ref = str(s.get("ref") or "")
        out.append(dict(MASKED) if ref in chunk_ids and ref not in readable else s)
    return {"suggestionId": str(row["id"]), "items": out}


def _cursor(created_at: datetime, sid: Any) -> str:
    return base64.urlsafe_b64encode(f"{created_at.isoformat()}|{sid}".encode()).decode().rstrip("=")


def _uncursor(c: str) -> tuple[datetime, uuid.UUID]:
    raw = base64.urlsafe_b64decode(c + "=" * (-len(c) % 4)).decode()
    at, _, sid = raw.partition("|")
    return datetime.fromisoformat(at), uuid.UUID(sid)


async def page(tx: Any, tenant_id: str, user_id: str, *, kind: str | None, use_case: str | None, limit: int,
               cursor: str | None) -> dict[str, Any]:
    """listAiSuggestions: gợi ý của chính người gọi, mới nhất trước, phân trang con trỏ (limit ≤ 200)."""
    args: list[Any] = [uuid.UUID(tenant_id), uuid.UUID(user_id), kind, use_case, limit + 1]
    where = ""
    if cursor:
        at, sid = _uncursor(cursor)
        args += [at, sid]
        where = "AND (created_at, id) < ($6, $7)"
    rows = await tx.fetch(
        f"""SELECT {_COLS} FROM ai.suggestion_log
             WHERE tenant_id = $1 AND user_id = $2 AND ($3::varchar IS NULL OR kind = $3)
               AND ($4::varchar IS NULL OR use_case = $4) {where}
             ORDER BY created_at DESC, id DESC LIMIT $5""", *args)  # noqa: S608
    more = len(rows) > limit
    rows = rows[:limit]
    return {"items": [api(r) for r in rows],
            "nextCursor": _cursor(rows[-1]["created_at"], rows[-1]["id"]) if more and rows else None}
