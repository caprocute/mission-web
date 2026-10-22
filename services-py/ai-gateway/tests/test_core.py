"""Kiểm thử không cần Docker: xác thực, đổi token, client lõi chỉ đọc, hàng đợi suy luận, client vLLM, Gateway."""

from __future__ import annotations

import asyncio
import hashlib
import json
from typing import Any

import httpx
import jwt
import pytest

from app.core.auth import AuthError, JwtVerifier, principal_from_claims
from app.core.core_client import CoreClient, WriteForbiddenError, new_http
from app.core.exchange import ExchangeError, TokenExchanger
from app.core.pipeline import Accepted, Gateway, Overloaded, SuggestionRequest
from app.llm.client import InferenceQueue, LlmError, Message, MockLlm, OpenAiCompatLlm, QueueFull
from app.store.suggestions import Block, Entry, prompt_digest, redact
from tests.helpers import ISSUER, JWKS_URL, TENANT_A, TOKEN_URL, USER_1, USER_2, FakeIdp, Router, token

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


def idp_client(idp: FakeIdp) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=Router({"idp.test": idp}))


def exchanger(idp: FakeIdp) -> TokenExchanger:
    http = idp_client(idp)
    return TokenExchanger(http, JwtVerifier(http, JWKS_URL, ISSUER), token_url=TOKEN_URL, client_id="ai-gateway",
                          client_secret="khong-phai-bi-mat", audience="mission-core", scope="ai_readonly",
                          required_role="ai_readonly")


async def test_verify_token_and_roles() -> None:
    v = JwtVerifier(idp_client(FakeIdp()), JWKS_URL, ISSUER)
    p = await v.verify(token(roles=("manager", "member"), unit_id="u1", acr="urn:viettel:aal2", auth_time=10))
    assert (p.user_id, p.tenant_id, p.roles, p.acr, p.unit_id) == (USER_1, TENANT_A, ("manager", "member"), "aal2",
                                                                   "u1")
    assert p.opa_subject()["roles"] == ["manager", "member"]
    with pytest.raises(AuthError):
        await v.verify(token(iss="https://khac.test"))
    with pytest.raises(AuthError):
        await v.verify(token(exp=1))
    with pytest.raises(AuthError):
        await v.verify(token()[:-4] + "AAAA")
    with pytest.raises(AuthError):
        principal_from_claims({"sub": USER_1}, "x")


async def test_exchange_keeps_identity_and_only_readonly_role() -> None:
    idp = FakeIdp()
    ex = exchanger(idp)
    p = await JwtVerifier(idp_client(idp), JWKS_URL, ISSUER).verify(token(roles=("manager",)))
    t1 = await ex.exchange(p)
    t2 = await ex.exchange(p)
    assert t1 is t2 and len(idp.exchanges) == 1, "token đã đổi phải được đệm"
    form = idp.exchanges[0]
    assert form["grant_type"] == "urn:ietf:params:oauth:grant-type:token-exchange"
    assert form["subject_token"] == p.token and form["audience"] == "mission-core" and form["scope"] == "ai_readonly"
    assert form["authorization"].startswith("Basic ")

    idp.issue = lambda c: token(c["sub"], c["tenant_id"], ("ai_readonly", "manager"), aud="mission-core")
    with pytest.raises(ExchangeError):
        await exchanger(idp).exchange(p)
    idp.issue = lambda c: token(USER_2, c["tenant_id"], ("ai_readonly",), aud="mission-core")
    with pytest.raises(ExchangeError):
        await exchanger(idp).exchange(p)
    idp.issue = lambda c: None
    with pytest.raises(ExchangeError):
        await exchanger(idp).exchange(p)


async def test_core_client_reads_with_readonly_token_and_never_writes() -> None:
    idp = FakeIdp()
    core_calls: list[httpx.Request] = []

    def core(req: httpx.Request) -> httpx.Response:
        core_calls.append(req)
        return httpx.Response(200, json={"items": []})

    router = Router({"idp.test": idp, "core.test": core})
    ex = exchanger(idp)
    cc = CoreClient(new_http("http://core.test", 5, router), ex)
    p = await JwtVerifier(idp_client(idp), JWKS_URL, ISSUER).verify(token(roles=("member",)))
    r = await cc.get(p, "/api/v1/tasks", {"limit": 10})
    assert r.status_code == 200
    sent = core_calls[0].headers["authorization"].removeprefix("Bearer ")
    assert sent != p.token, "không được chuyển token gốc của người dùng tới lõi"
    c = jwt.decode(sent, options={"verify_signature": False})
    assert c["realm_access"]["roles"][0] == "ai_readonly" and c["sub"] == USER_1
    for m in ("post", "put", "patch", "delete", "request", "send", "stream"):
        with pytest.raises(WriteForbiddenError):
            getattr(cc, m)
    raw = cc._http
    for m in ("POST", "PUT", "PATCH", "DELETE"):
        with pytest.raises(WriteForbiddenError):
            await raw.request(m, "/api/v1/tasks", json={})
    assert len(core_calls) == 1, "yêu cầu ghi không được tới API lõi"
    with pytest.raises(ValueError):
        await cc.get(p, "https://khac.test/api/v1/x")


