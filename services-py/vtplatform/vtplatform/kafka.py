"""Kafka idempotent inbox consumer (`inbox_processed`, `inbox_aggregate_version`)
and direct producer for SVC-14 `ai-analytics-service` (`EVT-20 DauHieuBatThuong` with deterministic UUID from `alertKey`)."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
import json
import re
from typing import Any, Awaitable, Callable, Dict, List, Optional
import uuid

from .db import SafeAsyncTx, in_tenant_tx, parse_rows_affected
from .otel import current_traceparent, new_span_context, parse_traceparent, set_current_span_context, reset_current_span_context
from .tenant import must_get_tenant_id, normalize_uuid


CLOUDEVENTS_SPEC_VERSION = "1.0"
CLASSIFICATION_C0 = "C0"
CLASSIFICATION_C1 = "C1"

EVT_20_CODE = "EVT-20"
EVT_20_TOPIC = "analytics.anomaly.detected.v1"
EVT_20_SCHEMA_NAME = "vn.viettel.okrkpi.events.analytics.DauHieuBatThuong"
# Dedicated UUIDv5 namespace for deterministic event_id derivation from alertKey on EVT-20
EVT_20_ALERT_NAMESPACE = uuid.UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

_SCHEMA_IDENT_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")


class InvalidClassificationError(ValueError):
    """Raised when a Kafka producer attempts to publish C2 or C3 classified data."""


class InboxOutcome(str, Enum):
    PROCESSED = "PROCESSED"
    DUPLICATE_EVENT = "DUPLICATE_EVENT"
    STALE_VERSION = "STALE_VERSION"


@dataclass
class IncomingEvent:
    event_id: str
    tenant_id: str
    payload: bytes
    aggregate_type: str = ""
    aggregate_id: str = ""
    aggregate_version: int = 0
    source_topic: str = "unknown"
    partition: int = 0
    offset: int = 0
    traceparent: str = ""

    def validate(self) -> None:
        self.event_id = normalize_uuid(self.event_id)
        self.tenant_id = normalize_uuid(self.tenant_id)
        if not (self.source_topic or "").strip():
            self.source_topic = "unknown"


InboxHandler = Callable[[SafeAsyncTx, IncomingEvent], Awaitable[None]]


class InboxGuard:
    """Enforces idempotency via `<schema>.inbox_processed` and monotonic aggregate version via `<schema>.inbox_aggregate_version`."""

    def __init__(self, schema: str, consumer: str) -> None:
        cleaned_schema = (schema or "").strip()
        if not _SCHEMA_IDENT_RE.match(cleaned_schema):
            raise ValueError(f"inbox: invalid PostgreSQL schema identifier: {schema!r}")
        cleaned_consumer = (consumer or "").strip()
        if not cleaned_consumer:
            raise ValueError("inbox: consumer name is required")
        self.schema = cleaned_schema
        self.consumer = cleaned_consumer

    async def process_in_tx(self, tx: SafeAsyncTx, event: IncomingEvent, handler: InboxHandler) -> InboxOutcome:
        event.validate()
        ctx_tenant = must_get_tenant_id()
        if ctx_tenant != event.tenant_id:
            raise ValueError(
                f"inbox: event tenant_id {event.tenant_id!r} does not match transaction tenant_id {ctx_tenant!r}"
            )

        token = None
        if event.traceparent:
            try:
                parsed = parse_traceparent(event.traceparent)
                token = set_current_span_context(new_span_context(parsed))
            except ValueError:
                token = None

        try:
            now = datetime.now(timezone.utc)
            insert_processed_sql = (
                f"INSERT INTO {self.schema}.inbox_processed "
                "(event_id, consumer, processed_at, tenant_id, source_topic, kafka_partition, kafka_offset) "
                "VALUES ($1, $2, $3, $4, $5, $6, $7) "
                "ON CONFLICT (consumer, event_id) DO NOTHING"
            )
            tag = await tx.execute(
                insert_processed_sql,
                event.event_id,
                self.consumer,
                now,
                event.tenant_id,
                event.source_topic,
                int(event.partition),
                int(event.offset),
            )
            if parse_rows_affected(tag) == 0:
                return InboxOutcome.DUPLICATE_EVENT

            if event.aggregate_type.strip() and event.aggregate_id.strip() and event.aggregate_version >= 1:
                upsert_version_sql = (
                    f"INSERT INTO {self.schema}.inbox_aggregate_version "
                    "(consumer, aggregate_type, aggregate_id, tenant_id, last_version, last_event_id, updated_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7) "
                    "ON CONFLICT (consumer, aggregate_type, aggregate_id) DO UPDATE SET "
                    "last_version = EXCLUDED.last_version, "
                    "last_event_id = EXCLUDED.last_event_id, "
                    "updated_at = EXCLUDED.updated_at "
                    f"WHERE {self.schema}.inbox_aggregate_version.last_version < EXCLUDED.last_version"
                )
                v_tag = await tx.execute(
                    upsert_version_sql,
                    self.consumer,
                    event.aggregate_type.strip(),
                    event.aggregate_id.strip(),
                    event.tenant_id,
                    int(event.aggregate_version),
                    event.event_id,
                    now,
                )
                if parse_rows_affected(v_tag) == 0:
                    return InboxOutcome.STALE_VERSION

            await handler(tx, event)
            return InboxOutcome.PROCESSED
        finally:
            if token is not None:
                reset_current_span_context(token)

    async def process(self, pool: Any, event: IncomingEvent, handler: InboxHandler) -> InboxOutcome:
        event.validate()
        async with in_tenant_tx(pool, event.tenant_id) as tx:
            return await self.process_in_tx(tx, event, handler)


# =============================================================================
# Direct Producer for SVC-14 ai-analytics-service (EVT-20 DauHieuBatThuong)
# =============================================================================


def deterministic_event_id_from_alert_key(tenant_id: str, alert_key: str) -> str:
    """Derives a deterministic UUIDv5 `event_id` (`ce_id`) from `(tenant_id, alert_key)`.

    Because SVC-14 (`ai-analytics-service`) is stateless and publishes `EVT-20` directly to Kafka
    without a database outbox table, re-runs of the anomaly detector for the same `alertKey`
    produce the exact same `event_id`, allowing downstream consumers' `inbox_processed` table
    to deduplicate repeat alerts deterministically.
    """
    norm_tenant = normalize_uuid(tenant_id)
    cleaned_key = (alert_key or "").strip()
    if not cleaned_key:
        raise ValueError("kafka: alert_key must not be empty")
    return str(uuid.uuid5(EVT_20_ALERT_NAMESPACE, f"{norm_tenant}:{cleaned_key}"))


@dataclass
class AnomalyAlertPayload:
    """Matches Avro schema `contracts/events/avro/analytics/DauHieuBatThuong.avsc` (`EVT-20`)."""

    alert_key: str
    signal_code: str
    severity: str
    subject_type: str
    subject_id: str
    evidence_chain: List[str]
    model_version: str

    def validate(self) -> None:
        if not (self.alert_key or "").strip():
            raise ValueError("kafka: alertKey is required")
        if self.signal_code not in {"CB_01", "CB_02", "CB_03", "CB_04", "CB_05"}:
            raise ValueError(f"kafka: invalid signalCode {self.signal_code!r}")
        if self.severity not in {"MEDIUM", "HIGH", "CRITICAL"}:
            raise ValueError(f"kafka: invalid severity {self.severity!r}")
        if self.subject_type not in {"TASK", "KEY_RESULT", "ORG_UNIT", "EVALUATION_PERIOD"}:
            raise ValueError(f"kafka: invalid subjectType {self.subject_type!r}")
        if not (self.subject_id or "").strip():
            raise ValueError("kafka: subjectId is required")
        if not self.evidence_chain or any(not str(x).strip() for x in self.evidence_chain):
            raise ValueError("kafka: evidenceChain must be a non-empty list of IDs")
        if not (self.model_version or "").strip():
            raise ValueError("kafka: modelVersion is required")

    def to_avro_dict(self) -> Dict[str, Any]:
        self.validate()
        return {
            "alertKey": self.alert_key.strip(),
            "signalCode": self.signal_code,
            "severity": self.severity,
            "subjectType": self.subject_type,
            "subjectId": self.subject_id.strip(),
            "evidenceChain": [str(x).strip() for x in self.evidence_chain],
            "modelVersion": self.model_version.strip(),
        }


@dataclass
class ProducedKafkaRecord:
    topic: str
    key: str
    value: bytes
    headers: Dict[str, str] = field(default_factory=dict)


class DirectProducer:
    """Direct Kafka producer for `SVC-14 ai-analytics-service` (no DB outbox)."""

    def __init__(
        self,
        source: str = "urn:viettel:okrkpi:svc-14:ai-analytics",
        send_fn: Optional[Callable[[ProducedKafkaRecord], Awaitable[None]]] = None,
    ) -> None:
        self.source = (source or "urn:viettel:okrkpi:svc-14:ai-analytics").strip()
        self._send_fn = send_fn
        self._published: List[ProducedKafkaRecord] = []

    @property
    def published_records(self) -> List[ProducedKafkaRecord]:
        return list(self._published)

    async def publish_anomaly_alert(
        self,
        payload: AnomalyAlertPayload,
        tenant_id: Optional[str] = None,
        classification: str = CLASSIFICATION_C1,
        aggregate_version: int = 1,
        schema_version: int = 1,
    ) -> ProducedKafkaRecord:
        cls = (classification or "").strip().upper()
        if cls not in {CLASSIFICATION_C0, CLASSIFICATION_C1}:
            raise InvalidClassificationError(
                f"kafka: classification must be C0 or C1; C2/C3 payloads are forbidden on Kafka (got {classification!r})"
            )
        effective_tenant = normalize_uuid(tenant_id) if tenant_id else must_get_tenant_id()
        payload.validate()

        event_id = deterministic_event_id_from_alert_key(effective_tenant, payload.alert_key)
        now_iso = datetime.now(timezone.utc).isoformat()
        headers: Dict[str, str] = {
            "ce_specversion": CLOUDEVENTS_SPEC_VERSION,
            "ce_id": event_id,
            "ce_source": self.source,
            "ce_type": EVT_20_CODE,
            "ce_time": now_iso,
            "ce_datacontenttype": "application/json",
            "ce_dataschema": EVT_20_SCHEMA_NAME,
            "tenant_id": effective_tenant,
            "aggregate_type": payload.subject_type,
            "aggregate_id": payload.subject_id.strip(),
            "aggregate_version": str(max(1, int(aggregate_version))),
            "schema_version": str(max(1, int(schema_version))),
            "classification": cls,
            "alert_key": payload.alert_key.strip(),
        }
        tp = current_traceparent()
        if tp:
            headers["traceparent"] = tp

        body = json.dumps(payload.to_avro_dict(), ensure_ascii=False).encode("utf-8")
        record = ProducedKafkaRecord(
            topic=EVT_20_TOPIC,
            key=f"{effective_tenant}:{payload.subject_id.strip()}",
            value=body,
            headers=headers,
        )
        if self._send_fn is not None:
            await self._send_fn(record)
        self._published.append(record)
        return record
