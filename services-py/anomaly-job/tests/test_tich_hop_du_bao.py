"""Kiểm thử tích hợp DV-15-10: EVT-05 trên Kafka → đọc kho ClickHouse → lời gọi cổng MOD-02 giả, với bộ ca vàng
data/golden/delay-risk; độ trễ dưới 5 phút; phát lại EVT-05 cũ không làm lùi giá trị; CDC chậm thì chờ,
quá hạn chờ thì giữ giá trị cũ."""

from __future__ import annotations

import datetime as dt
import decimal
import io
import json
import threading
import time
import uuid
from collections.abc import Iterator
from typing import Any

import fastavro
import httpx
import pytest
from confluent_kafka import Consumer, Producer

from app.clickhouse import ClickHouse
from app.delay_risk.calendar import CalendarClient
from app.delay_risk.main import REPO_SCHEMA
from app.delay_risk.model import Params
from app.delay_risk.service import TOPIC, DelayRiskService, Evt05Decoder
from app.delay_risk.sink import DelayRiskSink
from app.thresholds import ServiceToken
from tests.conftest import REPO, Env, Stub, sym

GOLDEN = REPO / "data" / "golden" / "delay-risk"
TENANT = sym("C")
SCHEMA = fastavro.parse_schema(json.loads(REPO_SCHEMA.read_text(encoding="utf-8")))


def tid(name: str) -> str:
    return sym(f"C:{name}")


def admin(env: Env) -> ClickHouse:
    return ClickHouse(env.ch_http, "default", env.admin_password, env.db)


def insert_progress(env: Env, task: str, pid: str, at: str, value: float, by_qty: bool) -> None:
    row = {"tenant_id": TENANT, "id": pid, "task_id": tid(task), "person_key": "0" * 32, "org_unit_id": tid("U"),
           "progress_pct": 0 if by_qty else value, "done_quantity": value if by_qty else None, "channel": "WEB",
           "reported_at": at[:19].replace("T", " "), "_version": 1, "_is_deleted": 0,
           "_source_ts": at[:19].replace("T", " ")}
    admin(env).run("INSERT INTO fact_task_progress FORMAT JSONEachRow\n" + json.dumps(row))


def evt05(producer: Producer, task: str, pid: str, at: str, version: int) -> None:
    rec = {"taskId": tid(task), "periodId": tid("Q4"), "progressId": pid, "reporterId": tid("nguoi"),
           "progressPct": decimal.Decimal("0.000"), "actualValue": None, "evidenceCount": 0,
           "reportedAt": dt.datetime.fromisoformat(at), "aggregateVersion": version}
    buf = io.BytesIO()
    fastavro.schemaless_writer(buf, SCHEMA, rec)
    producer.produce(TOPIC, key=f"{TENANT}:{tid(task)}".encode(), value=buf.getvalue(),
                     headers=[("id", str(uuid.uuid4()).encode()), ("tenantid", TENANT.encode()),
                              ("ce_type", TOPIC.encode()), ("classification", b"C1")])
    producer.flush(30)


@pytest.fixture(scope="module")
def setup(env: Env) -> Iterator[dict[str, Any]]:
    data = json.loads((GOLDEN / "du-lieu.json").read_text(encoding="utf-8"))
    Stub.calendar = data["lich"]
    rows = []
    for t in data["nhiem_vu"]:
        rows.append({"tenant_id": TENANT, "id": tid(t["ma"]), "period_id": tid("Q4"), "code": t["ma"],
                     "start_date": t["bat_dau"], "due_date": t["han"], "extended_due_date": t.get("han_gia_han"),
                     "planned_quantity": t["so_luong"], "done_quantity": 0, "progress_pct": 0,
                     "status": t["trang_thai"], "created_at": "2026-09-01 00:00:00",
                     "updated_at": "2026-10-19 00:00:00", "_version": 1, "_is_deleted": 0,
                     "_source_ts": "2026-10-19 00:00:00"})
    admin(env).run("INSERT INTO fact_task FORMAT JSONEachRow\n" + "\n".join(json.dumps(r) for r in rows))
    for t in data["nhiem_vu"]:
        for i, p in enumerate(t["tien_do"]):
            insert_progress(env, t["ma"], tid(f"{t['ma']}:p{i}"), p["luc"], p["gia_tri"], t["so_luong"] > 0)

    http = httpx.Client(timeout=30)
    token = ServiceToken(http, env.stub_url + "/token", "delay-risk", "bi-mat-thu")
    consumer = Consumer({"bootstrap.servers": env.bootstrap, "group.id": "svc15-delay-risk-kiem-thu",
                         "enable.auto.commit": False, "auto.offset.reset": "earliest"})
    consumer.subscribe([TOPIC])
    svc = DelayRiskService(consumer, ClickHouse(env.ch_http, f"{env.db}_anomaly", env.anomaly_password, env.db),
                           CalendarClient(http, env.stub_url, token), DelayRiskSink(http, env.stub_url, token),
                           Evt05Decoder(REPO_SCHEMA), Params(), max_wait=15, today=lambda: dt.date(2026, 10, 19))
    stop = threading.Event()

    def loop() -> None:
        while not stop.is_set():
            svc.step(0.5)

    th = threading.Thread(target=loop, daemon=True)
    th.start()
    producer = Producer({"bootstrap.servers": env.bootstrap, "enable.idempotence": True, "acks": "all"})
    sent: dict[str, float] = {}
    for t in data["nhiem_vu"]:
        last = len(t["tien_do"]) - 1
        sent[tid(t["ma"])] = time.monotonic()
        evt05(producer, t["ma"], tid(f"{t['ma']}:p{last}"), t["tien_do"][last]["luc"], last + 1)
    yield {"data": data, "svc": svc, "producer": producer, "sent": sent}
    stop.set()
    th.join(10)
    consumer.close()
    http.close()


