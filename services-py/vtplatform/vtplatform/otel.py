"""OpenTelemetry resource configuration and W3C Trace Context propagation (TL-01 L2929-2935)."""

from __future__ import annotations

import contextvars
from dataclasses import dataclass, field
import re
import secrets
import time
from typing import Any, Callable, Dict, List, Mapping, MutableMapping, Optional, Tuple


HEADER_TRACEPARENT = "traceparent"
HEADER_TRACESTATE = "tracestate"
TRACE_VERSION_00 = "00"

_TRACEPARENT_RE = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")


@dataclass(frozen=True)
class Config:
    service_name: str
    cell: str
    service_version: str = "1.0.0"
    environment: str = "production"
    otlp_endpoint: str = ""

    def validate(self) -> None:
        if not (self.service_name or "").strip():
            raise ValueError("otel: service.name is required")
        if not (self.cell or "").strip():
            raise ValueError("otel: cell is required")

    def resource_attributes(self) -> Dict[str, str]:
        self.validate()
        return {
            "service.name": self.service_name.strip(),
            "service.version": (self.service_version or "1.0.0").strip(),
            "cell": self.cell.strip(),
            "deployment.environment": (self.environment or "production").strip(),
        }


@dataclass(frozen=True)
class SpanContext:
    trace_id: str
    span_id: str
    parent_id: str = ""
    trace_flags: str = "01"
    trace_state: str = ""

    def is_valid(self) -> bool:
        return (
            len(self.trace_id) == 32
            and self.trace_id != "00000000000000000000000000000000"
            and len(self.span_id) == 16
            and self.span_id != "0000000000000000"
        )

    def traceparent(self) -> str:
        if not self.is_valid():
            return ""
        flags = self.trace_flags if len(self.trace_flags) == 2 else "01"
        return f"{TRACE_VERSION_00}-{self.trace_id}-{self.span_id}-{flags}"


_SPAN_CTX_VAR: contextvars.ContextVar[Optional[SpanContext]] = contextvars.ContextVar(
    "vtplatform_span_context", default=None
)


def parse_traceparent(header: str) -> SpanContext:
    cleaned = (header or "").strip().lower()
    match = _TRACEPARENT_RE.match(cleaned)
    if not match:
        raise ValueError(f"otel: invalid traceparent format: {header!r}")
    version, trace_id, span_id, flags = match.groups()
    if version == "ff":
        raise ValueError("otel: invalid traceparent version ff")
    if trace_id == "00000000000000000000000000000000":
        raise ValueError("otel: trace_id cannot be all zeros")
    if span_id == "0000000000000000":
        raise ValueError("otel: span_id cannot be all zeros")
    return SpanContext(trace_id=trace_id, span_id=span_id, trace_flags=flags)


def new_span_context(parent: Optional[SpanContext] = None) -> SpanContext:
    if parent is not None and parent.is_valid():
        return SpanContext(
            trace_id=parent.trace_id,
            span_id=secrets.token_hex(8),
            parent_id=parent.span_id,
            trace_flags=parent.trace_flags if len(parent.trace_flags) == 2 else "01",
            trace_state=parent.trace_state,
        )
    return SpanContext(
        trace_id=secrets.token_hex(16),
        span_id=secrets.token_hex(8),
        parent_id="",
        trace_flags="01",
        trace_state="",
    )


def set_current_span_context(sc: Optional[SpanContext]) -> contextvars.Token[Optional[SpanContext]]:
    return _SPAN_CTX_VAR.set(sc)


def reset_current_span_context(token: contextvars.Token[Optional[SpanContext]]) -> None:
    _SPAN_CTX_VAR.reset(token)


def current_span_context() -> Optional[SpanContext]:
    sc = _SPAN_CTX_VAR.get()
    if sc is not None and sc.is_valid():
        return sc
    return None


def current_trace_id() -> str:
    sc = current_span_context()
    return sc.trace_id if sc is not None else ""


def current_span_id() -> str:
    sc = current_span_context()
    return sc.span_id if sc is not None else ""


def current_traceparent() -> str:
    sc = current_span_context()
    return sc.traceparent() if sc is not None else ""


