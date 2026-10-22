"""Bước 1 dưới dạng phụ thuộc FastAPI: kiểm JWT rồi hỏi OPA với module `ai` và phương thức HTTP (ma trận API
của docs/phan-quyen/ma-tran.json). Quyền không đặt danh sách vai trò trong mã (BACKEND.md quy định 6)."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable

from fastapi import Request

from app.core import problems
from app.core.auth import AuthError, Principal
from app.core.opa import Decision
from app.core.state import State

log = logging.getLogger(__name__)

MODULE = "ai"


def state(request: Request) -> State:
    s: State = request.app.state.svc
    return s


async def authenticate(request: Request) -> Principal:
    h = request.headers.get("authorization", "")
    scheme, _, tok = h.partition(" ")
    if scheme.lower() != "bearer" or not tok.strip():
        raise problems.unauthorized(request.url.path)
    try:
        return await state(request).verifier.verify(tok.strip())
    except AuthError as e:
        log.info("token bị từ chối", extra={"reason": str(e)})
        raise problems.unauthorized(request.url.path) from e


def authorize(action: str | None = None) -> Callable[[Request], Awaitable[Principal]]:
    async def dep(request: Request) -> Principal:
        p = await authenticate(request)
        d: Decision = await state(request).opa.decide(p, module=MODULE, method=request.method, action=action)
        if not d.allow:
            log.info("OPA từ chối", extra={"deny": ",".join(d.deny)})
            if "opa_unavailable" in d.deny:
                raise problems.unavailable(request.url.path)
            raise problems.forbidden(request.url.path)
        request.state.flags = d.flags
        return p

    return dep
