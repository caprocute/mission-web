"""Kiểm thử tích hợp DV-15-09 bước tự đóng (QT-14 bước 10): sau khi dữ liệu nguồn được sửa để hết dấu hiệu, lượt chạy
sau đánh dấu đúng cảnh báo cũ là hết dấu hiệu, chỉ cảnh báo đó, và khi bật cờ thì phát tín hiệu cùng alertKey cũ một
lần. Chạy sau `test_tich_hop.py` trên cùng môi trường; tự phát hiện, phát lại nếu chạy riêng."""

from __future__ import annotations

import io
import time
import uuid
from typing import Any

import fastavro
import httpx
from confluent_kafka import Consumer, Producer

from app.config import REPO_SCHEMA
from app.producer import Evt20Codec, Publisher, event_id, registry_global_id
from tests.conftest import TOPIC, Env, migrate, sym
from tests.test_tich_hop import job_ch, run_detect, stored


def publisher(env: Env, resolved: bool) -> Publisher:
    codec = Evt20Codec(REPO_SCHEMA)
    with httpx.Client(timeout=30) as http:
        gid = registry_global_id(http, env.registry_url, codec.schema_text)
    producer = Producer({"bootstrap.servers": env.bootstrap, "enable.idempotence": True, "acks": "all"})
    return Publisher(job_ch(env), producer, codec, gid, publish_resolved=resolved)


def messages_with_id(env: Env, wanted: str) -> list[Any]:
    consumer = Consumer({"bootstrap.servers": env.bootstrap, "group.id": "kiem-thu-" + uuid.uuid4().hex,
                         "auto.offset.reset": "earliest"})
    consumer.subscribe([TOPIC])
    out: list[Any] = []
    idle_until = time.monotonic() + 60
    while time.monotonic() < idle_until:
        m = consumer.poll(1)
        if m is None:
            if out:
                break
            continue
        if m.error() is None and dict(m.headers())["id"].decode() == wanted:
            out.append(m)
            idle_until = time.monotonic() + 3
    consumer.close()
    return out


def test_corrected_source_resolves_old_alert_once(env: Env) -> None:
    run_detect(env, ("A",))
    publisher(env, resolved=False).publish_pending()
    before = {r["alert_key"]: r for r in stored(env) if r["tenant_id"] == sym("A")}
    target = [k for k, r in before.items()
              if r["signal_code"] == "TU_CHAM_CAO" and r["subject_id"] == sym("A:P3Q4")]
    assert len(target) == 1 and before[target[0]]["published_at"]
    key = target[0]

    # Điểm tự tính quý 4 của P3 được thay bằng điểm sau thẩm định: chuỗi 2 quý chênh điểm không còn.
    snap = sym("A:P3Q4:TU_DE_XUAT:diem")
    cols = [r["name"] for r in env.admin(
        f"SELECT name FROM system.columns WHERE database = '{env.db}' AND table = 'fact_score'"
        " AND default_kind != 'MATERIALIZED' ORDER BY position")]
    select = ", ".join({"total_score": "toDecimal64(83, 3)", "_version": "_version + 1"}.get(c, c) for c in cols)
    migrate.ClickHouse(env.ch_http, "default", env.admin_password).run(
        f"INSERT INTO {env.db}.fact_score ({', '.join(cols)}) SELECT {select} FROM {env.db}.fact_score FINAL"
        f" WHERE tenant_id = '{sym('A')}' AND id = '{snap}'")

    run = run_detect(env, ("A",))
    assert run.new_alerts == {} and run.resolved == 1
    rows = env.admin(f"SELECT alert_key, resolved_at, resolution_published_at, published_at FROM {env.db}.anomaly_out"
                     f" FINAL WHERE tenant_id = '{sym('A')}'")
    assert [r["alert_key"] for r in rows if r["resolved_at"]] == [key]
    assert all(r["published_at"] and not r["resolution_published_at"] for r in rows)

    # Cờ tắt (mặc định): không phát gì; cờ bật: phát đúng một tín hiệu cho alertKey cũ, rồi không phát lại.
    assert publisher(env, resolved=False).publish_pending() == 0
    pub = publisher(env, resolved=True)
    assert pub.publish_pending() == 1
    assert pub.publish_pending() == 0
    msgs = messages_with_id(env, event_id(sym("A"), key, resolved=True))
    assert len(msgs) == 1
    rec = fastavro.schemaless_reader(io.BytesIO(msgs[0].value()), Evt20Codec(REPO_SCHEMA).schema, None)
    assert (rec["alertKey"], rec["severity"], rec["signalCode"]) == (key, "HET_DAU_HIEU", "TU_CHAM_CAO")
    assert msgs[0].key().decode() == f"{sym('A')}:{sym('A:P3Q4')}"

    # Lượt sau không đóng lại, không mở lại và không xoá trạng thái đã phát.
    again = run_detect(env, ("A",))
    assert again.new_alerts == {} and again.resolved == 0
    after = env.admin(f"SELECT alert_key, resolution_published_at FROM {env.db}.anomaly_out FINAL"
                      f" WHERE tenant_id = '{sym('A')}' AND resolved_at IS NOT NULL")
    assert [r["alert_key"] for r in after] == [key] and after[0]["resolution_published_at"]
