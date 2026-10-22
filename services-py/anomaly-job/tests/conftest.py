"""Môi trường kiểm thử tích hợp DV-15-07: ClickHouse 25.8 dựng bằng cấu hình infra/dev/clickhouse và toàn bộ migration
của kho, Kafka 3.9, Apicurio Registry 3, cùng máy chủ HTTP giả cho token dịch vụ và getAnomalyThresholds."""

from __future__ import annotations

import hashlib
import http.server
import json
import os
import pathlib
import secrets
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any, ClassVar

import pytest

JOB = pathlib.Path(__file__).resolve().parents[1]
REPO = JOB.parents[1]
CH_DIR = REPO / "analytics" / "clickhouse"
GOLDEN = REPO / "data" / "golden" / "anomaly"
sys.path.insert(0, str(CH_DIR))

import migrate  # noqa: E402

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
CH_IMAGE = os.environ.get("CLICKHOUSE_IMAGE", "mirror.gcr.io/clickhouse/clickhouse-server:25.8")
KAFKA_IMAGE = os.environ.get("KAFKA_IMAGE", "mirror.gcr.io/apache/kafka:3.9.1")
REGISTRY_IMAGE = os.environ.get("REGISTRY_IMAGE", "quay.io/apicurio/apicurio-registry:3.0.9")
CH_CONF = REPO / "infra" / "dev" / "clickhouse"
NS = uuid.UUID("2c0f3a52-3d0e-4c38-9a77-0d6c1f3e9b41")
TOPIC = "analytics.anomaly.detected.v1"


def sym(name: str) -> str:
    """Mã tượng trưng trong dữ liệu vàng → UUID cố định."""
    return str(uuid.uuid5(NS, name))


def person_key(name: str) -> str:
    return hashlib.md5(name.encode()).hexdigest()  # noqa: S324 - mã giả danh 32 ký tự cho dữ liệu thử


def _docker() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _wait(url: str, timeout: float = 120) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError(f"{url} không sẵn sàng")


class Stub(http.server.BaseHTTPRequestHandler):
    """Máy chủ token (client_credentials) và mission-core getAnomalyThresholds; tenant không có trong bảng trả 503."""

    thresholds: ClassVar[dict[str, dict[str, Any]]] = {}
    calls: ClassVar[list[tuple[str, str | None, str | None]]] = []

    def log_message(self, *_: Any) -> None:
        pass

    def _send(self, code: int, body: dict[str, Any]) -> None:
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self._send(200, {"access_token": "tok-" + secrets.token_hex(4), "expires_in": 300})

    def do_GET(self) -> None:
        tenant = self.headers.get("X-Tenant-Id")
        self.calls.append((self.path, tenant, self.headers.get("Authorization")))
        if self.path != "/api/v1/config/anomaly-thresholds" or tenant not in self.thresholds:
            self._send(503, {"type": "about:blank", "status": 503})
            return
        self._send(200, self.thresholds[tenant])


@dataclass
class Env:
    ch_http: str
    admin_password: str
    db: str
    anomaly_password: str
    bootstrap: str
    registry_url: str
    stub_url: str

    def admin(self, sql: str) -> list[dict[str, Any]]:
        out = migrate.ClickHouse(self.ch_http, "default", self.admin_password).run(sql + " FORMAT JSONEachRow")
        return [json.loads(line) for line in out.splitlines() if line]


@pytest.fixture(scope="session")
def env() -> Iterator[Env]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.network import Network

    net = Network()
    net.create()
    kport = _free_port()
    kafka = (
        DockerContainer(KAFKA_IMAGE)
        .with_network(net)
        .with_bind_ports(9094, kport)
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", "EXTERNAL://:9094,CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", f"EXTERNAL://127.0.0.1:{kport}")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
        .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "EXTERNAL")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
    )
    admin_pw, anomaly_pw = secrets.token_hex(16), secrets.token_hex(16)
    ch = (
        DockerContainer(CH_IMAGE)
        .with_network(net)
        .with_exposed_ports(8123)
        .with_env("CLICKHOUSE_PASSWORD", admin_pw)
        .with_env("CLICKHOUSE_SKIP_USER_SETUP", "1")
        .with_volume_mapping(str(CH_CONF / "config.d" / "cell-dev.xml"), "/etc/clickhouse-server/config.d/cell-dev.xml")
        .with_volume_mapping(str(CH_CONF / "users.d" / "migrator.xml"), "/etc/clickhouse-server/users.d/migrator.xml")
    )
    registry = DockerContainer(REGISTRY_IMAGE).with_exposed_ports(8080)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    started: list[Any] = []
    try:
        for c in (kafka, ch, registry):
            c.start()
            started.append(c)
        ch_http = f"http://{ch.get_container_host_ip()}:{ch.get_exposed_port(8123)}"
        reg = f"http://{registry.get_container_host_ip()}:{registry.get_exposed_port(8080)}"
        _wait(ch_http + "/ping")
        params = migrate.params_from_env({
            "ANALYTICS_CELL": "dev", "ANALYTICS_KAFKA_BROKERS": "kafka-khong-co:9092",
            "ANALYTICS_CUBE_PASSWORD": secrets.token_hex(16), "ANALYTICS_ANOMALY_PASSWORD": anomaly_pw,
            "ANALYTICS_DICT_PASSWORD": secrets.token_hex(16),
        })
        migrate.migrate(migrate.ClickHouse(ch_http, "default", admin_pw, distributed_ddl_task_timeout="300"), params)
        db = params["db"]
        load_golden(migrate.ClickHouse(ch_http, "default", admin_pw), db)
        _wait(reg + "/health/ready")
        _register_schema(reg + "/apis/registry/v3")
        _create_topic(f"127.0.0.1:{kport}")
        Stub.thresholds = {sym("A"): json.loads((GOLDEN / "nguong-mac-dinh.json").read_text(encoding="utf-8"))}
        yield Env(ch_http, admin_pw, db, anomaly_pw, f"127.0.0.1:{kport}", reg + "/apis/registry/v3",
                  f"http://127.0.0.1:{server.server_port}")
    finally:
        server.shutdown()
        for c in reversed(started):
            c.stop()
        net.remove()


