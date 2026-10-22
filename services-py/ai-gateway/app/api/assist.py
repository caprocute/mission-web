"""API trợ lý của SVC-14 (DV-14-04): requestAiSuggestion, reviewOkrKeyResults, assistOkrDraft, getDocExtraction.
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

from app.api.deps import authorize, state
from app.assist import extract
from app.assist.service import Done, UnsupportedUseCase
from app.core import problems
from app.core.auth import Principal
from app.core.core_client import C3ForbiddenError
from app.core.pipeline import Accepted
from app.guard.guards import MSG_C3
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
