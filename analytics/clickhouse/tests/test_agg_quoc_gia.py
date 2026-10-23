"""Kiểm tra tĩnh DV-15-11 (không cần Docker):

1. Lược đồ Avro họ `agg.*` không có trường nào mang tên hay phân loại mã người (`person_key`, `user_id`…) hoặc C2, C3;
   topic `agg.*` trong danh mục chỉ là C0 và dùng lược đồ trong `avro/agg/`.
2. Job tổng hợp V015 và bảng V007 không đưa mã người ra ngoài: `person_key` chỉ xuất hiện trong hàm đếm; khung xuất
   áp ngưỡng che nhóm nhỏ k = 10 cho cả ba nguồn.
3. Bộ xuất: chặn ô dưới ngưỡng, mã hoá đúng lược đồ, tiêu đề Apicurio 8 byte, chỉ ghi mốc khi Kafka xác nhận hết."""

from __future__ import annotations

import io
import json
import re
from typing import Any

import fastavro
import pytest
import yaml

import agg_export
from tests.conftest import REPO, ROOT

AVRO = REPO / "contracts" / "events" / "avro" / "agg"
CATALOG = REPO / "contracts" / "events" / "catalog.yaml"
V007 = ROOT / "migrations" / "V007__bang_tong_hop_tinh.sql"
V015 = ROOT / "migrations" / "V015__tong_hop_quoc_gia.sql"
# Tên (dạng snake, chữ thường) mang mã hay thuộc tính định danh một người cụ thể (TL-03 phân loại cột, test_phan_loai).
PERSON = re.compile(r"(^|_)(person|user|actor|assigner|acceptor|assignee|approver|appraiser|decider|verifier|owner"
                    r"|created_by|updated_by|verified_by|approved_by|decided_by|locked_by|subject_id|profile|cbccvc"
                    r"|full_name|ho_ten|email|phone|so_dien_thoai|cccd|cmnd|birth|ngay_sinh)(_|$)")
SENSITIVE_DOC = re.compile(r"\bC[23]\b|_enc\b|_bidx\b")


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def fields(schema: Any, path: str = "") -> list[tuple[str, dict[str, Any]]]:
    """Mọi trường của lược đồ, kể cả bản ghi lồng trong union, mảng, map."""
    out: list[tuple[str, dict[str, Any]]] = []
    if isinstance(schema, list):
        for s in schema:
            out += fields(s, path)
    elif isinstance(schema, dict):
        if schema.get("type") == "record":
            for f in schema["fields"]:
                out.append((f"{path}{f['name']}", f))
                out += fields(f["type"], f"{path}{f['name']}.")
        elif schema.get("type") == "array":
            out += fields(schema["items"], path)
        elif schema.get("type") == "map":
            out += fields(schema["values"], path)
    return out


def agg_schemas() -> list[tuple[str, dict[str, Any]]]:
    files = sorted(AVRO.glob("*.avsc"))
    assert files, "không thấy lược đồ agg.*"
    return [(f.name, json.loads(f.read_text(encoding="utf-8"))) for f in files]


@pytest.mark.parametrize(("name", "schema"), agg_schemas())
def test_agg_schema_has_no_person_or_c2_field(name: str, schema: dict[str, Any]) -> None:
    for path, f in fields(schema):
        leaf = snake(path.rsplit(".", 1)[-1])
        assert not PERSON.search(leaf), f"{name}: trường {path} mang mã người"
        assert not SENSITIVE_DOC.search(f.get("doc", "")), f"{name}: trường {path} ghi phân loại C2/C3"
        cls = f.get("classification", "C0")
        assert cls in ("C0", "C1"), f"{name}: trường {path} có phân loại {cls}"


def test_person_pattern_catches_known_identifiers() -> None:
    for bad in ("personKey", "person_key", "userId", "user_id", "actorKey", "profileId", "subjectId", "createdBy",
                "fullName", "cbccvcCode", "email"):
        assert PERSON.search(snake(bad)), bad
    for ok in ("cellId", "provinceUnitRef", "periodId", "metricKey", "value", "count", "computedAt"):
        assert not PERSON.search(snake(ok)), ok


def test_catalog_agg_topics_are_c0_with_agg_schema() -> None:
    cat = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))
    topics = [t for t in cat["events"] + cat["technicalTopics"] if t["topic"].startswith("agg.")]
    assert [t["topic"] for t in topics] == [agg_export.TOPIC]
    for t in topics:
        assert t["classification"] == "C0"
        assert t["schema"].startswith("avro/agg/")
        assert t["artifactId"] == agg_export.ARTIFACT_ID
    family = [f for f in cat["technicalTopicFamilies"] if f["pattern"] == "agg.*"]
    assert family and family[0]["classification"] == "C0"


def test_aggregate_tables_hold_only_counts() -> None:
    sql = V007.read_text(encoding="utf-8")
    columns = re.findall(r"^\s+(\w+)\s+[A-Z]", sql, re.M)
    assert "n_person" in columns
    # n_person là số người của ô (đếm), không phải mã người.
    assert not [c for c in columns if PERSON.search(c) and not c.startswith("n_")], columns


def test_job_uses_person_key_only_inside_counts() -> None:
    sql = re.sub(r"--[^\n]*", "", V015.read_text(encoding="utf-8"))
    uses = re.findall(r"[\w(]*\(?[\w.]*person_key[^,\n]*", sql)
    assert uses
    for u in uses:
        assert re.match(r"uniqExact(If)?\(\w+\.person_key", u.strip()), u


