"""Môi trường kiểm thử tích hợp DV-15-03: ClickHouse 25.8 dựng bằng cấu hình infra/dev/clickhouse và toàn bộ migration
của kho (DV-15-01), nạp dữ liệu vàng data/golden/analytics-measures, rồi chạy Cube với mô hình trong analytics/cube."""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import pathlib
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any
from zoneinfo import ZoneInfo

import pytest

CUBE_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO = CUBE_DIR.parents[1]
CH_DIR = REPO / "analytics" / "clickhouse"
GOLDEN = REPO / "data" / "golden" / "analytics-measures"
sys.path.insert(0, str(CH_DIR))

import migrate  # noqa: E402

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
CH_IMAGE = os.environ.get("CLICKHOUSE_IMAGE", "mirror.gcr.io/clickhouse/clickhouse-server:25.8")
CUBE_IMAGE = os.environ.get("CUBE_IMAGE", "mirror.gcr.io/cubejs/cube:v1.3.60")
CH_CONF = REPO / "infra" / "dev" / "clickhouse"
VN = ZoneInfo("Asia/Ho_Chi_Minh")
NS = uuid.UUID("6f1d0c1e-7a54-4a0e-9a63-3b1f0d0a2c15")


def sym(name: str) -> str:
    """Mã tượng trưng '#X' trong dữ liệu vàng → UUID cố định."""
    return str(uuid.uuid5(NS, name))


def resolve(v: Any, now: dt.datetime) -> Any:
    if not isinstance(v, str):
        return v
    if v.startswith("#"):
        return sym(v[1:])
    if v.startswith("@today"):
        return (now.date() + dt.timedelta(days=int(v[6:]))).isoformat()
    if v.startswith("@now"):
        return (now + dt.timedelta(days=int(v[4:]))).strftime("%Y-%m-%d %H:%M:%S")
    return v


def _docker() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


@dataclass
class Env:
    ch_http: str
    admin_password: str
    db: str
    cube_url: str
    api_secret: str
    cube_password: str
    cube: Any

    def ch(self, sql: str, **settings: str) -> str:
        return migrate.ClickHouse(self.ch_http, "default", self.admin_password).run(sql, **settings)

    def token(self, claims: dict[str, Any]) -> str:
        def b64(b: bytes) -> str:
            return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

        header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        body = b64(json.dumps({"exp": int(time.time()) + 600, **claims}).encode())
        sig = hmac.new(self.api_secret.encode(), f"{header}.{body}".encode(), hashlib.sha256).digest()
        return f"{header}.{body}.{b64(sig)}"

    def load(self, query: dict[str, Any], token: str | None, timeout: float = 120) -> tuple[int, dict[str, Any]]:
        """Gọi REST /load; chờ khi Cube trả 'Continue wait' (đang dựng pre-aggregation)."""
        data = json.dumps({"query": query}).encode()
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = token
        deadline = time.monotonic() + timeout
        while True:
            req = urllib.request.Request(self.cube_url + "/cubejs-api/v1/load", data=data, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    body = json.loads(r.read())
                    status = r.status
            except urllib.error.HTTPError as e:
                raw = e.read()
                try:
                    body = json.loads(raw)
                except ValueError:
                    body = {"error": raw.decode(errors="replace")}
                status = e.code
            if body.get("error") == "Continue wait" and time.monotonic() < deadline:
                time.sleep(0.3)
                continue
            return status, body


@pytest.fixture(scope="session")
def env() -> Iterator[Env]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.network import Network

    net = Network()
    net.create()
    admin_pw, cube_pw, secret = secrets.token_hex(16), secrets.token_hex(16), secrets.token_hex(32)
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
    ch.start()
    cube = None
    try:
        http = f"http://{ch.get_container_host_ip()}:{ch.get_exposed_port(8123)}"
        _wait(http + "/ping", b"Ok.")
        params = migrate.params_from_env({
            "ANALYTICS_CELL": "dev", "ANALYTICS_KAFKA_BROKERS": "kafka-khong-co:9092",
            "ANALYTICS_CUBE_PASSWORD": cube_pw, "ANALYTICS_ANOMALY_PASSWORD": secrets.token_hex(16),
            "ANALYTICS_DICT_PASSWORD": secrets.token_hex(16),
        })
        migrate.migrate(migrate.ClickHouse(http, "default", admin_pw, distributed_ddl_task_timeout="300"), params)
        db = params["db"]
        load_golden(migrate.ClickHouse(http, "default", admin_pw), db)
        tenants = ",".join(sym(t) for t in ("A", "B"))
        cube = (
            DockerContainer(CUBE_IMAGE)
            .with_network(net)
            .with_exposed_ports(4000)
            .with_volume_mapping(str(CUBE_DIR / "cube.js"), "/cube/conf/cube.js")
            .with_volume_mapping(str(CUBE_DIR / "model"), "/cube/conf/model")
            .with_env("CUBEJS_DB_TYPE", "clickhouse")
            .with_env("CUBEJS_DB_HOST", "clickhouse")
            .with_env("CUBEJS_DB_PORT", "8123")
            .with_env("CUBEJS_DB_NAME", db)
            .with_env("CUBEJS_DB_USER", f"{db}_cube")
            .with_env("CUBEJS_DB_PASS", cube_pw)
            .with_env("CUBEJS_API_SECRET", secret)
            .with_env("CUBEJS_DEV_MODE", "false")
            .with_env("CUBEJS_CACHE_AND_QUEUE_DRIVER", "memory")
            .with_env("CUBEJS_REFRESH_WORKER", "true")
            .with_env("CUBE_REFRESH_TENANTS", tenants)
            .with_env("CUBEJS_TELEMETRY", "false")
            .with_env("CUBEJS_LOG_LEVEL", "info")
        )
        cube.start()
        url = f"http://{cube.get_container_host_ip()}:{cube.get_exposed_port(4000)}"
        _wait(url + "/readyz", None, timeout=120)
        yield Env(http, admin_pw, db, url, secret, cube_pw, cube)
    finally:
        if cube is not None:
            if os.environ.get("CUBE_LOGS"):
                out, err = cube.get_logs()
                print(out.decode(errors="replace")[-20000:], err.decode(errors="replace")[-20000:])
            cube.stop()
        ch.stop()
        net.remove()


def load_golden(ch: migrate.ClickHouse, db: str) -> None:
    now = dt.datetime.now(VN).replace(microsecond=0)
    data = json.loads((GOLDEN / "du-lieu.json").read_text(encoding="utf-8"))
    stamp = now.strftime("%Y-%m-%d %H:%M:%S")
    for table, rows in data.items():
        if table.startswith("_"):
            continue
        lines = [json.dumps({**{k: resolve(v, now) for k, v in r.items()}, "_version": 1, "_is_deleted": 0,
                             "_source_ts": stamp}) for r in rows]
        ch.run(f"INSERT INTO {db}.{table} FORMAT JSONEachRow\n" + "\n".join(lines))
    for mv in ("mv_dim_org_scope", "mv_data_freshness"):
        ch.run(f"SYSTEM REFRESH VIEW {db}.{mv}")
        ch.run(f"SYSTEM WAIT VIEW {db}.{mv}")


def _wait(url: str, expect: bytes | None, timeout: float = 90) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:
                if expect is None or r.read().strip() == expect:
                    return
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError(f"{url} không sẵn sàng")
