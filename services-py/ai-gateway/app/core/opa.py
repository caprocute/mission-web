"""Bước 1 (tiếp): hỏi OPA cùng pod theo hợp đồng đầu vào của backend/opa/README.md. OPA lỗi thì từ chối."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.core.auth import Principal

log = logging.getLogger(__name__)

DECISION_PATH = "/v1/data/okrkpi/authz/decision"


@dataclass(frozen=True)
class Decision:
    allow: bool
    deny: tuple[str, ...] = ()
    flags: tuple[str, ...] = field(default_factory=tuple)


class OpaClient:
    def __init__(self, http: httpx.AsyncClient) -> None:
        self._http = http

    async def decide(
        self,
        principal: Principal,
        *,
        module: str,
        method: str,
        action: str | None = None,
        resource: dict[str, Any] | None = None,
    ) -> Decision:
        inp: dict[str, Any] = {
            "subject": principal.opa_subject(),
            "request": {"module": module, "method": method},
            "resource": {"tenant_id": principal.tenant_id, **(resource or {})},
            "context": {"now": int(time.time())},
        }
        if action:
            inp["action"] = action
        try:
            r = await self._http.post(DECISION_PATH, json={"input": inp})
            r.raise_for_status()
            res = r.json().get("result") or {}
        except (httpx.HTTPError, ValueError) as e:
            log.error("hỏi OPA thất bại, từ chối", extra={"err": type(e).__name__})
            return Decision(False, ("opa_unavailable",))
        return Decision(
            allow=res.get("allow") is True,
            deny=tuple(str(x) for x in res.get("deny") or ()),
            flags=tuple(str(x) for x in res.get("flags") or ()),
        )