async def test_queue_limits_and_reports_length() -> None:
    q = InferenceQueue(MockLlm(delay_s=0.2), max_concurrency=1, max_waiting=1)
    msgs = [Message("user", "xin chào")]
    t1 = asyncio.create_task(q.run(msgs, timeout_s=5))
    await asyncio.sleep(0.01)
    t2 = asyncio.create_task(q.run(msgs, timeout_s=5))
    await asyncio.sleep(0.01)
    assert q.waiting == 1 and not q.has_room()
    with pytest.raises(QueueFull):
        await q.run(msgs, timeout_s=5)
    t3 = asyncio.create_task(q.run(msgs, timeout_s=5, bypass_cap=True))
    await asyncio.gather(t1, t2, t3)
    assert q.waiting == 0 and q.has_room()
    with pytest.raises(LlmError):
        await InferenceQueue(MockLlm(delay_s=1), 1, 0).run(msgs, timeout_s=0.05)


async def test_openai_compatible_client() -> None:
    seen: list[dict[str, Any]] = []

    def vllm(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        seen.append(body)
        if body["messages"][-1]["content"] == "lỗi":
            return httpx.Response(500)
        return httpx.Response(200, json={"model": body["model"], "choices": [{"message": {"content": "Trả lời"}}],
                                         "usage": {"prompt_tokens": 7, "completion_tokens": 2}})

    llm = OpenAiCompatLlm(httpx.AsyncClient(base_url="http://vllm.test", transport=Router({"vllm.test": vllm})),
                          "viettel-llm-vi")
    c = await llm.complete([Message("system", "s"), Message("user", "hỏi")], json_mode=True, max_tokens=64)
    assert (c.text, c.model, c.prompt_tokens) == ("Trả lời", "viettel-llm-vi", 7)
    assert seen[0]["response_format"] == {"type": "json_object"} and seen[0]["max_tokens"] == 64
    with pytest.raises(LlmError):
        await llm.complete([Message("user", "lỗi")])


class MemStore:
    def __init__(self) -> None:
        self.logged: list[tuple[Entry, list[Block]]] = []

    async def log(self, e: Entry, blocks: list[Block] | None = None) -> None:
        self.logged.append((e, list(blocks or [])))


def principal() -> Any:
    return principal_from_claims({"sub": USER_1, "tenant_id": TENANT_A, "realm_access": {"roles": ["member"]}}, "t")


def req(text: str = "Gợi ý OKR cho phòng") -> SuggestionRequest:
    return SuggestionRequest("HOI_DAP_QUY_DINH", "v1", [Message("user", text)])


async def test_gateway_sync_deferred_and_overloaded() -> None:
    store = MemStore()
    llm = MockLlm(delay_s=0.1)
    gw = Gateway(InferenceQueue(llm, 1, 0), store, timeout_s=5, sync_wait_s=2, deferred_max=1)  # type: ignore[arg-type]
    first = asyncio.create_task(gw.suggest(principal(), req()))
    await asyncio.sleep(0.01)
    second = await gw.suggest(principal(), req("thứ hai"))
    assert isinstance(second, Accepted) and second.job()["status"] == "CHO"
    assert second.location == f"/api/v1/ai/suggestions/{second.suggestion_id}"
    with pytest.raises(Overloaded):
        await gw.suggest(principal(), req("thứ ba"))
    r1 = await first
    assert isinstance(r1, dict) and r1["aiGenerated"] is True and r1["label"] == "Nội dung do AI tạo"
    for _ in range(100):
        if not gw.deferred:
            break
        await asyncio.sleep(0.02)
    assert [e.id for e, _ in store.logged][-1] == second.suggestion_id, "việc hoãn phải được ghi với đúng mã job"

    slow = Gateway(InferenceQueue(MockLlm(delay_s=0.3), 1, 4), store, timeout_s=5, sync_wait_s=0.05,  # type: ignore[arg-type]
                   deferred_max=4)
    assert isinstance(await slow.suggest(principal(), req()), Accepted)


async def test_guard_block_is_logged() -> None:
    store = MemStore()

    async def no_scoring(_: Any, r: SuggestionRequest) -> list[Block]:
        return [Block("OUT_OF_SCOPE", "CHAN", "Trợ lý không chấm điểm hay đề xuất xếp loại.")] if "xếp loại" in \
            r.messages[-1].content else []

    gw = Gateway(InferenceQueue(MockLlm(), 1, 1), store, timeout_s=5, sync_wait_s=1, deferred_max=1,  # type: ignore[arg-type]
                 input_guards=[no_scoring])
    r = await gw.suggest(principal(), req("Đề xuất xếp loại cho đồng chí A"))
    assert isinstance(r, dict) and r["blocked"]["ruleCode"] == "OUT_OF_SCOPE" and r["outputText"] is None
    assert store.logged[0][1][0].action == "CHAN"


def test_redaction_and_digest() -> None:
    raw = "Đồng chí số định danh 001203004567, email a.b@x.vn, điện thoại 0912345678"
    red = redact(raw)
    assert "001203004567" not in red and "a.b@x.vn" not in red and "0912345678" not in red
    assert prompt_digest(raw) == hashlib.sha256(raw.encode()).digest()
