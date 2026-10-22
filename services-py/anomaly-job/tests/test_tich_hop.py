"""Kiểm thử tích hợp DV-15-07, DV-15-08: luật dấu hiệu 1, 2, 3, 5, 7 trên dữ liệu vàng, ngưỡng tenant khác cho kết quả
khác, chạy lại không sinh cảnh báo mới, phát EVT-20 đúng hợp đồng (Avro theo sổ đăng ký, khoá, tiêu đề, event_id
tất định), và không có tên người hay nội dung C2."""

from __future__ import annotations

import io
import json
import re
import struct
import time
import uuid
from typing import Any

import fastavro
import httpx
import pytest
from confluent_kafka import Consumer, Producer

from app.clickhouse import ClickHouse
from app.config import REPO_SCHEMA
from app.delay_risk.calendar import CalendarClient
from app.detector import detect
from app.main import main
from app.producer import Evt20Codec, Publisher, event_id, registry_global_id
from app.rules.giai_thich import giai_thich
from app.thresholds import ServiceToken, ThresholdClient
from tests.conftest import GOLDEN, TOPIC, Env, Stub, sym

EVIDENCE = re.compile(r"^(dieu_kien:[A-Z_]+|so_lieu:[A-Z_]+=[0-9.]+|[a-z_]+:[0-9a-f-]{36})$")
TOTAL_A = 14


def job_ch(env: Env) -> ClickHouse:
    """Job chạy bằng tài khoản kỹ thuật của nó, không phải tài khoản quản trị."""
    return ClickHouse(env.ch_http, f"{env.db}_anomaly", env.anomaly_password, env.db)


def run_detect(env: Env, tenants: tuple[str, ...] = ("A", "B")) -> Any:
    with httpx.Client(timeout=30) as http:
        token = ServiceToken(http, env.stub_url + "/token", "anomaly-job", "bi-mat-thu")
        return detect(job_ch(env), ThresholdClient(http, env.stub_url, token), [sym(t) for t in tenants],
                      CalendarClient(http, env.stub_url, token))


def resolve(x: str, t: str) -> str:
    return re.sub(r"#([\w-]+)", lambda m: sym(f"{t}:{m.group(1)}"), x)


def expected(key: str = "canh_bao", t: str = "A") -> list[dict[str, Any]]:
    cases = json.loads((GOLDEN / "ca-kiem-thu.json").read_text(encoding="utf-8"))[key]
    out = [{"signal_code": c["dau_hieu"], "subject_type": c["loai"], "subject_id": resolve(c["doi_tuong"], t),
            "period_id": sym(f"{t}:{c['ky']}"), "severity": c["muc"],
            "chain": sorted(resolve(x, t) for x in c["chuoi"])}
           for c in cases]
    return sorted(out, key=json.dumps)


