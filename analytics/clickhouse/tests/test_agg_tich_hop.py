"""Kiểm thử tích hợp DV-15-11 trên ClickHouse 25.8 và Kafka 3.9 thật: job làm mới agg_* gom về đơn vị cấp tỉnh, khung
xuất bỏ ô dưới ngưỡng k = 10, tài khoản bộ xuất chỉ đọc được khung xuất, bộ xuất phát Avro lên agg.province.metric.v1
và không phát lại lần tính đã xuất."""

from __future__ import annotations

import hashlib
import io
import json
import secrets
import time
import uuid
from typing import Any

import fastavro
import pytest
from confluent_kafka import Consumer, Producer

import agg_export
import migrate
from tests.conftest import Analytics

pytestmark = pytest.mark.integration

TENANT = "0192f000-0000-7000-8000-0000000000c1"
ZERO = "00000000-0000-0000-0000-000000000000"


def u() -> str:
    return str(uuid.uuid4())


def pk(i: int) -> str:
    return hashlib.sha256(f"nguoi-{i}".encode()).hexdigest()[:32]


def insert(a: Analytics, table: str, rows: list[dict[str, Any]]) -> None:
    body = "\n".join(json.dumps(r) for r in rows)
    a.query(f"INSERT INTO {a.db}.{table} FORMAT JSONEachRow\n{body}", date_time_input_format="best_effort")


def refresh(a: Analytics) -> None:
    for t in ("agg_task_tinh", "agg_score_tinh", "agg_rating_tinh"):
        a.query(f"SYSTEM REFRESH VIEW {a.db}.mv_{t}")
        a.query(f"SYSTEM WAIT VIEW {a.db}.mv_{t}")


