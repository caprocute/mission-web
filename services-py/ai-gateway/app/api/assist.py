"""API trợ lý của SVC-14 (DV-14-04, DV-14-06): requestAiSuggestion, reviewOkrKeyResults, assistOkrDraft,
getDocExtraction, listAiSuggestions, listAiSuggestionSources, decideAiSuggestion.
POST bắt buộc `Idempotency-Key`; thân yêu cầu kiểm theo lược đồ hợp đồng (app/assist/schemas.json), sai thì 422.
Kết quả 200 khi xong trong thời gian chờ, 202 kèm job khi hàng đợi suy luận đầy (MSG-17-03)."""

from __future__ import annotations

import json
import pathlib
import uuid
from collections.abc import Awaitable, Callable
from typing import Annotated, Any

import jsonschema
from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse
from vtplatform import problem as vp
from vtplatform.db import in_tenant_tx

from app.api.deps import authenticate, authorize, state
from app.assist import decisions, evidence, extract
from app.assist.service import Done, UnsupportedUseCase
from app.core import problems
from app.core.auth import Principal
from app.core.core_client import C3ForbiddenError
from app.core.pipeline import Accepted
from app.guard.guards import MSG_C3
from app.ocr.client import OcrUnavailable, Unreadable
from app.store.idempotency import IdempotencyInProgress, IdempotencyMismatch, request_hash

SCHEMAS: dict[str, Any] = json.loads((pathlib.Path(__file__).resolve().parents[1] / "assist" / "schemas.json")
                                     .read_text(encoding="utf-8"))
CODE_IDEM_MISMATCH = "COMMON-422-IDEMPOTENCY-MISMATCH"
CODE_VALIDATION = "COMMON-422-VALIDATION"

router = APIRouter(prefix="/api/v1/ai")


def _problem(code: str, detail: str, path: str) -> problems.ApiError:
    return problems.ApiError(problems.make(code, detail, path))


async def _body(request: Request, schema: str) -> dict[str, Any]:
    try:
        body = json.loads(await request.body() or b"null")
    except ValueError as e:
        raise _problem(vp.CODE_BAD_REQUEST, "Yêu cầu không đúng định dạng.", request.url.path) from e
    try:
        jsonschema.validate(body, SCHEMAS[schema], format_checker=jsonschema.FormatChecker())
    except jsonschema.ValidationError as e:
        raise _problem(CODE_VALIDATION, "Dữ liệu gửi lên chưa hợp lệ.", request.url.path) from e
    return dict(body)


Handler = Callable[[Principal, dict[str, Any]], Awaitable[Done | Accepted]]


async def _idempotent(request: Request, p: Principal, schema: str, handler: Handler) -> JSONResponse:
    path = request.url.path
    key = (request.headers.get("idempotency-key") or "").strip()
    if not key or len(key) > 64:
        raise _problem(vp.CODE_BAD_REQUEST, "Thiếu khoá chống gửi trùng của yêu cầu.", path)
    body = await _body(request, schema)
    idem = state(request).idempotency
    try:
        stored = await idem.begin(p.tenant_id, p.user_id, key, request_hash("POST", path, body))
    except IdempotencyMismatch as e:
        raise _problem(CODE_IDEM_MISMATCH, "Khoá chống gửi trùng đã dùng cho một yêu cầu khác.", path) from e
    except IdempotencyInProgress as e:
        raise _problem(vp.CODE_CONFLICT, "Yêu cầu trước với cùng khoá đang được xử lý.", path) from e
    if stored is not None:
        headers = {"Location": stored.location, "Retry-After": "5"} if stored.code == 202 and stored.location else {}
        return JSONResponse(stored.body, status_code=stored.code, headers=headers)
    try:
        res = await handler(p, body)
    except BaseException:
        await idem.release(p.tenant_id, p.user_id, key)
        raise
    if isinstance(res, Accepted):
        await idem.finish(p.tenant_id, p.user_id, key, 202, res.job(), res.location)
        return JSONResponse(res.job(), status_code=202, headers={"Location": res.location, "Retry-After": "5"})
    await idem.finish(p.tenant_id, p.user_id, key, 200, res.body, None)
    return JSONResponse(res.body)


def _c3(path: str) -> problems.ApiError:
    return _problem(vp.CODE_FORBIDDEN, MSG_C3, path)


