"""Consumer EVT-24 `integ.document.received.v1` (consumer group `ai-gateway`). Thân sự kiện là Avro nhị phân theo
contracts/events/avro/integ/VanBanChiDaoDaNhan.avsc (bản chép ở schemas/, có kiểm thử so khớp); tiêu đề
CloudEvents `id`, `tenantid` bắt buộc. Lỗi tạm thời thử lại bốn lần rồi chuyển thư chết
`<topic>.dlq.ai-gateway`; offset chỉ được xác nhận sau khi bản ghi đã xử lý xong."""

from __future__ import annotations

import asyncio
import io
import json
import logging
import pathlib
from typing import Any

import fastavro
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from prometheus_client import Counter
from vtplatform.kafka import InboxOutcome, IncomingEvent

from app.rag.ingest import CONSUMER, TOPIC_EVT24, Indexer

log = logging.getLogger(__name__)

SCHEMA_PATH = pathlib.Path(__file__).resolve().parent / "schemas" / "VanBanChiDaoDaNhan.avsc"
SCHEMA = fastavro.parse_schema(json.loads(SCHEMA_PATH.read_text(encoding="utf-8")))
MAX_ATTEMPTS = 4

INDEXED = Counter("ai_documents_indexed_total", "Số văn bản đã lập chỉ mục", ["outcome"])
DLQ = Counter("ai_events_dlq_total", "Số sự kiện chuyển thư chết")


class PermanentError(Exception):
    """Bản ghi hỏng, thử lại vô ích."""


def decode(value: bytes) -> dict[str, Any]:
    try:
        rec: dict[str, Any] = fastavro.schemaless_reader(io.BytesIO(value), SCHEMA)  # type: ignore[assignment]
    except Exception as e:
        raise PermanentError("thân sự kiện không đúng lược đồ Avro") from e
    return rec


def encode(rec: dict[str, Any]) -> bytes:
    buf = io.BytesIO()
    fastavro.schemaless_writer(buf, SCHEMA, rec)
    return buf.getvalue()


def to_event(topic: str, partition: int, offset: int, headers: dict[str, str]) -> IncomingEvent:
    if not headers.get("id") or not headers.get("tenantid"):
        raise PermanentError("thiếu tiêu đề id hoặc tenantid")
    try:
        ev = IncomingEvent(event_id=headers["id"], tenant_id=headers["tenantid"], payload=b"", source_topic=topic,
                           partition=partition, offset=offset, traceparent=headers.get("traceparent", ""))
        ev.validate()
    except ValueError as e:
        raise PermanentError("tiêu đề id hoặc tenantid không hợp lệ") from e
    return ev


async def process(indexer: Indexer, topic: str, partition: int, offset: int, headers: dict[str, str],
                  value: bytes) -> InboxOutcome:
    ev = to_event(topic, partition, offset, headers)
    out = await indexer.handle_document_received(ev, decode(value))
    INDEXED.labels(out.value).inc()
    return out


async def run(indexer: Indexer, brokers: str, stop: asyncio.Event, *, backoff_s: float = 0.5) -> None:
    consumer = AIOKafkaConsumer(TOPIC_EVT24, bootstrap_servers=brokers, group_id=CONSUMER, enable_auto_commit=False,
                                auto_offset_reset="earliest", isolation_level="read_committed")
    producer = AIOKafkaProducer(bootstrap_servers=brokers, enable_idempotence=True)
    await consumer.start()
    await producer.start()
    try:
        while not stop.is_set():
            batch = await consumer.getmany(timeout_ms=1000, max_records=50)
            for tp, msgs in batch.items():
                for m in msgs:
                    headers = {k: v.decode("utf-8", "replace") for k, v in (m.headers or [])}
                    for attempt in range(1, MAX_ATTEMPTS + 1):
                        try:
                            await process(indexer, m.topic, m.partition, m.offset, headers, m.value or b"")
                            break
                        except PermanentError as e:
                            await _dlq(producer, m, str(e))
                            break
                        except Exception as e:
                            if attempt == MAX_ATTEMPTS:
                                await _dlq(producer, m, type(e).__name__)
                                break
                            log.warning("xử lý EVT-24 lỗi, thử lại",
                                        extra={"attempt": attempt, "err": type(e).__name__})
                            await asyncio.sleep(backoff_s * 2 ** (attempt - 1))
                    await consumer.commit({tp: m.offset + 1})
    finally:
        await consumer.stop()
        await producer.stop()


async def _dlq(producer: AIOKafkaProducer, m: Any, reason: str) -> None:
    DLQ.inc()
    log.error("chuyển EVT-24 sang thư chết", extra={"reason": reason, "offset": m.offset})
    await producer.send_and_wait(f"{m.topic}.dlq.{CONSUMER}", m.value, key=m.key,
                                 headers=[*(m.headers or []), ("dlq-reason", reason.encode())])
