"""Job tóm tắt tuần cho lãnh đạo (DV-14-06, QT-16 BR-16-06, SCR-1601 trường 8): chạy sáng thứ Hai theo giờ Việt
Nam. Với mỗi lãnh đạo, đọc số liệu tổng hợp trong phạm vi của chính người đó qua analytics-api (token dịch vụ đổi
lấy token chỉ đọc của người nhận), sinh 3 đến 5 ý, ghi ai.suggestion_log `kind = TOM_TAT_TUAN` kèm nguồn và thời
điểm dữ liệu. Chạy lại trong cùng tuần không sinh bản thứ hai (mã tuần ISO trong target_ref)."""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any

import httpx
import jsonschema

from app.core.core_client import CoreClient
from app.core.exchange import ExchangeError, ReadOnlyToken, TokenExchanger
from app.core.pipeline import ContextChunk
from app.guard import rules
from app.llm.client import InferenceQueue, LlmError, Message, QueueFull
from app.llm.fake_json import schema_instruction
from app.store.suggestions import Entry, insert

log = logging.getLogger(__name__)

VN = timezone(timedelta(hours=7))
KIND = "TOM_TAT_TUAN"
USE_CASE = "TOM_TAT_VAN_BAN"  # ví dụ của hợp đồng listAiSuggestions
TEMPLATE = "dv14-06.weekly.1"

# Hai truy vấn chuẩn trên lớp ngữ nghĩa (tên chỉ số theo ví dụ hợp đồng AiSource.ref; suy luận).
QUERIES: tuple[tuple[str, str, dict[str, Any]], ...] = (
    ("okr.tien_do_o", "Tiến độ mục tiêu O theo đơn vị",
     {"measures": ["okr.tien_do_o"], "dimensions": ["org_unit.name"], "compareToPreviousPeriod": True, "limit": 50}),
    ("task.so_viec_cham", "Nhiệm vụ quá hạn theo đơn vị",
     {"measures": ["task.so_viec_cham"], "dimensions": ["org_unit.name"], "limit": 50}),
)

SCHEMA: dict[str, Any] = {"type": "object", "required": ["text", "items"], "properties": {
    "text": {"type": "string", "maxLength": 400},
    "items": {"type": "array", "minItems": 3, "maxItems": 5, "items": {
        "type": "object", "required": ["text", "sources"], "properties": {
            "text": {"type": "string", "maxLength": 300},
            "sources": {"type": "array", "items": {"type": "string"}}}}}}}

PROMPT = ("Đồng chí là trợ lý tổng hợp cho lãnh đạo cơ quan Đảng, Nhà nước. Viết tóm tắt tuần gồm một câu chung và 3 "
          "đến 5 ý ngắn, chỉ dựa trên số liệu tham khảo; mỗi ý trích nguồn [#n] trong trường sources. Không chấm "
          "điểm, không xếp loại, không nhận xét cá nhân.\n" + schema_instruction(SCHEMA))


@dataclass(frozen=True)
class Recipient:
    tenant_id: str
    user_id: str


def week_ref(day: date) -> str:
    y, w, _ = day.isocalendar()
    return f"week:{y}-W{w:02d}"


def summary_id(r: Recipient, ref: str) -> str:
    return str(uuid.uuid5(uuid.UUID(r.user_id), f"{KIND}|{ref}"))


def _table(res: dict[str, Any]) -> tuple[list[str], list[list[Any]]]:
    cols = [str(c.get("title") or c.get("name")) for c in res.get("columns") or [] if isinstance(c, dict)]
    rows = [r for r in res.get("rows") or [] if isinstance(r, list)]
    return cols, rows


def fallback(chunks: list[tuple[ContextChunk, dict[str, Any]]]) -> dict[str, Any]:
    """Mô hình lỗi hoặc ý thiếu nguồn: tóm tắt theo mẫu từ chính số liệu, mỗi ý gắn đúng truy vấn đã dùng."""
    items: list[dict[str, Any]] = []
    for i, (ch, res) in enumerate(chunks, 1):
        _, rows = _table(res)
        nums = [r for r in rows if len(r) >= 2 and isinstance(r[1], int | float)]
        if not nums:
            continue
        hi, lo = max(nums, key=lambda r: r[1]), min(nums, key=lambda r: r[1])
        unit = "%" if "tien_do" in ch.ref else ""
        title = ch.source["title"]
        items.append({"text": f"{title}: cao nhất {hi[0]} {hi[1]}{unit}.", "sources": [f"[#{i}]"]})
        if lo is not hi:
            items.append({"text": f"{title}: thấp nhất {lo[0]} {lo[1]}{unit}.", "sources": [f"[#{i}]"]})
        items.append({"text": f"{title}: có số liệu của {len(nums)} đơn vị.", "sources": [f"[#{i}]"]})
    return {"text": "Tóm tắt số liệu tuần trong phạm vi phụ trách.", "items": items[:5]}


