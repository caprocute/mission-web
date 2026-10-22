"""Môi trường kiểm thử tích hợp DV-15-05: dùng lại môi trường DV-15-03 (ClickHouse có dữ liệu vàng, Cube bật SQL API)
rồi chạy Superset 4.x dựng từ analytics/superset/Dockerfile trên cùng mạng, CSDL siêu dữ liệu SQLite, khởi tạo bằng
khoi_tao.sh. Ảnh cần bánh xe psycopg2-binary trong wheels/ (bản dựng không có mạng ra ngoài)."""

from __future__ import annotations

import importlib.util
import sys
import json
import os
import pathlib
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

import pytest

HERE = pathlib.Path(__file__).resolve().parents[1]
CUBE_TESTS = HERE.parent / "cube" / "tests"
_spec = importlib.util.spec_from_file_location("cube_conftest", CUBE_TESTS / "conftest.py")
cube = importlib.util.module_from_spec(_spec)
sys.modules["cube_conftest"] = cube
_spec.loader.exec_module(cube)

env = cube.env  # fixture phiên của DV-15-03
Env = cube.Env
sym = cube.sym
BASE_IMAGE = os.environ.get("SUPERSET_BASE_IMAGE", "mirror.gcr.io/apache/superset:4.1.2")
IMAGE = "mission-superset:kiem-thu"
CATALOG = json.loads((HERE / "dashboards" / "nhung.json").read_text(encoding="utf-8"))
ORIGIN = "http://localhost:4200"


@dataclass
class Superset:
    url: str
    service_user: str
    service_password: str
    env: Any

    def call(self, method: str, path: str, body: Any = None, headers: dict[str, str] | None = None
             ) -> tuple[int, Any]:
        h = {"Content-Type": "application/json", **(headers or {})}
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.url + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:  # noqa: S310 - container kiểm thử
                raw, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        try:
            return status, json.loads(raw)
        except ValueError:
            return status, raw.decode(errors="replace")

    def access_token(self) -> str:
        status, body = self.call("POST", "/api/v1/security/login", {
            "username": self.service_user, "password": self.service_password, "provider": "db", "refresh": False})
        assert status == 200, body
        return body["access_token"]

    def guest_token(self, cube_jwt: str, embedded_uuid: str | None = None) -> str:
        emb = embedded_uuid or CATALOG["dashboards"][0]["embedded_uuid"]
        status, body = self.call("POST", "/api/v1/security/guest_token/", {
            "user": {"username": "khach", "first_name": "Khách", "last_name": "Nhúng"},
            "resources": [{"type": "dashboard", "id": emb}],
            "rls": [{"clause": f"__user = '{cube_jwt}'"}],
        }, {"Authorization": "Bearer " + self.access_token()})
        assert status == 200, body
        return body["token"]


@pytest.fixture(scope="session")
def superset(env: Any) -> Iterator[Superset]:
    if not list((HERE / "wheels").glob("psycopg2_binary-*.whl")):
        pytest.skip("cần bánh xe psycopg2-binary trong analytics/superset/wheels")
    subprocess.run(["docker", "build", "-q", "--build-arg", f"SUPERSET_IMAGE={BASE_IMAGE}", "-t", IMAGE, str(HERE)],
                   check=True, capture_output=True, timeout=900)
    from testcontainers.core.container import DockerContainer

    svc_user, svc_pw = "analytics_api", secrets.token_hex(16)
    container = (
        DockerContainer(IMAGE)
        .with_network(env.net)
        .with_exposed_ports(8088)
        .with_env("SUPERSET_SECRET_KEY", secrets.token_hex(32))
        .with_env("SUPERSET_METADATA_URI", "sqlite:////app/superset_home/superset.db")
        .with_env("SUPERSET_WEB_ORIGINS", ORIGIN)
        .with_env("SUPERSET_GUEST_TOKEN_SECRET", secrets.token_hex(32))
        .with_env("SUPERSET_COOKIE_SECURE", "false")
        .with_env("SUPERSET_ADMIN_USER", "quan_tri")
        .with_env("SUPERSET_ADMIN_PASSWORD", secrets.token_hex(16))
        .with_env("SUPERSET_SERVICE_USER", svc_user)
        .with_env("SUPERSET_SERVICE_PASSWORD", svc_pw)
        .with_env("CUBE_SQL_USER", env.sql_user)
        .with_env("CUBE_SQL_PASSWORD", env.sql_password)
        .with_env("CUBE_SQL_HOST", "cube:15432")
    )
    container.start()
    try:
        url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(8088)}"
        _wait(url + "/health", container, timeout=420)
        yield Superset(url, svc_user, svc_pw, env)
    finally:
        if os.environ.get("SUPERSET_LOGS"):
            out, err = container.get_logs()
            print(out.decode(errors="replace")[-20000:], err.decode(errors="replace")[-20000:])
        container.stop()


def _wait(url: str, container: Any, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as r:  # noqa: S310
                if r.read().strip() == b"OK":
                    return
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        if container.get_wrapped_container().status == "exited":
            break
        container.get_wrapped_container().reload()
        time.sleep(2)
    out, err = container.get_logs()
    raise RuntimeError("Superset không sẵn sàng:\n" + (out + err).decode(errors="replace")[-6000:])