def test_export_view_applies_k_threshold_to_every_source() -> None:
    sql = re.sub(r"--[^\n]*", "", V015.read_text(encoding="utf-8"))
    view = sql[sql.index("CREATE VIEW"):sql.index("CREATE TABLE")]
    assert re.findall(r"WHERE (\w+) >= (\d+)", view) == [("n_task", "10"), ("n_person", "10"), ("n_person", "10")]
    assert agg_export.K_MIN == 10
    head = view[:view.index("FROM")]
    assert not PERSON.search(head.replace("province_unit_ref", "")), head
    assert "SQL SECURITY DEFINER" in view


def test_refresh_hourly_append() -> None:
    sql = V015.read_text(encoding="utf-8")
    for table in ("agg_task_tinh", "agg_score_tinh", "agg_rating_tinh"):
        head = f"mv_{table} ON CLUSTER '{{{{cluster}}}}'\nREFRESH EVERY 1 HOUR APPEND\nTO {{{{db}}}}.{table}\n"
        assert head in sql, table


ROW = {"source": "score", "tenant_id": "0192f000-0000-7000-8000-00000000000a", "province_unit_ref": "TU-HN",
       "period_id": "0192f000-0000-7000-8000-0000000000e1", "cell_id": "score|g|CHUYEN_MON",
       "metric_key": "score.avg_total_score", "value": 87.25, "count": 12, "at_ms": 1_792_720_800_123}


def test_codec_round_trip_and_rejects_small_cells() -> None:
    codec = agg_export.AggCodec()
    rec = fastavro.schemaless_reader(io.BytesIO(codec.encode(ROW)), codec.schema)
    assert rec == {"cellId": "score|g|CHUYEN_MON", "provinceUnitRef": "TU-HN",
                   "periodId": rec["periodId"], "metricKey": "score.avg_total_score",
                   "value": 87.25, "count": 12, "computedAt": rec["computedAt"]}
    assert str(rec["periodId"]) == ROW["period_id"]
    assert int(rec["computedAt"].timestamp() * 1000) == ROW["at_ms"]
    with pytest.raises(agg_export.ExportError):
        codec.encode({**ROW, "count": 9})


def test_headers_and_deterministic_id() -> None:
    h = dict(agg_export.headers(ROW, 42))
    assert h["apicurio.value.globalId"] == (42).to_bytes(8, "big")
    assert h["classification"] == b"C0" and h["ce_type"] == b"agg.province.metric.v1"
    assert h["occurredat"].endswith(b"Z")
    assert agg_export.event_id(ROW) == agg_export.event_id(dict(ROW))
    assert agg_export.event_id(ROW) != agg_export.event_id({**ROW, "at_ms": ROW["at_ms"] + 1})


class FakeCh:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows, self.marks, self.calls = rows, [], []

    def run(self, sql: str, **settings: str) -> str:
        self.calls.append(settings)
        if sql.startswith("SELECT max"):
            return "\\N\n"
        if sql.startswith("INSERT"):
            self.marks.append(settings)
            return ""
        return "".join(json.dumps(r) + "\n" for r in self.rows if r["source"] == settings["param_source"])


class FakeProducer:
    def __init__(self, fail: bool = False) -> None:
        self.sent: list[dict[str, Any]] = []
        self.fail = fail
        self._cb: list[Any] = []

    def produce(self, topic: str, key: bytes, value: bytes, headers: list[Any], on_delivery: Any) -> None:
        self.sent.append({"topic": topic, "key": key, "value": value, "headers": headers})
        self._cb.append(on_delivery)

    def poll(self, _t: float) -> int:
        return 0

    def flush(self, _t: float) -> int:
        for i, cb in enumerate(self._cb):
            cb("hết thời gian" if self.fail and i == 0 else None, None)
        self._cb = []
        return 0


def test_exporter_drops_small_cells_and_marks_after_ack() -> None:
    ch = FakeCh([ROW, {**ROW, "metric_key": "score.n_person", "value": 12}, {**ROW, "cell_id": "nho", "count": 3}])
    prod = FakeProducer()
    sent = agg_export.Exporter(ch, "analytics_dev", prod, agg_export.AggCodec(), 7).export()  # type: ignore[arg-type]
    assert sent == {"task": 0, "score": 2, "rating": 0}
    assert {m["key"] for m in prod.sent} == {b"0192f000-0000-7000-8000-00000000000a:TU-HN"}
    assert {m["topic"] for m in prod.sent} == {"agg.province.metric.v1"}
    assert ch.marks == [{"database": "analytics_dev", "param_source": "score", "param_at": str(ROW["at_ms"]),
                         "param_n": "2"}]


def test_exporter_keeps_mark_when_kafka_does_not_ack() -> None:
    ch = FakeCh([ROW])
    with pytest.raises(agg_export.ExportError):
        agg_export.Exporter(ch, "analytics_dev", FakeProducer(fail=True), agg_export.AggCodec(), 7).export()  # type: ignore[arg-type]
    assert ch.marks == []


def test_kafka_config_requires_mtls_files() -> None:
    with pytest.raises(SystemExit):
        agg_export.kafka_config({"KAFKA_BOOTSTRAP_SERVERS": "k:9093"})
    conf = agg_export.kafka_config({"KAFKA_BOOTSTRAP_SERVERS": "k:9093", "KAFKA_SSL_CA_LOCATION": "/ca",
                                    "KAFKA_SSL_CERTIFICATE_LOCATION": "/crt", "KAFKA_SSL_KEY_LOCATION": "/key"})
    assert conf["security.protocol"] == "SSL" and conf["enable.idempotence"] is True