class WeeklySummary:
    def __init__(self, pool: Any, queue: InferenceQueue, core: CoreClient, exchanger: TokenExchanger,
                 service_token: Callable[[], Awaitable[ReadOnlyToken]]) -> None:
        self.pool = pool
        self.queue = queue
        self.core = core
        self.ex = exchanger
        self.service_token = service_token

    async def recipients(self, tok: ReadOnlyToken, tenant_id: str) -> list[Recipient]:
        """Lãnh đạo đang có vai trò hiệu lực của tenant (`GET /config/role-bindings?role=leader&status=HIEU_LUC`)."""
        out: dict[str, Recipient] = {}
        cursor: str | None = None
        for _ in range(100):
            params: dict[str, Any] = {"role": "leader", "status": "HIEU_LUC", "limit": 200}
            if cursor:
                params["cursor"] = cursor
            page = await self.core.get_json_with(tok, "/api/v1/config/role-bindings", params)
            for b in page.get("items") or []:
                person = (b.get("person") or {}).get("id") if isinstance(b, dict) else None
                if isinstance(person, str):
                    rid = str(uuid.UUID(person))
                    out[rid] = Recipient(tenant_id, rid)
            cursor = page.get("nextCursor")
            if not cursor:
                break
        return list(out.values())

    async def _exists(self, r: Recipient, ref: str) -> bool:
        from vtplatform.db import in_tenant_tx

        async with in_tenant_tx(self.pool, r.tenant_id) as tx:
            n = await tx.fetchval(
                """SELECT count(*) FROM ai.suggestion_log
                    WHERE tenant_id = $1 AND user_id = $2 AND kind = $3 AND target_ref = $4""",
                uuid.UUID(r.tenant_id), uuid.UUID(r.user_id), KIND, ref)
        return bool(n)

    async def one(self, svc: ReadOnlyToken, r: Recipient, today: date) -> str | None:
        """Sinh tóm tắt cho một người nhận; trả mã gợi ý, hoặc None khi đã có bản tuần này hoặc không có số liệu."""
        ref = week_ref(today)
        if await self._exists(r, ref):
            return None
        tok = await self.ex.impersonate(svc, r.tenant_id, r.user_id)
        data: list[tuple[ContextChunk, dict[str, Any]]] = []
        for measure, title, q in QUERIES:
            try:
                res = await self.core.query_analytics(tok, q)
            except (httpx.HTTPError, ValueError) as e:
                log.info("không đọc được số liệu tóm tắt tuần", extra={"measure": measure, "err": type(e).__name__})
                continue
            cols, rows = _table(res)
            excerpt = "; ".join(", ".join(str(x) for x in row) for row in rows[:20])
            src = {"kind": "TRUY_VAN", "title": title, "ref": measure, "excerpt": excerpt[:500]}
            if isinstance(res.get("dataAsOf"), str):
                src["asOf"] = res["dataAsOf"]
            data.append((ContextChunk(ref=measure, text=f"{title} ({' | '.join(cols)}): {excerpt}", source=src), res))
        if not data:
            return None
        ctx = [c for c, _ in data]
        note = "\n".join(f"[#{i}] {c.text}" for i, c in enumerate(ctx, 1))
        msgs = [Message("system", PROMPT), Message("user", "Số liệu tham khảo:\n" + note)]
        model = self.queue.llm.model
        doc: dict[str, Any] | None = None
        try:
            c = await self.queue.run(msgs, timeout_s=60, bypass_cap=True, max_tokens=700, json_mode=True)
            model = c.model
            cand = json.loads(c.text)
            jsonschema.validate(cand, SCHEMA)
            if not rules.outputs_scoring(c.text):
                doc = cand
        except (LlmError, QueueFull, ValueError, jsonschema.ValidationError) as e:
            log.info("mô hình không tóm tắt được, dùng mẫu", extra={"err": type(e).__name__})
        items: list[dict[str, Any]] = []
        if doc is not None:
            items = [it for it in doc["items"] if _cited(it, len(ctx))]
        if len(items) < 3:  # BR-16-06: mỗi ý có ít nhất một nguồn
            doc = fallback(data)
            items = doc["items"]
        if not items:
            return None
        used = sorted({int(s[2:-1]) for it in items for s in it["sources"]})
        sources = [ctx[i - 1].source for i in used]
        text = rules.mask_pii(str((doc or {}).get("text") or "")).text
        out = {"text": text, "items": [rules.mask_pii(str(it["text"])).text for it in items]}
        entry = Entry(tenant_id=r.tenant_id, user_id=r.user_id, use_case=USE_CASE, model=model,
                  prompt_template_version=TEMPLATE, prompt=msgs[1].content,
                  output_text=json.dumps(out, ensure_ascii=False), sources=sources, kind=KIND, target_ref=ref,
                  id=summary_id(r, ref), redact_output=False)
        from vtplatform.db import in_tenant_tx

        async with in_tenant_tx(self.pool, r.tenant_id) as tx:
            if await tx.fetchval("""SELECT count(*) FROM ai.suggestion_log WHERE tenant_id = $1 AND user_id = $2
                                       AND kind = $3 AND target_ref = $4""",
                                 uuid.UUID(r.tenant_id), uuid.UUID(r.user_id), KIND, ref):
                return None
            await insert(tx, entry)
        return entry.id

    async def run(self, tenant_id: str, recipients: list[Recipient] | None = None,
                  today: date | None = None) -> dict[str, int]:
        day = today or datetime.now(VN).date()
        svc = await self.service_token()
        targets = recipients if recipients is not None else await self.recipients(svc, tenant_id)
        stats = {"created": 0, "skipped": 0, "failed": 0}
        for r in targets:
            try:
                stats["created" if await self.one(svc, r, day) else "skipped"] += 1
            except (ExchangeError, httpx.HTTPError) as e:
                stats["failed"] += 1
                log.warning("không tạo được tóm tắt tuần", extra={"err": type(e).__name__})
        return stats


