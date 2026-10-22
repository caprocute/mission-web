"""Điểm vào SVC-14 ai-gateway (Python 3.12, FastAPI, Uvicorn 2 worker theo TL-04)."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import asyncpg
import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response
from vtplatform import problem as vp
from vtplatform.logging import configure_logger
from vtplatform.otel import extract_headers, reset_current_span_context, set_current_span_context

from app.api import assist, routes
from app.api import speech as speech_api
from app.assist.evidence import EvidenceOcr
from app.assist.extract import Extractor
from app.assist.service import AssistService
from app.core import problems
from app.core.auth import JwtVerifier
from app.core.config import Settings
from app.core.core_client import CoreClient, new_http
from app.core.exchange import TokenExchanger
from app.core.opa import OpaClient
from app.core.pipeline import Gateway, Overloaded
from app.core.service_token import ServiceToken
from app.core.state import State
from app.guard.guards import input_guard, output_guard
from app.llm.client import InferenceQueue, Llm, LlmError, MockLlm, OpenAiCompatLlm
from app.ocr.client import MockOcr, Ocr, ViettelOcr
from app.rag import consumer
from app.rag.embed import Embedder, HashEmbedder, OpenAiEmbedder
from app.rag.ingest import Indexer
from app.rag.retriever import Retriever
from app.rag.store import ChunkStore
from app.speech.recognizer import MockRecognizer, OpenAiAsr, SpeechRecognizer
from app.speech.service import SpeechService
from app.store.idempotency import IdempotencyStore
from app.store.suggestions import SuggestionStore

log = logging.getLogger("ai-gateway")


def build_llm(s: Settings) -> tuple[Llm, httpx.AsyncClient | None]:
    if s.llm_mode == "mock":
        return MockLlm(), None
    if s.llm_mode != "openai" or not s.llm_base_url:
        raise RuntimeError("LLM_MODE phải là mock hoặc openai (kèm LLM_BASE_URL)")
    headers = {"Authorization": f"Bearer {s.llm_api_key}"} if s.llm_api_key else {}
    http = httpx.AsyncClient(base_url=s.llm_base_url, timeout=s.llm_timeout_s, headers=headers)
    return OpenAiCompatLlm(http, s.llm_model), http


def build_embedder(s: Settings) -> tuple[Embedder, httpx.AsyncClient | None]:
    if s.embed_mode == "mock":
        return HashEmbedder(), None
    if s.embed_mode != "openai" or not s.embed_base_url:
        raise RuntimeError("EMBED_MODE phải là mock hoặc openai (kèm EMBED_BASE_URL)")
    http = httpx.AsyncClient(base_url=s.embed_base_url, timeout=s.llm_timeout_s)
    return OpenAiEmbedder(http, s.embed_model), http


def build_ocr(s: Settings, transport: httpx.AsyncBaseTransport | None) -> tuple[Ocr, httpx.AsyncClient | None]:
    if s.ocr_mode == "mock":
        return MockOcr(), None
    if s.ocr_mode != "viettel" or not s.ocr_base_url:
        raise RuntimeError("OCR_MODE phải là mock hoặc viettel (kèm OCR_BASE_URL)")
    headers = {"Authorization": f"Bearer {s.ocr_api_key}"} if s.ocr_api_key else {}
    http = httpx.AsyncClient(base_url=s.ocr_base_url, timeout=s.ocr_timeout_s, headers=headers, transport=transport)
    return ViettelOcr(http, s.ocr_model, timeout_s=s.ocr_timeout_s), http


def build_asr(s: Settings, transport: httpx.AsyncBaseTransport | None
              ) -> tuple[SpeechRecognizer, httpx.AsyncClient | None]:
    if s.asr_mode == "mock":
        return MockRecognizer(), None
    if s.asr_mode != "openai" or not s.asr_base_url:
        raise RuntimeError("ASR_MODE phải là mock hoặc openai (kèm ASR_BASE_URL)")
    headers = {"Authorization": f"Bearer {s.asr_api_key}"} if s.asr_api_key else {}
    http = httpx.AsyncClient(base_url=s.asr_base_url, timeout=s.asr_timeout_s, headers=headers, transport=transport)
    return OpenAiAsr(http, s.asr_model, concurrency=s.asr_max_concurrency, timeout_s=s.asr_timeout_s), http


def create_app(settings: Settings | None = None, *, http_transport: httpx.AsyncBaseTransport | None = None,
               llm: Llm | None = None, embedder: Embedder | None = None, ocr: Ocr | None = None,
               asr: SpeechRecognizer | None = None) -> FastAPI:
    s = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if not s.database_url or not s.oidc_issuer:
            raise RuntimeError("thiếu DATABASE_URL hoặc OIDC_ISSUER")
        pool = await asyncpg.create_pool(s.database_url, min_size=1, max_size=10)
        opa_http = httpx.AsyncClient(base_url=s.opa_url, timeout=s.opa_timeout_s, transport=http_transport)
        idp_http = httpx.AsyncClient(timeout=5, transport=http_transport)
        core_http = new_http(s.core_base_url or "http://core.invalid", s.core_timeout_s, http_transport)
        model, llm_http = (llm, None) if llm is not None else build_llm(s)
        verifier = JwtVerifier(idp_http, s.oidc_jwks_url, s.oidc_issuer, s.oidc_audience)
        exchanger = TokenExchanger(idp_http, verifier, token_url=s.oidc_token_url, client_id=s.exchange_client_id,
                                   client_secret=s.exchange_client_secret, audience=s.exchange_audience,
                                   scope=s.exchange_scope, required_role=s.exchange_role)
        emb, emb_http = (embedder, None) if embedder is not None else build_embedder(s)
        chunks = ChunkStore(pool)
        core = CoreClient(core_http, exchanger)
        svc_token = ServiceToken(idp_http, verifier, token_url=s.oidc_token_url, client_id=s.exchange_client_id,
                                 client_secret=s.exchange_client_secret, audience=s.exchange_audience,
                                 required_role=s.exchange_role)
        queue = InferenceQueue(model, s.llm_max_concurrency, s.llm_max_waiting)
        indexer = Indexer(pool, chunks, emb, core, svc_token, Extractor(queue, core))
        stop = asyncio.Event()
        consumer_task = (asyncio.create_task(consumer.run(indexer, s.kafka_brokers, stop))
                         if s.kafka_brokers else None)
        store = SuggestionStore(pool)
        gw = Gateway(queue, store,
                     timeout_s=s.llm_timeout_s, sync_wait_s=s.llm_sync_wait_s, deferred_max=s.deferred_max,
                     input_guards=[input_guard], output_guards=[output_guard])
        ocr_engine, ocr_http = (ocr, None) if ocr is not None else build_ocr(s, http_transport)
        objects = (httpx.AsyncClient(base_url=s.object_base_url, timeout=s.ocr_timeout_s, transport=http_transport)
                   if s.object_base_url else None)
        retriever = Retriever(chunks, emb)
        asr_engine, asr_http = (asr, None) if asr is not None else build_asr(s, http_transport)
        speech = SpeechService(asr_engine, store, max_concurrency=s.asr_max_concurrency,
                               max_waiting=s.asr_max_waiting, per_minute=s.asr_per_user_per_minute,
                               disabled=s.ai_disabled)
        app.state.svc = State(verifier=verifier, opa=OpaClient(opa_http), core=core, gateway=gw, store=store,
                              pool=pool, retriever=retriever, indexer=indexer, idempotency=IdempotencyStore(pool),
                              assist=AssistService(gw, store, core, retriever, indexer),
                              evidence=EvidenceOcr(ocr_engine, core, store, objects), ocr_clients=s.ocr_clients,
                              speech=speech)
        try:
            yield
        finally:
            stop.set()
            if consumer_task is not None:
                await asyncio.wait_for(consumer_task, 15)
            for c in (opa_http, idp_http, core_http, llm_http, emb_http, ocr_http, objects, asr_http):
                if c is not None:
                    await c.aclose()
            await pool.close()

    app = FastAPI(title="ai-gateway", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.include_router(routes.probes)
    app.include_router(routes.api)
    app.include_router(assist.router)
    app.include_router(speech_api.router)
    app.add_exception_handler(problems.ApiError, problems.handle_api_error)

    @app.exception_handler(Overloaded)
    async def overloaded(request: Request, _: Exception) -> Response:
        e = problems.too_many(request.url.path)
        return problems.render(e.problem, e.headers)

    @app.exception_handler(LlmError)
    async def llm_down(request: Request, _: Exception) -> Response:
        e = problems.unavailable(request.url.path)
        return problems.render(e.problem, e.headers)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, _: Exception) -> Response:
        return problems.render(problems.make(vp.CODE_BAD_REQUEST, "Yêu cầu không đúng định dạng.", request.url.path))

    @app.exception_handler(Exception)
    async def internal(request: Request, exc: Exception) -> Response:
        log.error("lỗi không xử lý", extra={"err": type(exc).__name__})
        return problems.render(problems.make(vp.CODE_INTERNAL_ERROR, "", request.url.path))

    @app.middleware("http")
    async def trace(request: Request, call_next):  # type: ignore[no-untyped-def]
        tok = set_current_span_context(extract_headers(dict(request.headers)))
        try:
            return await call_next(request)
        finally:
            reset_current_span_context(tok)

    return app


def run() -> None:
    import uvicorn

    configure_logger("ai-gateway")
    uvicorn.run("app.main:create_app", factory=True, host="0.0.0.0", port=8080, workers=2,  # noqa: S104
                proxy_headers=False, log_config=None)

