"""Bộ xuất số liệu tổng hợp cấp tỉnh lên topic `agg.province.metric.v1` (DV-15-11, TL-03 "Đồng bộ giữa các cell").

Đọc khung `v_agg_xuat` (V015: đã che nhóm nhỏ, không mã người), mã hoá Avro theo lược đồ khung
`contracts/events/avro/agg/AggregateMetric.avsc` và phát lên Kafka của cell; MirrorMaker 2 luồng phân tích
(`analytics/mirrormaker`) chỉ sao chép họ topic `agg.*` sang Kafka của kho phân tích quốc gia.

Định dạng trên Kafka giống outbox của mission-core và producer EVT-20 (NEN-007, NEN-012): thân Avro nhị phân không tiền
tố, global id lược đồ ở tiêu đề `apicurio.value.globalId` (8 byte big-endian), tiêu đề CloudEvents (`id`, `tenantid`,
`ce_type`, `classification`, `occurredat`), khoá `tenant:provinceUnitRef` theo danh mục topic. `id` là UUIDv5 tất định
của ô, chỉ số và lần tính, nên phát lại sau lỗi không sinh bản ghi mới ở phía nhận.

Chạy một lượt rồi thoát (CronJob, suy luận mỗi 10 phút): với mỗi nguồn, phát mọi dòng tính sau mốc đã xuất, đợi Kafka
xác nhận hết rồi ghi mốc mới vào `agg_xuat_moc`. Thiếu xác nhận thì không ghi mốc, lượt sau phát lại cả lần tính đó."""

from __future__ import annotations

import io
import json
import logging
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Iterable, Mapping
from datetime import UTC, datetime
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import migrate

log = logging.getLogger("agg_export")

TOPIC = "agg.province.metric.v1"
ARTIFACT_ID = f"{TOPIC}-value"
CLASSIFICATION = "C0"
SOURCES = ("task", "score", "rating")
# Ngưỡng che nhóm nhỏ (wiki/14 DV-15-11). V015 đã lọc; bộ xuất lọc lần nữa để một khung xuất sửa sai không lọt ô nhỏ.
K_MIN = 10
NAMESPACE = uuid.UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
SCHEMA = (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "events" / "avro" / "agg"
          / "AggregateMetric.avsc")

SELECT_ROWS = (
    "SELECT source, toString(tenant_id) AS tenant_id, province_unit_ref, toString(period_id) AS period_id, cell_id,"
    " metric_key, value, count, toUnixTimestamp64Milli(computed_at) AS at_ms FROM v_agg_xuat"
    " WHERE source = {source:String} AND toUnixTimestamp64Milli(computed_at) > {mark:Int64}"
    " ORDER BY at_ms, tenant_id, province_unit_ref, cell_id, metric_key FORMAT JSONEachRow")
SELECT_MARK = ("SELECT max(toUnixTimestamp64Milli(computed_at)) FROM agg_xuat_moc FINAL"
               " WHERE source = {source:String} FORMAT TSV")
INSERT_MARK = ("INSERT INTO agg_xuat_moc (source, computed_at, exported_at, n_messages, _version)"
               " SELECT {source:String}, fromUnixTimestamp64Milli({at:Int64}), now64(3), {n:UInt64},"
               " toUInt64(toUnixTimestamp64Milli(now64(3)))")


class ExportError(RuntimeError):
    pass


def event_id(row: Mapping[str, Any]) -> str:
    return str(uuid.uuid5(NAMESPACE, ":".join(str(row[k]) for k in (
        "tenant_id", "province_unit_ref", "period_id", "cell_id", "metric_key", "at_ms"))))


class AggCodec:
    """Ánh xạ cố định dòng khung xuất → bản ghi AggregateMetric: chỉ bảy trường của lược đồ, không trường nào khác."""

    def __init__(self, schema_path: pathlib.Path = SCHEMA) -> None:
        import fastavro

        self._fastavro = fastavro
        self.schema_text = schema_path.read_text(encoding="utf-8")
        self.schema = fastavro.parse_schema(json.loads(self.schema_text))

    @staticmethod
    def record(row: Mapping[str, Any]) -> dict[str, Any]:
        if int(row["count"]) < K_MIN:
            raise ExportError(f"ô {row['cell_id']} có {row['count']} phần tử, dưới ngưỡng k = {K_MIN}")
        return {
            "cellId": row["cell_id"],
            "provinceUnitRef": row["province_unit_ref"],
            "periodId": str(uuid.UUID(row["period_id"])),
            "metricKey": row["metric_key"],
            "value": float(row["value"]),
            "count": int(row["count"]),
            "computedAt": int(row["at_ms"]) * 1000,
        }

    def encode(self, row: Mapping[str, Any]) -> bytes:
        buf = io.BytesIO()
        self._fastavro.schemaless_writer(buf, self.schema, self.record(row))
        return buf.getvalue()