def wait_for(cond: Any, timeout: float = 60) -> None:
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError("hết thời gian chờ")
        time.sleep(0.2)


def test_golden_cases_reach_core_within_budget(setup: dict[str, Any]) -> None:
    cases = json.loads((GOLDEN / "ca-kiem-thu.json").read_text(encoding="utf-8"))["ket_qua"]
    want = {tid(k): v for k, v in cases.items() if v is not None}
    wait_for(lambda: set(want) <= {k[1] for k in Stub.delay_risk if k[0] == TENANT})
    wait_for(lambda: setup["svc"].pending == 0)
    for task, w in want.items():
        b = Stub.delay_risk[(TENANT, task)]
        n = w["n"] if w["n"] is not None else w["m"] + 1  # nhịp 0: gửi m + 1 (sink.needed_days)
        assert (b["level"], b["neededWorkingDays"]) == (w["muc"], n), task
        assert b["explanation"].endswith((f"còn {w['m']} ngày.", "không có nguy cơ trễ hạn.")), task
    called = {c[2] for c in Stub.delay_calls if c[1] == TENANT}
    assert called == set(want)  # T08 (chưa đủ dữ liệu), T09 (đã hoàn thành) không được ghi
    lat = sorted(min(c[0] for c in Stub.delay_calls if c[2] == t) - setup["sent"][t] for t in want)
    assert lat[int(0.95 * (len(lat) - 1))] < 300


def test_replayed_old_event_does_not_regress(setup: dict[str, Any]) -> None:
    before = dict(Stub.delay_risk[(TENANT, tid("T02"))])
    n = len(Stub.delay_calls)
    first = setup["data"]["nhiem_vu"][1]["tien_do"][0]
    evt05(setup["producer"], "T02", tid("T02:p0"), first["luc"], 1)
    wait_for(lambda: len(Stub.delay_calls) > n and setup["svc"].pending == 0)
    # Dự báo tính lại từ toàn bộ lịch sử trong kho: chỉ computedAt mới hơn, nội dung không lùi.
    after = Stub.delay_risk[(TENANT, tid("T02"))]
    def content(b: dict[str, Any]) -> dict[str, Any]:
        return {k: v for k, v in b.items() if k != "computedAt"}

    assert content(after) == content(before)
    assert all(c[3]["basedOnProgressId"] == before["basedOnProgressId"] for c in Stub.delay_calls[n:])


def test_waits_for_cdc_then_keeps_old_value_on_timeout(env: Env, setup: dict[str, Any]) -> None:
    svc, producer = setup["svc"], setup["producer"]
    key = (TENANT, tid("T04"))
    assert Stub.delay_risk[key]["level"] == "CAO"
    at = "2026-10-19T17:00:00+07:00"
    evt05(producer, "T04", tid("T04:p-moi"), at, 3)
    time.sleep(3)
    assert Stub.delay_risk[key]["level"] == "CAO" and svc.pending == 1
    insert_progress(env, "T04", tid("T04:p-moi"), at, 0.75, False)
    wait_for(lambda: Stub.delay_risk[key].get("basedOnProgressId") == tid("T04:p-moi"))
    assert (Stub.delay_risk[key]["level"], Stub.delay_risk[key]["neededWorkingDays"]) == ("THAP", 3)

    before, lag = dict(Stub.delay_risk[(TENANT, tid("T05"))]), svc.stats["cdc_cham"]
    evt05(producer, "T05", tid("T05:khong-bao-gio-toi"), at, 9)
    wait_for(lambda: svc.stats["cdc_cham"] == lag + 1, timeout=40)
    assert Stub.delay_risk[(TENANT, tid("T05"))] == before


def test_offsets_committed_after_processing(env: Env, setup: dict[str, Any]) -> None:
    wait_for(lambda: setup["svc"].pending == 0)
    time.sleep(1.5)
    c = Consumer({"bootstrap.servers": env.bootstrap, "group.id": "svc15-delay-risk-kiem-thu"})
    from confluent_kafka import TopicPartition

    parts = [TopicPartition(TOPIC, p) for p in range(3)]
    committed = c.committed(parts, timeout=10)
    ends = [c.get_watermark_offsets(p, timeout=10)[1] for p in parts]
    c.close()
    assert [p.offset if p.offset >= 0 else 0 for p in committed] == ends
