"""Structured JSON logging with trace.id, span.id, tenant.id injection and PII scrubbing (JWT, CCCD, phone, email)."""

from __future__ import annotations

from datetime import datetime, timezone
import io
import json
import logging
import re
import sys
from typing import Any, Dict, Mapping, Optional

from .otel import current_span_id, current_trace_id
from .tenant import get_optional_actor_id, get_optional_tenant_id


REDACTED_JWT = "[REDACTED_JWT]"
REDACTED_CCCD = "[REDACTED_CCCD]"
REDACTED_PHONE = "[REDACTED_PHONE]"
REDACTED_EMAIL = "[REDACTED_EMAIL]"
REDACTED_SECRET = "[REDACTED_SECRET]"

_JWT_RE = re.compile(r"(?i)(?:bearer\s+)?eyJ[A-Za-z0-9_-]{4,}\.eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*")
_EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}")
_CCCD_RE = re.compile(r"\b\d{12}\b")
_PHONE_RE = re.compile(r"(?:\+84|84|0)(?:3|5|7|8|9)\d{8}\b")

_SENSITIVE_KEYS: Dict[str, str] = {
    "authorization": REDACTED_JWT,
    "access_token": REDACTED_JWT,
    "refresh_token": REDACTED_JWT,
    "id_token": REDACTED_JWT,
    "token": REDACTED_JWT,
    "password": REDACTED_SECRET,
    "secret": REDACTED_SECRET,
    "client_secret": REDACTED_SECRET,
    "api_key": REDACTED_SECRET,
    "cccd": REDACTED_CCCD,
    "national_id": REDACTED_CCCD,
    "citizen_id": REDACTED_CCCD,
    "phone": REDACTED_PHONE,
    "phone_number": REDACTED_PHONE,
    "so_dien_thoai": REDACTED_PHONE,
    "email": REDACTED_EMAIL,
}


def scrub_text(text: str) -> str:
    if not text:
        return ""
    out = _JWT_RE.sub(REDACTED_JWT, text)
    out = _EMAIL_RE.sub(REDACTED_EMAIL, out)
    out = _CCCD_RE.sub(REDACTED_CCCD, out)
    out = _PHONE_RE.sub(REDACTED_PHONE, out)
    return out


def scrub_value(key: Optional[str], value: Any) -> Any:
    if key:
        lower_key = key.strip().lower()
        if lower_key in _SENSITIVE_KEYS and not isinstance(value, Mapping):
            return _SENSITIVE_KEYS[lower_key]
    if isinstance(value, str):
        return scrub_text(value)
    if isinstance(value, Mapping):
        return {str(k): scrub_value(str(k), v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [scrub_value(None, item) for item in value]
    return value


class JSONLogFormatter(logging.Formatter):
    """Formats log records as single-line JSON with trace.id, span.id, tenant.id and PII scrubbing."""

    def __init__(self, service_name: str = "ai-analytics-service", cell: str = "cell-default") -> None:
        super().__init__()
        self.service_name = (service_name or "ai-analytics-service").strip()
        self.cell = (cell or "cell-default").strip()

    def format(self, record: logging.LogRecord) -> str:
        ts = datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat()
        payload: Dict[str, Any] = {
            "time": ts,
            "level": record.levelname,
            "logger": record.name,
            "msg": scrub_text(record.getMessage()),
            "service.name": self.service_name,
            "cell": self.cell,
        }
        trace_id = current_trace_id()
        if trace_id:
            payload["trace.id"] = trace_id
        span_id = current_span_id()
        if span_id:
            payload["span.id"] = span_id
        tenant_id = get_optional_tenant_id()
        if tenant_id:
            payload["tenant.id"] = tenant_id
        actor_id = get_optional_actor_id()
        if actor_id:
            payload["actor.id"] = actor_id

        extra_fields = getattr(record, "vt_fields", None)
        if isinstance(extra_fields, Mapping):
            for k, v in extra_fields.items():
                if k not in payload:
                    payload[str(k)] = scrub_value(str(k), v)

        if record.exc_info:
            exc_text = self.formatException(record.exc_info)
            payload["error"] = scrub_text(exc_text)

        return json.dumps(payload, ensure_ascii=False)


def configure_logger(
    name: str = "vtplatform",
    service_name: str = "ai-analytics-service",
    cell: str = "cell-default",
    level: int = logging.INFO,
    stream: Optional[io.TextIOBase] = None,
) -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(level)
    logger.propagate = False
    logger.handlers.clear()
    handler = logging.StreamHandler(stream or sys.stdout)
    handler.setLevel(level)
    handler.setFormatter(JSONLogFormatter(service_name=service_name, cell=cell))
    logger.addHandler(handler)
    return logger
