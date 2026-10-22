"""Probe, metric và phần API chung của SVC-14. Các ca dùng (DV-14-04..06) thêm router riêng dùng Gateway."""

from __future__ import annotations

import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from vtplatform import problem as vp

from app.api.deps import authorize, state
from app.core import problems
from app.core.auth import Principal
from app.core.pipeline import Accepted

probes = APIRouter()
api = APIRouter(prefix="/api/v1/ai")


@probes.get("/healthz", include_in_schema=False)
async def healthz() -> dict[str, str]:
    return {"status": "UP"}


@probes.get("/readyz", include_in_schema=False)
async def readyz(request: Request) -> Response:
    try:
        async with state(request).pool.acquire() as c:
            await c.fetchval("SELECT 1")
    except Exception:
        return JSONResponse({"status": "DOWN"}, status_code=503)
    return JSONResponse({"status": "UP"})


@probes.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


def accepted(a: Accepted) -> JSONResponse:
    return JSONResponse(a.job(), status_code=202, headers={"Location": a.location, "Retry-After": "5"})


def result(r: dict[str, Any] | Accepted) -> JSONResponse:
    return accepted(r) if isinstance(r, Accepted) else JSONResponse(r)


@api.get("/suggestions/{suggestion_id}")
async def get_suggestion(
    suggestion_id: str, request: Request, p: Annotated[Principal, Depends(authorize())]
) -> JSONResponse:
    """getAiSuggestion: chỉ người hỏi đọc được gợi ý của mình; là resultLocation của job khi trả 202."""
    try:
        sid = str(uuid.UUID(suggestion_id))
    except ValueError:
        sid = ""
    row = await state(request).store.get_own(p.tenant_id, p.user_id, sid) if sid else None
    if row is None:
        raise problems.ApiError(problems.make(vp.CODE_NOT_FOUND, "Không tìm thấy gợi ý.", request.url.path))
    return JSONResponse(row)