def test_aggregate_mask_and_export(analytics: Analytics) -> None:
    a, db = analytics, analytics.db
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    tinh, xa, period, g_lon, g_nho = u(), u(), u(), u(), u()
    insert(a, "dim_org_unit", [
        {"tenant_id": TENANT, "id": i, "valid_from": "2026-01-01", "valid_to": "2299-12-31", "code": code,
         "name": code, "unit_type": "TO_CHUC_DANG", "source_version": 1, "_version": 1, "_is_deleted": 0,
         "_source_ts": now} for i, code in ((tinh, "TU-THU-NGHIEM"), (xa, "DU-XA-1"))])
    insert(a, "dim_org_closure", [
        {"tenant_id": TENANT, "tree": "DANG", "ancestor_id": anc, "descendant_id": desc, "depth": d, "_version": 1,
         "_is_deleted": 0, "_source_ts": now} for anc, desc, d in ((tinh, tinh, 0), (xa, xa, 0), (tinh, xa, 1))])
    base = {"tenant_id": TENANT, "period_bucket": 202610, "period_id": period, "flow": "CHUYEN_MON",
            "is_current": 1, "_version": 1, "_is_deleted": 0, "_source_ts": now}
    # Khối lớn: 12 người ở đơn vị cấp xã (gom lên tỉnh); khối nhỏ: 4 người, phải bị che. Bản tạm tính không tính.
    scores = [{**base, "id": u(), "profile_id": u(), "person_key": pk(i), "org_unit_id": xa,
               "subject_group_id": g_lon if i < 12 else g_nho, "snapshot_kind": "CHOT_KY",
               "policy_pack_version_id": u(), "policy_version": "1", "total_score": f"{80 + i}.500",
               "auto_rating_code": "HTTNV", "computed_at": now} for i in range(16)]
    scores.append({**scores[0], "id": u(), "person_key": pk(99), "snapshot_kind": "TAM_TINH"})
    insert(a, "fact_score", scores)
    insert(a, "fact_rating", [
        {**base, "id": u(), "profile_id": u(), "person_key": pk(i), "org_unit_id": xa,
         "subject_group_id": g_lon if i < 12 else g_nho, "level_kind": "QUYET_DINH",
         "rating_code": "HTXSNV" if i < 2 else "HTTNV", "score_snapshot_id": u(), "actor_key": pk(500),
         "actor_role": "decider", "differs_from_prev": 0, "explanation_required": 0, "is_bulk_decision": 0,
         "is_signed": 1, "proposed_at": now} for i in range(16)])
    refresh(a)

    got = a.rows(f"SELECT toString(province_unit_id) AS p, toString(subject_group_id) AS g, n_person, "
                 f"toFloat64(avg_total_score) AS avg FROM {db}.agg_score_tinh FINAL WHERE tenant_id = '{TENANT}' "
                 f"ORDER BY n_person")
    assert got == [{"p": tinh, "g": g_nho, "n_person": 4, "avg": 94.0},
                   {"p": tinh, "g": g_lon, "n_person": 12, "avg": 86.0}]
    rows = a.rows(f"SELECT source, province_unit_ref, cell_id, metric_key, value, count FROM {db}.v_agg_xuat "
                  f"WHERE tenant_id = '{TENANT}' ORDER BY source, metric_key")
    assert {r["cell_id"] for r in rows} == {f"score|{g_lon}|CHUYEN_MON", f"rating|{g_lon}|CHUYEN_MON"}
    assert {r["province_unit_ref"] for r in rows} == {"TU-THU-NGHIEM"} and {r["count"] for r in rows} == {12}
    assert {r["metric_key"]: r["value"] for r in rows if r["source"] == "rating"} == {
        "rating.n_htnv": 0, "rating.n_htxsnv": 2, "rating.n_httnv": 10, "rating.n_khtnv": 0, "rating.n_person": 12}

    # Tài khoản bộ xuất do vận hành tạo, chỉ có vai trò <db>_agg: đọc được khung xuất, không đọc được agg_* hay fact_*.
    user, pw = f"{db}_agg_it", secrets.token_hex(16)
    a.query(f"CREATE USER IF NOT EXISTS {user} IDENTIFIED WITH sha256_password BY '{pw}' DEFAULT ROLE {db}_agg")
    a.query(f"GRANT {db}_agg TO {user}")
    assert a.rows(f"SELECT count() AS n FROM {db}.v_agg_xuat WHERE tenant_id = '{TENANT}'", user=user,
                  password=pw) == [{"n": len(rows)}]
    for table in ("agg_score_tinh", "fact_score", "dim_org_unit"):
        with pytest.raises(RuntimeError, match=r"ACCESS_DENIED|Not enough privileges"):
            a.query(f"SELECT count() FROM {db}.{table}", user=user, password=pw)

    ch = migrate.ClickHouse(a.http, user, pw)
    exporter = agg_export.Exporter(ch, db, Producer({"bootstrap.servers": a.bootstrap, "acks": "all"}),
                                   agg_export.AggCodec(), 4242)
    sent = exporter.export()
    assert sent["score"] >= 3 and sent["rating"] >= 5
    assert exporter.export() == {"task": 0, "score": 0, "rating": 0}

    c = Consumer({"bootstrap.servers": a.bootstrap, "group.id": "kiem-agg", "auto.offset.reset": "earliest"})
    c.subscribe([agg_export.TOPIC])
    codec = agg_export.AggCodec()
    mine: list[dict[str, Any]] = []
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and len(mine) < len(rows):
        m = c.poll(1)
        if m is None or m.error():
            continue
        h = dict(m.headers() or [])
        if h.get("tenantid") != TENANT.encode():
            continue
        assert h["apicurio.value.globalId"] == (4242).to_bytes(8, "big") and h["classification"] == b"C0"
        assert m.key() == f"{TENANT}:TU-THU-NGHIEM".encode()
        mine.append(fastavro.schemaless_reader(io.BytesIO(m.value()), codec.schema))
    c.close()
    assert len(mine) == len(rows)
    assert all(r["count"] >= agg_export.K_MIN for r in mine)
    assert not [r for r in mine if g_nho in r["cellId"]]