def _cited(item: dict[str, Any], n: int) -> bool:
    refs = [s for s in item.get("sources") or [] if isinstance(s, str)]
    ok = [s for s in refs if s.startswith("[#") and s.endswith("]") and s[2:-1].isdigit() and 1 <= int(s[2:-1]) <= n]
    return bool(ok) and len(ok) == len(refs)


async def main(tenant_ids: list[str]) -> None:
    """CronJob của cell: `python -m app.assist.weekly <tenant>...` lúc 23:00 UTC Chủ nhật (06:00 thứ Hai giờ Việt
    Nam, suy luận theo ví dụ hợp đồng createdAt 06:00+07:00)."""
    import asyncpg

    from app.core.auth import JwtVerifier
    from app.core.config import Settings
    from app.core.core_client import new_http
    from app.core.service_token import ServiceToken
    from app.main import build_llm

    s = Settings.from_env()
    pool = await asyncpg.create_pool(s.database_url, min_size=1, max_size=4)
    idp = httpx.AsyncClient(timeout=5)
    core_http = new_http(s.core_base_url, s.core_timeout_s)
    llm, llm_http = build_llm(s)
    verifier = JwtVerifier(idp, s.oidc_jwks_url, s.oidc_issuer, s.oidc_audience)
    ex = TokenExchanger(idp, verifier, token_url=s.oidc_token_url, client_id=s.exchange_client_id,
                        client_secret=s.exchange_client_secret, audience=s.exchange_audience,
                        scope=s.exchange_scope, required_role=s.exchange_role)
    svc = ServiceToken(idp, verifier, token_url=s.oidc_token_url, client_id=s.exchange_client_id,
                       client_secret=s.exchange_client_secret, audience=s.exchange_audience,
                       required_role=s.exchange_role)
    job = WeeklySummary(pool, InferenceQueue(llm, s.llm_max_concurrency, s.llm_max_waiting),
                        CoreClient(core_http, ex), ex, svc.get)
    try:
        for t in tenant_ids:
            log.info("tóm tắt tuần", extra={"tenant.id": t, **await job.run(str(uuid.UUID(t)))})
    finally:
        for c in (idp, core_http, llm_http):
            if c is not None:
                await c.aclose()
        await pool.close()


if __name__ == "__main__":
    import asyncio
    import sys

    from vtplatform.logging import configure_logger

    configure_logger("ai-gateway-weekly")
    asyncio.run(main(sys.argv[1:]))