def load_golden(ch: migrate.ClickHouse, db: str) -> None:
    """Nạp dữ liệu vàng cho tenant A và một bản sao cho tenant B (B không lấy được ngưỡng nên không được xét)."""
    data = json.loads((GOLDEN / "du-lieu.json").read_text(encoding="utf-8"))
    flow = data["luong"]
    base = {"_version": 1, "_is_deleted": 0, "_source_ts": "2026-10-01 00:00:00"}
    rows: dict[str, list[dict[str, Any]]] = {"dim_period": [], "fact_appraisal_profile": [], "fact_rating": [],
                                             "fact_score": []}
    for t in ("A", "B"):
        tenant = sym(t)

        def tid(name: str, t: str = t) -> str:
            return sym(f"{t}:{name}")

        for k in data["ky"]:
            rows["dim_period"].append({**base, "tenant_id": tenant, "id": tid(k["ma"]), "code": k["ma"],
                                       "name": k["ma"], "kind": "QUY", "flow": flow, "start_date": k["bat_dau"],
                                       "end_date": k["ket_thuc"], "status": "DA_KHOA"})
        for h in data["ho_so"]:
            common = {**base, "tenant_id": tenant, "period_id": tid(h["ky"]), "person_key": person_key(h["nguoi"]),
                      "org_unit_id": tid(h["don_vi"]), "flow": flow}
            rows["fact_appraisal_profile"].append({**common, "id": tid(h["ma"]), "status": "DA_QUYET_DINH"})
            common = {**common, "profile_id": tid(h["ma"])}
            if "tu" in h:
                for lv, muc, diem, cur in (("TU_DE_XUAT", h["tu"], h["diem_tu"], 0),
                                           ("THAM_DINH", h["td"], h["diem_td"], 1)):
                    snap = tid(f"{h['ma']}:{lv}:diem")
                    rows["fact_rating"].append({**common, "id": tid(f"{h['ma']}:{lv}"), "level_kind": lv,
                                                "rating_code": muc, "score_snapshot_id": snap, "is_current": 1})
                    rows["fact_score"].append({**common, "id": snap, "total_score": diem, "is_current": cur,
                                               "auto_rating_code": muc})
            else:
                snap = tid(f"{h['ma']}:diem")
                rows["fact_rating"].append({**common, "id": tid(f"{h['ma']}:QUYET_DINH"), "level_kind": "QUYET_DINH",
                                            "rating_code": h["qd"], "score_snapshot_id": snap, "is_current": 1})
                rows["fact_score"].append({**common, "id": snap, "total_score": h["diem"], "is_current": 1,
                                           "auto_rating_code": h["qd"]})
    for table, items in rows.items():
        ch.run(f"INSERT INTO {db}.{table} FORMAT JSONEachRow\n" + "\n".join(json.dumps(r) for r in items))


def _register_schema(registry: str) -> None:
    schema = (REPO / "contracts" / "events" / "avro" / "analytics" / "DauHieuBatThuong.avsc").read_text("utf-8")
    body = json.dumps({"artifactId": f"{TOPIC}-value", "artifactType": "AVRO",
                       "firstVersion": {"content": {"content": schema, "contentType": "application/json"}}}).encode()
    req = urllib.request.Request(registry + "/groups/default/artifacts", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        assert r.status == 200


def _create_topic(bootstrap: str) -> None:
    from confluent_kafka.admin import AdminClient, NewTopic

    admin = AdminClient({"bootstrap.servers": bootstrap})
    deadline = time.monotonic() + 60
    while True:
        try:
            admin.list_topics(timeout=5)
            break
        except Exception:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)
    for f in admin.create_topics([NewTopic(TOPIC, num_partitions=3, replication_factor=1)]).values():
        f.result(30)
