"""Rào chắn hai đầu của mọi yêu cầu AI (DV-14-03).

Đầu vào: chặn câu hỏi tiêm lệnh, loại khỏi ngữ cảnh đoạn tài liệu nghi tiêm lệnh, chặn tuyệt đối dữ liệu C3,
chặn ca dùng bị cấm theo BR-17-04 (chấm điểm, đề xuất mức xếp loại, nhận xét cán bộ) và câu hỏi cố ý lấy dữ liệu
cá nhân ngoài chức năng, che dữ liệu cá nhân trong lời nhắc trước khi gửi mô hình.
Đầu ra: kiểm JSON theo lược đồ của ca dùng, trích dẫn phải thuộc danh sách đoạn đã truy xuất và gợi ý có ngữ
cảnh phải có nguồn (BR-17-02), không chấm điểm hay xếp loại, không quá số mục tiêu cho phép (BR-17-06), che dữ
liệu cá nhân lọt ra (PII_OUT). Mỗi lần chặn, che hay cảnh báo trả một Block để Gateway ghi ai.guardrail_event."""

from __future__ import annotations

import json
import re
import time
from typing import Any

import jsonschema
from prometheus_client import Counter, Histogram

from app.core.auth import Principal
from app.guard import rules
from app.llm.client import Completion, Message
from app.store.suggestions import Block

MSG_SCOPE = ("Trợ lý không thực hiện chấm điểm hoặc đề xuất xếp loại. Việc này thuộc thẩm quyền của cán bộ và cấp "
             "có thẩm quyền theo quy định.")  # MSG-17-02
MSG_INJECTION = "Yêu cầu có nội dung trợ lý không thể thực hiện. Đề nghị đồng chí diễn đạt lại câu hỏi."
MSG_C2 = "Trợ lý không cung cấp thông tin cá nhân của cán bộ. Đề nghị đồng chí tra cứu tại chức năng có thẩm quyền."
MSG_C3 = "Trợ lý không xử lý tài liệu mật."
MSG_FORMAT = "Trợ lý chưa đưa ra được gợi ý phù hợp. Đề nghị đồng chí thử lại."
MSG_SOURCE = "Gợi ý không có nguồn đối chiếu được nên đã bị loại. Đề nghị đồng chí thử lại."

GUARD_TOTAL = Counter("ai_guardrail_total", "Số lần rào chắn chặn, che hoặc cảnh báo", ["rule_code", "action"])
INPUT_SECONDS = Histogram("ai_guardrail_input_seconds", "Thời gian rào chắn đầu vào",
                          buckets=(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.5))

_CITE = re.compile(r"\[#(\d{1,3})\]")


def _block(code: str, act: str, message: str, **detail: Any) -> Block:
    GUARD_TOTAL.labels(code, act).inc()
    return Block(code, act, message, "MSG-17-02" if code == "OUT_OF_SCOPE" else "", detail or None)


def _user_text(messages: list[Message]) -> str:
    return "\n".join(m.content for m in messages if m.role == "user")


async def input_guard(p: Principal, req: Any) -> list[Block]:
    """Có thể sửa req: che dữ liệu cá nhân trong lời nhắc và loại đoạn ngữ cảnh vi phạm."""
    start = time.perf_counter()
    try:
        return _input(req)
    finally:
        INPUT_SECONDS.observe(time.perf_counter() - start)


def _input(req: Any) -> list[Block]:
    blocks: list[Block] = []
    if any(c.classification == "C3" for c in req.context) or req.classification == "C3":
        return [_block("C2_REQUEST", "CHAN", MSG_C3, classification="C3")]
    question = _user_text(req.messages)
    if rules.is_injection(question):
        return [_block("PROMPT_INJECTION", "CHAN", MSG_INJECTION, where="question")]
    if rules.asks_scoring(question):
        return [_block("OUT_OF_SCOPE", "CHAN", MSG_SCOPE, rule="BR-17-04")]
    if not req.allow_c2 and rules.asks_c2(question):
        return [_block("C2_REQUEST", "CHAN", MSG_C2)]
    kept = []
    for c in req.context:
        if rules.is_injection(c.text):
            blocks.append(_block("PROMPT_INJECTION", "CHE", MSG_INJECTION, where="context", chunk=c.ref))
        else:
            kept.append(c)
    req.context = kept
    kinds: set[str] = set()
    if not req.allow_c2:
        msgs = []
        for m in req.messages:
            mk = rules.mask_pii(m.content)
            kinds.update(mk.kinds)
            msgs.append(Message(m.role, mk.text))
        req.messages = msgs
        for c in req.context:
            mk = rules.mask_pii(c.text)
            kinds.update(mk.kinds)
            c.text = mk.text
    if kinds:
        blocks.append(_block("PII_OUT", "CHE", "Đã che dữ liệu cá nhân trước khi gửi trợ lý.", where="input",
                             kinds=sorted(kinds)))
    return blocks


