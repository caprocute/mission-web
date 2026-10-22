"""Producer EVT-20 `analytics.anomaly.detected.v1` (TL-03 L4468): đọc dòng chưa phát của `anomaly_out`, mã hoá Avro
theo lược đồ đã đăng ký trong Apicurio, phát lên Kafka rồi ghi bản đã phát khi Kafka xác nhận.

Định dạng trên Kafka giống outbox của mission-core (NEN-007, NEN-012): thân Avro nhị phân không tiền tố, global id lược
đồ ở tiêu đề `apicurio.value.globalId` (8 byte big-endian), phong bì CloudEvents ở tiêu đề (`id`, `tenantid`,
`ce_type`, `classification`, `occurredat`), khoá `tenant:subjectId`. `id` là UUIDv5 tất định của (tenant, alertKey),
cùng cách `vtplatform.kafka.deterministic_event_id_from_alert_key`, nên phát lại không sinh cảnh báo trùng ở MOD-07."""

from __future__ import annotations

import io
import json
import logging
import pathlib
import uuid
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any

import fastavro
import httpx

from app.clickhouse import ClickHouse

log = logging.getLogger(__name__)

TOPIC = "analytics.anomaly.detected.v1"
ARTIFACT_ID = f"{TOPIC}-value"
EVENT_TYPE = "analytics.anomaly.detected.v1"
ALERT_NAMESPACE = uuid.UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
SUBJECT_TYPES = {"CA_NHAN", "DON_VI", "NGUOI_DUYET"}
SEVERITIES = {"CAO", "VUA", "THAP"}


class PublishError(RuntimeError):
    pass


def event_id(tenant: str, alert_key: str) -> str:
    return str(uuid.uuid5(ALERT_NAMESPACE, f"{uuid.UUID(tenant)!s}:{alert_key.strip()}"))


class Evt20Codec:
    def __init__(self, schema_path: pathlib.Path) -> None:
        self.schema_text = schema_path.read_text(encoding="utf-8")
        self.schema = fastavro.parse_schema(json.loads(self.schema_text))

    def encode(self, row: Mapping[str, Any]) -> bytes:
        if row["subject_type"] not in SUBJECT_TYPES or row["severity"] not in SEVERITIES:
            raise PublishError(f"dòng {row['alert_key']} có mức hoặc loại đối tượng ngoài hợp đồng")
        chain = json.loads(row["evidence_chain"])
        if not isinstance(chain, list) or not all(isinstance(x, str) for x in chain):
            raise PublishError(f"dòng {row['alert_key']} có chuỗi bằng chứng không phải mảng mã")
        record = {
            "alertKey": row["alert_key"],
            "signalCode": row["signal_code"],
            "severity": row["severity"],
            "subjectType": row["subject_type"],
            "subjectId": str(uuid.UUID(row["subject_id"])),
            "evidenceChain": json.dumps(chain, ensure_ascii=False, separators=(",", ":")),
            "modelVersion": row["model_version"],
        }
        buf = io.BytesIO()
        fastavro.schemaless_writer(buf, self.schema, record)
        return buf.getvalue()


def registry_global_id(http: httpx.Client, registry_url: str, schema_text: str) -> int:
    """Tra global id của phiên bản lược đồ trùng nội dung (dạng chuẩn hoá) trong artifact `<topic>-value`; không tự
    đăng ký (lược đồ chưa đăng ký là lỗi phát hành, như ApicurioPayloadSerializer của mission-core)."""
    r = http.post(f"{registry_url}/search/versions",
                  params={"groupId": "default", "artifactId": ARTIFACT_ID, "canonical": "true",
                          "artifactType": "AVRO"},
                  content=schema_text.encode(), headers={"Content-Type": "application/json"})
    if r.status_code != 200:
        raise PublishError(f"Apicurio trả {r.status_code} khi tra lược đồ {ARTIFACT_ID}")
    versions = r.json().get("versions") or []
    if not versions:
        raise PublishError(f"lược đồ {ARTIFACT_ID} chưa được đăng ký trong Apicurio")
    return int(max(versions, key=lambda v: int(v["globalId"]))["globalId"])


class Publisher:
    def __init__(self, ch: ClickHouse, producer: Any, codec: Evt20Codec, global_id: int, batch: int = 500) -> None:
        self._ch, self._producer, self._codec, self._gid, self._batch = ch, producer, codec, global_id, batch

    def publish_pending(self) -> int:
        total = 0
        while True:
            rows = self._ch.rows(
                "SELECT toString(tenant_id) AS tenant_id, alert_key, signal_code, severity, subject_type,"
                " toString(subject_id) AS subject_id, evidence_chain, model_version,"
                " toUnixTimestamp64Milli(detected_at) AS detected_ms"
                " FROM anomaly_out FINAL WHERE published_at IS NULL ORDER BY detected_at, alert_key"
                " LIMIT {n:UInt32}", {"n": self._batch})
            if not rows:
                return total
            acked = self._send(rows)
            if acked:
                self._mark(acked)
            total += len(acked)
            if len(acked) < len(rows):
                raise PublishError(f"Kafka không xác nhận {len(rows) - len(acked)} thông điệp; sẽ phát lại lần sau")

    def _send(self, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        acked: list[dict[str, Any]] = []
        errors: list[str] = []

        def on_delivery(err: Any, _msg: Any, row: dict[str, Any]) -> None:
            if err is None:
                acked.append(row)
            else:
                errors.append(str(err))

        gid = self._gid.to_bytes(8, "big")
        for row in rows:
            occurred = datetime.fromtimestamp(int(row["detected_ms"]) / 1000, UTC).isoformat().replace("+00:00", "Z")
            headers = [
                ("id", event_id(row["tenant_id"], row["alert_key"]).encode()),
                ("tenantid", row["tenant_id"].encode()),
                ("ce_type", EVENT_TYPE.encode()),
                ("classification", b"C1"),
                ("occurredat", occurred.encode()),
                ("apicurio.value.globalId", gid),
            ]
            self._producer.produce(TOPIC, key=f"{row['tenant_id']}:{row['subject_id']}".encode(),
                                   value=self._codec.encode(row), headers=headers,
                                   on_delivery=lambda err, msg, r=row: on_delivery(err, msg, r))
        self._producer.flush(60)
        for e in errors[:5]:
            log.error("phát EVT-20 thất bại: %s", e)
        return acked

    def _mark(self, rows: list[dict[str, Any]]) -> None:
        for tenant in {r["tenant_id"] for r in rows}:
            keys = [r["alert_key"] for r in rows if r["tenant_id"] == tenant]
            self._ch.run(
                "INSERT INTO anomaly_out (tenant_id, alert_key, signal_code, severity, subject_type, subject_id,"
                " period_id, evidence_chain, model_version, detected_at, run_id, published_at, _version)"
                " SELECT tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,"
                " evidence_chain, model_version, detected_at, run_id, now64(3), _version + 1"
                " FROM anomaly_out FINAL"
                " WHERE tenant_id = {tenant:UUID} AND alert_key IN {keys:Array(String)} AND published_at IS NULL",
                {"tenant": tenant, "keys": keys})