def got(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out = [{"signal_code": r["signal_code"], "subject_type": r["subject_type"], "subject_id": r["subject_id"],
            "period_id": r["period_id"], "severity": r["severity"], "chain": sorted(json.loads(r["evidence_chain"]))}
           for r in rows]
    return sorted(out, key=json.dumps)


def stored(env: Env) -> list[dict[str, Any]]:
    return env.admin(f"SELECT toString(tenant_id) AS tenant_id, alert_key, signal_code, severity, subject_type,"
                     f" toString(subject_id) AS subject_id, toString(period_id) AS period_id, evidence_chain,"
                     f" model_version, toString(run_id) AS run_id, published_at"
                     f" FROM {env.db}.anomaly_out FINAL ORDER BY signal_code, subject_id")


@pytest.fixture(scope="module")
def first_run(env: Env) -> Any:
    return run_detect(env)


def test_rules_match_golden_cases_and_fail_closed(env: Env, first_run: Any) -> None:
    assert first_run.failed_tenants == [sym("B")]
    assert first_run.new_alerts == {"TU_CHAM_CAO": 2, "CAO_BANG": 2, "MUC_TIEU_DE": 4, "CAP_NHAT_DON": 3, "CHE_NHO": 3}
    rows = stored(env)
    assert {r["tenant_id"] for r in rows} == {sym("A")}
    assert got(rows) == expected()
    assert {r["model_version"] for r in rows} == {"luat-2026.10.2;nguong-v1"}
    calls = [c for c in Stub.calls if c[1] == sym("A")]
    assert calls and all(c[2] and c[2].startswith("Bearer tok-") for c in calls)
    for r in rows:  # mỗi điều kiện có mẫu giải thích và đủ số liệu
        assert giai_thich(json.loads(r["evidence_chain"])).endswith(".")


def test_second_run_adds_nothing(env: Env, first_run: Any) -> None:
    keys = {r["alert_key"] for r in stored(env)}
    again = run_detect(env)
    assert again.new_alerts == {}
    assert {r["alert_key"] for r in stored(env)} == keys
    assert {r["run_id"] for r in stored(env)} == {first_run.run_id}


def test_publish_evt20_per_contract_once(env: Env, first_run: Any) -> None:
    codec = Evt20Codec(REPO_SCHEMA)
    with httpx.Client(timeout=30) as http:
        gid = registry_global_id(http, env.registry_url, codec.schema_text)
    producer = Producer({"bootstrap.servers": env.bootstrap, "enable.idempotence": True, "acks": "all"})
    pub = Publisher(job_ch(env), producer, codec, gid, batch=3)
    assert pub.publish_pending() == TOTAL_A
    assert all(r["published_at"] for r in stored(env))
    assert pub.publish_pending() == 0

    consumer = Consumer({"bootstrap.servers": env.bootstrap, "group.id": "kiem-thu-" + uuid.uuid4().hex,
                         "auto.offset.reset": "earliest"})
    consumer.subscribe([TOPIC])
    msgs: list[Any] = []
    deadline = time.monotonic() + 60
    while len(msgs) < TOTAL_A and time.monotonic() < deadline:
        m = consumer.poll(1)
        if m is not None and m.error() is None:
            msgs.append(m)
    time.sleep(2)
    while (m := consumer.poll(1)) is not None:
        msgs.append(m)
    consumer.close()
    assert len(msgs) == TOTAL_A

    by_key = {r["alert_key"]: r for r in stored(env)}
    for m in msgs:
        h = dict(m.headers())
        assert struct.unpack(">q", h["apicurio.value.globalId"])[0] == gid
        rec = fastavro.schemaless_reader(io.BytesIO(m.value()), codec.schema, None)
        row = by_key[rec["alertKey"]]
        assert m.key().decode() == f"{sym('A')}:{rec['subjectId']}" and str(rec["subjectId"]) == row["subject_id"]
        assert h["id"].decode() == event_id(sym("A"), rec["alertKey"])
        assert (h["tenantid"].decode(), h["ce_type"].decode(), h["classification"]) == (sym("A"), TOPIC, b"C1")
        assert re.match(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$", h["occurredat"].decode())
        assert all(EVIDENCE.match(x) for x in json.loads(rec["evidenceChain"]))
        assert (rec["signalCode"], rec["severity"], rec["subjectType"]) == (
            row["signal_code"], row["severity"], row["subject_type"])


def test_main_publish_only_is_idempotent(env: Env, first_run: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    for k, v in {"ANALYTICS_CELL": "dev", "CLICKHOUSE_URL": env.ch_http, "CLICKHOUSE_PASSWORD": env.anomaly_password,
                 "KAFKA_BOOTSTRAP_SERVERS": env.bootstrap, "REGISTRY_URL": env.registry_url,
                 "CONFIG_API_URL": env.stub_url, "OIDC_TOKEN_URL": env.stub_url + "/token",
                 "OIDC_CLIENT_ID": "anomaly-job", "OIDC_CLIENT_SECRET": "bi-mat-thu"}.items():
        monkeypatch.setenv(k, v)
    assert main(["--chi-phat"]) == 0
    # Chạy đủ (phát hiện + phát) cho tenant B: không lấy được ngưỡng nên mã thoát khác 0, không có dòng mới.
    assert main(["--tenant", sym("B")]) == 1
    assert len(stored(env)) == TOTAL_A


def test_tenant_thresholds_change_results(env: Env) -> None:
    run = run_detect(env, ("D",))
    assert run.failed_tenants == []
    rows = [r for r in stored(env) if r["tenant_id"] == sym("D")]
    assert got(rows) == expected("canh_bao_nguong_thay_doi", "D")
    assert {r["model_version"] for r in rows} == {"luat-2026.10.2;nguong-v2"}


def test_job_account_cannot_write_elsewhere(env: Env) -> None:
    ch = job_ch(env)
    with pytest.raises(Exception, match=r"ACCESS_DENIED|Not enough privileges"):
        ch.run(f"INSERT INTO {env.db}.fact_rating (tenant_id) VALUES ({{t:UUID}})", {"t": sym("A")})
    with pytest.raises(Exception, match=r"ACCESS_DENIED|Not enough privileges"):
        ch.run(f"SELECT _source_ts FROM {env.db}.dim_period LIMIT 1")