def citations(text: str) -> tuple[set[int], set[str]]:
    """Chỉ số [#n] trong văn bản và mã nguồn trong trường `sources`/`citations` nếu đầu ra là JSON."""
    idx = {int(m) for m in _CITE.findall(text)}
    refs: set[str] = set()
    try:
        doc = json.loads(text)
    except ValueError:
        return idx, refs

    def walk(v: Any, key: str = "") -> None:
        if isinstance(v, dict):
            for k, x in v.items():
                walk(x, k)
        elif isinstance(v, list):
            for x in v:
                walk(x, key)
        elif isinstance(v, str) and key in ("ref", "sources", "citations", "source"):
            m = _CITE.fullmatch(v.strip())
            if m:
                idx.add(int(m.group(1)))
            else:
                refs.add(v.strip())

    walk(doc)
    return idx, refs


async def output_guard(p: Principal, req: Any, c: Completion) -> tuple[Completion, list[Block]]:
    blocks: list[Block] = []
    text = c.text
    if req.json_mode or req.output_schema is not None:
        try:
            doc = json.loads(text)
            if req.output_schema is not None:
                jsonschema.validate(doc, req.output_schema)
        except (ValueError, jsonschema.ValidationError) as e:
            return c, [_block("OUT_OF_SCOPE", "CHAN", MSG_FORMAT, reason="SCHEMA", err=type(e).__name__)]
        if req.max_objectives is not None and isinstance(doc, dict) and isinstance(doc.get("objectives"), list) \
                and len(doc["objectives"]) > req.max_objectives:
            doc["objectives"] = doc["objectives"][: req.max_objectives]
            text = json.dumps(doc, ensure_ascii=False)
            blocks.append(_block("OUT_OF_SCOPE", "CHE", MSG_SCOPE, rule="BR-17-06", kept=req.max_objectives))
    if rules.outputs_scoring(text):
        return c, [*blocks, _block("OUT_OF_SCOPE", "CHAN", MSG_SCOPE, rule="BR-17-04", where="output")]
    if rules.is_injection(text):
        blocks.append(_block("PROMPT_INJECTION", "CANH_BAO", MSG_INJECTION, where="output"))
    idx, refs = citations(text)
    known = {ch.ref for ch in req.context}
    bad_idx = sorted(i for i in idx if not 1 <= i <= len(req.context))
    bad_ref = sorted(r for r in refs if r not in known and not any(r == s.get("ref") for s in req.sources))
    if bad_idx or bad_ref:
        return c, [*blocks, _block("OUT_OF_SCOPE", "CHAN", MSG_SOURCE, reason="SOURCE_NOT_RETRIEVED",
                                   count=len(bad_idx) + len(bad_ref))]
    if req.context and req.require_sources and not idx and not (refs & known):
        return c, [*blocks, _block("OUT_OF_SCOPE", "CHAN", MSG_SOURCE, reason="NO_SOURCE")]
    mk = rules.mask_pii(text)
    if mk.kinds:
        text = mk.text
        blocks.append(_block("PII_OUT", "CHE", "Đã che dữ liệu cá nhân trong gợi ý.", where="output",
                             kinds=list(mk.kinds)))
    used = [req.context[i - 1] for i in sorted(idx)] + [ch for ch in req.context if ch.ref in refs]
    cited: dict[str, Any] = {}
    for ch in used:
        cited.setdefault(ch.ref, ch)
    req.cited = list(cited.values())
    return Completion(text, c.model, c.prompt_tokens, c.completion_tokens), blocks