def extract_headers(headers: Mapping[str, str]) -> SpanContext:
    lower_map = {k.lower(): v for k, v in headers.items()}
    raw = lower_map.get(HEADER_TRACEPARENT, "")
    if raw:
        try:
            parsed = parse_traceparent(raw)
            child = new_span_context(parsed)
            return SpanContext(
                trace_id=child.trace_id,
                span_id=child.span_id,
                parent_id=child.parent_id,
                trace_flags=child.trace_flags,
                trace_state=lower_map.get(HEADER_TRACESTATE, ""),
            )
        except ValueError:
            pass
    return new_span_context(None)


def inject_headers(headers: MutableMapping[str, str], sc: Optional[SpanContext] = None) -> None:
    target = sc or current_span_context()
    if target is not None and target.is_valid():
        headers[HEADER_TRACEPARENT] = target.traceparent()
        if target.trace_state:
            headers[HEADER_TRACESTATE] = target.trace_state


@dataclass
class RecordedSpan:
    name: str
    context: SpanContext
    start_time: float
    end_time: float
    attributes: Dict[str, str] = field(default_factory=dict)
    error: Optional[str] = None


class Span:
    def __init__(self, provider: "Provider", name: str, sc: SpanContext, attributes: Dict[str, str]) -> None:
        self._provider = provider
        self.name = name
        self.context = sc
        self.start_time = time.time()
        self.attributes = dict(attributes)
        self.error: Optional[str] = None
        self._ended = False
        self._token: Optional[contextvars.Token[Optional[SpanContext]]] = None

    def set_attribute(self, key: str, value: str) -> None:
        self.attributes[key] = str(value)

    def record_error(self, exc: BaseException) -> None:
        self.error = f"{type(exc).__name__}: {exc}"

    def end(self) -> None:
        if self._ended:
            return
        self._ended = True
        if self._token is not None:
            reset_current_span_context(self._token)
            self._token = None
        self._provider._record_span(
            RecordedSpan(
                name=self.name,
                context=self.context,
                start_time=self.start_time,
                end_time=time.time(),
                attributes=dict(self.attributes),
                error=self.error,
            )
        )

    def __enter__(self) -> "Span":
        self._token = set_current_span_context(self.context)
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        if exc_val is not None:
            self.record_error(exc_val)
        self.end()


class Provider:
    """OpenTelemetry provider for Python microservices (SVC-14 ai-analytics-service)."""

    def __init__(self, config: Config) -> None:
        config.validate()
        self.config = config
        self._resource_attrs = config.resource_attributes()
        self._spans: List[RecordedSpan] = []
        self._counters: Dict[str, int] = {}

    def resource_attributes(self) -> Dict[str, str]:
        return dict(self._resource_attrs)

    def start_span(self, name: str, parent: Optional[SpanContext] = None) -> Span:
        effective_parent = parent or current_span_context()
        sc = new_span_context(effective_parent)
        return Span(self, name, sc, self.resource_attributes())

    def add_counter(self, name: str, delta: int = 1) -> None:
        self._counters[name] = self._counters.get(name, 0) + int(delta)

    def counter_value(self, name: str) -> int:
        return self._counters.get(name, 0)

    def recorded_spans(self) -> List[RecordedSpan]:
        return list(self._spans)

    def _record_span(self, span: RecordedSpan) -> None:
        self._spans.append(span)

    def asgi_middleware(self, app: Callable[..., Any]) -> Callable[..., Any]:
        async def _middleware(scope: Dict[str, Any], receive: Callable[..., Any], send: Callable[..., Any]) -> None:
            if scope.get("type") != "http":
                await app(scope, receive, send)
                return
            raw_headers = {
                k.decode("latin1").lower(): v.decode("latin1")
                for k, v in scope.get("headers", [])
            }
            sc = extract_headers(raw_headers)
            method = scope.get("method", "GET")
            path = scope.get("path", "/")
            self.add_counter("http.server.requests", 1)

            async def _send_with_traceparent(message: Dict[str, Any]) -> None:
                if message.get("type") == "http.response.start":
                    headers: List[Tuple[bytes, bytes]] = list(message.get("headers", []))
                    headers.append((HEADER_TRACEPARENT.encode("ascii"), sc.traceparent().encode("ascii")))
                    message = dict(message, headers=headers)
                await send(message)

            with Span(self, f"{method} {path}", sc, self.resource_attributes()) as span:
                span.set_attribute("http.request.method", method)
                span.set_attribute("url.path", path)
                await app(scope, receive, _send_with_traceparent)

        return _middleware


def init_otel(config: Config) -> Provider:
    return Provider(config)