def registry_global_id(registry_url: str, schema_text: str, timeout: float = 10) -> int:
    """Global id của phiên bản lược đồ trùng nội dung trong artifact `<topic>-value` (Apicurio 3.x). Không tự đăng ký:
    lược đồ do bước phát hành hợp đồng đăng ký với kiểm tra BACKWARD_TRANSITIVE (libs/vt-events, -Pschema-compat)."""
    q = urllib.parse.urlencode({"groupId": "default", "artifactId": ARTIFACT_ID, "canonical": "true",
                                "artifactType": "AVRO"})
    if not registry_url.startswith(("http://", "https://")):
        raise ExportError("APICURIO_REGISTRY_URL phải là http:// hoặc https://")
    req = urllib.request.Request(  # noqa: S310 - chỉ http(s), kiểm ở trên
        f"{registry_url.rstrip('/')}/search/versions?{q}", data=schema_text.encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - URL do người vận hành cấu hình
            versions = json.loads(r.read()).get("versions") or []
    except urllib.error.HTTPError as e:
        raise ExportError(f"Apicurio trả {e.code} khi tra lược đồ {ARTIFACT_ID}") from None
    if not versions:
        raise ExportError(f"lược đồ {ARTIFACT_ID} chưa được đăng ký trong Apicurio")
    return int(max(versions, key=lambda v: int(v["globalId"]))["globalId"])


def headers(row: Mapping[str, Any], global_id: int) -> list[tuple[str, bytes]]:
    occurred = datetime.fromtimestamp(int(row["at_ms"]) / 1000, UTC).isoformat(timespec="milliseconds")
    return [
        ("id", event_id(row).encode()),
        ("tenantid", row["tenant_id"].encode()),
        ("ce_type", TOPIC.encode()),
        ("classification", CLASSIFICATION.encode()),
        ("occurredat", occurred.replace("+00:00", "Z").encode()),
        ("apicurio.value.globalId", global_id.to_bytes(8, "big")),
    ]


class Exporter:
    def __init__(self, ch: migrate.ClickHouse, db: str, producer: Any, codec: AggCodec, global_id: int) -> None:
        self._ch, self._db, self._producer, self._codec, self._gid = ch, db, producer, codec, global_id

    def _run(self, sql: str, **params: object) -> str:
        return self._ch.run(sql, database=self._db, **{f"param_{k}": str(v) for k, v in params.items()})

    def mark(self, source: str) -> int:
        out = self._run(SELECT_MARK, source=source).strip()
        return int(out) if out and out != "\\N" else 0

    def rows(self, source: str, mark: int) -> list[dict[str, Any]]:
        out = self._run(SELECT_ROWS, source=source, mark=mark)
        return [json.loads(line) for line in out.splitlines() if line]

    def export(self, sources: Iterable[str] = SOURCES) -> dict[str, int]:
        sent: dict[str, int] = {}
        for source in sources:
            rows = [r for r in self.rows(source, self.mark(source)) if int(r["count"]) >= K_MIN]
            if not rows:
                sent[source] = 0
                continue
            acked = self._send(rows)
            if acked < len(rows):
                raise ExportError(f"Kafka không xác nhận {len(rows) - acked} thông điệp nguồn {source}")
            self._run(INSERT_MARK, source=source, at=max(int(r["at_ms"]) for r in rows), n=len(rows))
            sent[source] = len(rows)
            log.info("đã xuất %d thông điệp nguồn %s", len(rows), source)
        return sent

    def _send(self, rows: list[dict[str, Any]]) -> int:
        acked = 0
        errors: list[str] = []

        def on_delivery(err: Any, _msg: Any) -> None:
            nonlocal acked
            if err is None:
                acked += 1
            else:
                errors.append(str(err))

        for row in rows:
            self._producer.produce(TOPIC, key=f"{row['tenant_id']}:{row['province_unit_ref']}".encode(),
                                   value=self._codec.encode(row), headers=headers(row, self._gid),
                                   on_delivery=on_delivery)
            self._producer.poll(0)
        self._producer.flush(60)
        for e in errors[:5]:
            log.error("phát %s thất bại: %s", TOPIC, e)
        return acked


def kafka_config(env: Mapping[str, str]) -> dict[str, Any]:
    """mTLS mặc định như mọi client Kafka của cell (SEC-57); PLAINTEXT chỉ để chạy thử cục bộ."""
    conf: dict[str, Any] = {"bootstrap.servers": env["KAFKA_BOOTSTRAP_SERVERS"], "acks": "all",
                            "enable.idempotence": True, "linger.ms": 20, "compression.type": "zstd",
                            "security.protocol": env.get("KAFKA_SECURITY_PROTOCOL", "SSL")}
    if conf["security.protocol"] == "SSL":
        for key, var in (("ssl.ca.location", "KAFKA_SSL_CA_LOCATION"),
                         ("ssl.certificate.location", "KAFKA_SSL_CERTIFICATE_LOCATION"),
                         ("ssl.key.location", "KAFKA_SSL_KEY_LOCATION")):
            if not env.get(var):
                raise SystemExit(f"thiếu {var} cho mTLS")
            conf[key] = env[var]
    return conf


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    from confluent_kafka import Producer

    env = os.environ
    cell = env.get("ANALYTICS_CELL", "dev")
    db = env.get("ANALYTICS_DB", f"analytics_{cell}")
    if not migrate.NAME_RE.match(db):
        raise SystemExit(f"ANALYTICS_DB không hợp lệ: {db!r}")
    ch = migrate.ClickHouse(env.get("CLICKHOUSE_URL", "http://localhost:8123"),
                            env.get("ANALYTICS_AGG_USER", f"{db}_agg"), env.get("ANALYTICS_AGG_PASSWORD", ""))
    codec = AggCodec(pathlib.Path(env.get("AGG_SCHEMA_PATH", str(SCHEMA))))
    gid = registry_global_id(env["APICURIO_REGISTRY_URL"], codec.schema_text)
    print(json.dumps(Exporter(ch, db, Producer(kafka_config(env)), codec, gid).export()))


if __name__ == "__main__":
    main()
