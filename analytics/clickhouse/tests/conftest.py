"""Container dùng chung cho kiểm thử tích hợp DV-15-01: Kafka 3.9 (KRaft) và ClickHouse 25.8 một nút dựng như cụm
của cell (Keeper nhúng, cụm cell_dev) bằng đúng cấu hình infra/dev/clickhouse, rồi áp toàn bộ migrations."""

from __future__ import annotations

import base64
import json
import os
import pathlib
import secrets
import socket
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
sys.path.insert(0, str(ROOT))

import migrate  # noqa: E402

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
CH_IMAGE = os.environ.get("CLICKHOUSE_IMAGE", "mirror.gcr.io/clickhouse/clickhouse-server:25.8")
KAFKA_IMAGE = os.environ.get("KAFKA_IMAGE", "mirror.gcr.io/apache/kafka:3.9.1")
CH_CONF = REPO / "infra" / "dev" / "clickhouse"


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


@dataclass
class Analytics:
    http: str
    admin_password: str
    params: dict[str, str]
    bootstrap: str

    @property
    def db(self) -> str:
        return self.params["db"]

    def query(self, sql: str, user: str = "default", password: str | None = None, **settings: str) -> str:
        pw = self.admin_password if password is None else password
        ch = migrate.ClickHouse(self.http, user, pw)
        return ch.run(sql, **settings)

    def rows(self, sql: str, **kw: Any) -> list[dict[str, Any]]:
        out = self.query(sql + " FORMAT JSONEachRow", **kw)
        return [json.loads(line) for line in out.splitlines() if line]


@pytest.fixture(scope="session")
def analytics() -> Iterator[Analytics]:
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
        .with_network_aliases("kafka")
        .with_bind_ports(9094, kport)
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", "INTERNAL://:19092,EXTERNAL://:9094,CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", f"INTERNAL://kafka:19092,EXTERNAL://127.0.0.1:{kport}")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
        .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
        .with_env("KAFKA_NUM_PARTITIONS", "3")
    )
    admin_pw = secrets.token_hex(16)
    ch = (
        DockerContainer(CH_IMAGE)
        .with_network(net)
        .with_network_aliases("clickhouse")
        .with_exposed_ports(8123)
        .with_env("CLICKHOUSE_PASSWORD", admin_pw)
        .with_env("CLICKHOUSE_SKIP_USER_SETUP", "1")
        .with_volume_mapping(str(CH_CONF / "config.d" / "cell-dev.xml"), "/etc/clickhouse-server/config.d/cell-dev.xml")
        .with_volume_mapping(str(CH_CONF / "users.d" / "migrator.xml"), "/etc/clickhouse-server/users.d/migrator.xml")
    )
    kafka.start()
    ch.start()
    try:
        http = f"http://{ch.get_container_host_ip()}:{ch.get_exposed_port(8123)}"
        bootstrap = f"127.0.0.1:{kport}"
        _wait_clickhouse(http, admin_pw)
        topics = _create_topics(bootstrap)
        params = migrate.params_from_env({
            "ANALYTICS_CELL": "dev", "ANALYTICS_KAFKA_BROKERS": "kafka:19092",
            "ANALYTICS_KAFKA_GROUP": "clickhouse-analytics-it", "ANALYTICS_KAFKA_FLUSH_MS": "1000",
            "ANALYTICS_CUBE_PASSWORD": secrets.token_hex(16), "ANALYTICS_ANOMALY_PASSWORD": secrets.token_hex(16),
            "ANALYTICS_DICT_PASSWORD": secrets.token_hex(16),
        })
        migrate.migrate(migrate.ClickHouse(http, "default", admin_pw, distributed_ddl_task_timeout="300"), params)
        assert len(topics) == 17
        yield Analytics(http, admin_pw, params, bootstrap)
    finally:
        ch.stop()
        kafka.stop()
        net.remove()


def _wait_clickhouse(http: str, password: str) -> None:
    token = base64.b64encode(f"default:{password}".encode()).decode()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            req = urllib.request.Request(  # noqa: S310 - http của container kiểm thử
                http + "/?query=SELECT%201", headers={"Authorization": "Basic " + token})
            with urllib.request.urlopen(req, timeout=3) as r:  # noqa: S310
                if r.read().strip() == b"1":
                    return
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError("ClickHouse không sẵn sàng")


def _create_topics(bootstrap: str) -> list[str]:
    from confluent_kafka.admin import AdminClient, NewTopic

    sql = (ROOT / "migrations" / "V005__nguon_cdc_kafka.sql").read_text(encoding="utf-8")
    import re

    names = [t.replace("{{topic_core}}", "cdc.tenant_data").replace("{{topic_org}}", "cdc.org")
             for t in re.findall(r"kafka_topic_list = '([^']+)'", sql)]
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
    for f in admin.create_topics([NewTopic(n, num_partitions=3, replication_factor=1) for n in names]).values():
        f.result(30)
    return names