@router.post("/suggestions")
async def request_suggestion(request: Request, p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    async def run(pr: Principal, body: dict[str, Any]) -> Done | Accepted:
        try:
            return await state(request).assist.ask(pr, body)
        except UnsupportedUseCase as e:
            raise _problem(CODE_VALIDATION, "Loại câu hỏi này chưa được trợ lý hỗ trợ.", request.url.path) from e
        except C3ForbiddenError as e:
            raise _c3(request.url.path) from e

    return await _idempotent(request, p, "AiSuggestionRequest", run)


@router.post("/okr/review")
async def review_okr(request: Request, p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    return await _idempotent(request, p, "OkrReviewRequest", state(request).assist.review_okr)


@router.post("/assist/okr")
async def assist_okr(request: Request, p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    async def run(pr: Principal, body: dict[str, Any]) -> Done | Accepted:
        try:
            return await state(request).assist.assist_okr(pr, body)
        except C3ForbiddenError as e:
            raise _c3(request.url.path) from e

    return await _idempotent(request, p, "OkrAssistRequest", run)


@router.get("/extractions")
async def get_extraction(request: Request, p: Annotated[Principal, Depends(authorize())],
                         source_doc_id: Annotated[str, Query(alias="sourceDocId")]) -> JSONResponse:
    try:
        doc_id = str(uuid.UUID(source_doc_id))
    except ValueError as e:
        raise _problem(vp.CODE_BAD_REQUEST, "Mã văn bản không hợp lệ.", request.url.path) from e
    async with in_tenant_tx(state(request).pool, p.tenant_id) as tx:
        out = await extract.read(tx, p.tenant_id, doc_id)
    if out is None:
        raise _problem(vp.CODE_NOT_FOUND, "Chưa có kết quả trích nhiệm vụ cho văn bản này.", request.url.path)
    return JSONResponse(out)


@router.get("/suggestions")
async def list_suggestions(
    request: Request, p: Annotated[Principal, Depends(authorize())],
    kind: Annotated[str | None, Query(max_length=24)] = None,
    use_case: Annotated[str | None, Query(alias="useCase", max_length=32)] = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 20,
    cursor: Annotated[str | None, Query(max_length=200)] = None,
) -> JSONResponse:
    """listAiSuggestions: gợi ý của chính người gọi; `unitId`, `periodId` của hợp đồng nhận nhưng chưa lọc vì
    tóm tắt tuần là theo người nhận (suy luận, wiki/14)."""
    try:
        async with in_tenant_tx(state(request).pool, p.tenant_id) as tx:
            out = await decisions.page(tx, p.tenant_id, p.user_id, kind=kind, use_case=use_case, limit=limit,
                                       cursor=cursor)
    except ValueError as e:
        raise _problem(vp.CODE_BAD_REQUEST, "Con trỏ trang không hợp lệ.", request.url.path) from e
    return JSONResponse(out)


def _sid(raw: str, path: str) -> str:
    try:
        return str(uuid.UUID(raw))
    except ValueError as e:
        raise _problem(vp.CODE_NOT_FOUND, "Không tìm thấy gợi ý.", path) from e


@router.get("/suggestions/{suggestion_id}/sources")
async def suggestion_sources(suggestion_id: str, request: Request,
                             p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    sid = _sid(suggestion_id, request.url.path)
    try:
        async with in_tenant_tx(state(request).pool, p.tenant_id) as tx:
            out = await decisions.sources(tx, p.tenant_id, p.user_id, state(request).retriever.readers(p), sid)
    except decisions.NotFound as e:
        raise _problem(vp.CODE_NOT_FOUND, "Không tìm thấy gợi ý.", request.url.path) from e
    except decisions.NotRecipient as e:
        raise problems.forbidden(request.url.path) from e
    return JSONResponse(out)


@router.post("/suggestions/{suggestion_id}/decision")
async def decide_suggestion(suggestion_id: str, request: Request,
                            p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    path = request.url.path
    sid = _sid(suggestion_id, path)

    async def run(pr: Principal, body: dict[str, Any]) -> Done | Accepted:
        try:
            async with in_tenant_tx(state(request).pool, pr.tenant_id) as tx:
                return Done(await decisions.decide(tx, pr.tenant_id, pr.user_id, sid, body))
        except decisions.NotFound as e:
            raise _problem(vp.CODE_NOT_FOUND, "Không tìm thấy gợi ý.", path) from e
        except decisions.NotRecipient as e:
            raise problems.forbidden(path) from e
        except decisions.AlreadyDecided as e:
            raise _problem("AI-409-ALREADY-DECIDED", "Gợi ý này đã có quyết định.", path) from e
        except decisions.ReasonRequired as e:
            raise _problem("AI-422-REASON-REQUIRED", "Đề nghị nêu lý do bỏ nhiệm vụ này.", path) from e

    return await _idempotent(request, p, "AiSuggestionDecisionRequest", run)


def _ocr_errors(path: str, e: Exception) -> problems.ApiError:
    if isinstance(e, evidence.C3Refused | C3ForbiddenError):
        return _problem(problems.CODE_C3, MSG_C3, path)
    if isinstance(e, Unreadable):
        return _problem("AI-422-UNREADABLE", "Ảnh chưa đủ nét để đọc. Đề nghị đồng chí chụp lại rõ hơn.", path)
    if isinstance(e, evidence.TooLarge):
        return _problem(CODE_VALIDATION, "Tệp vượt quá 20 MB.", path)
    return problems.unavailable(path)


@router.post("/evidence/extract")
async def extract_evidence(request: Request, p: Annotated[Principal, Depends(authorize())]) -> JSONResponse:
    async def run(pr: Principal, body: dict[str, Any]) -> Done | Accepted:
        try:
            return await state(request).evidence.extract(pr, body)
        except (evidence.C3Refused, C3ForbiddenError, Unreadable, evidence.TooLarge, OcrUnavailable) as e:
            raise _ocr_errors(request.url.path, e) from e

    return await _idempotent(request, p, "EvidenceExtractRequest", run)


async def service_client(request: Request) -> Principal:
    """ocrEvidenceObject chỉ cho tài khoản dịch vụ evidence-service, search-indexer (vai trò service_client của
    hợp đồng); lớp mTLS của lưới dịch vụ chặn trước ở tầng mạng."""
    p = await authenticate(request)
    if p.client_id not in state(request).ocr_clients:
        raise problems.forbidden(request.url.path)
    return p


@router.post("/ocr")
async def ocr_object(request: Request, p: Annotated[Principal, Depends(service_client)]) -> JSONResponse:
    async def run(_: Principal, body: dict[str, Any]) -> Done | Accepted:
        try:
            return Done(await state(request).evidence.ocr_object(body))
        except (evidence.C3Refused, Unreadable, evidence.TooLarge, OcrUnavailable) as e:
            raise _ocr_errors(request.url.path, e) from e

    raw = json.loads(await request.body() or b"null")
    if isinstance(raw, dict) and raw.get("classification") == "C3":  # BR-17-07: 403 trước khi kiểm lược đồ
        raise _problem(problems.CODE_C3, MSG_C3, request.url.path)
    return await _idempotent(request, p, "AiOcrRequest", run)
