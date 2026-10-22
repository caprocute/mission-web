"""Container dùng chung cho kiểm thử tích hợp: PostgreSQL 17 + pgvector đã áp migration, OPA nạp backend/opa."""

from __future__ import annotations

import os
import pathlib
import secrets
import time
from collections.abc import Iterator

import httpx
import psycopg
import pytest

os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
MIGRATIONS = sorted((ROOT / "db" / "migration" / "ai").glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0]))


def _docker() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


if not _docker():
    pytest.skip("kiểm thử tích hợp cần Docker", allow_module_level=True)


@pytest.fixture(scope="session")
def db() -> Iterator[dict[str, str]]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    from testcontainers.community.postgres import PostgresContainer

    pg = PostgresContainer("pgvector/pgvector:pg17", username="postgres", password=secrets.token_hex(12),
                           dbname="ai", driver=None)
    pg.start()
    try:
        admin = pg.get_connection_url()
        mig_pw, svc_pw = secrets.token_hex(12), secrets.token_hex(12)
        with psycopg.connect(admin, autocommit=True) as c:
            # Tương ứng infra/dev/postgres/migrate-roles.sh: quản trị cài extension và tạo vai trò; một schema
            # nghiệp vụ mẫu để kiểm svc_ai không có quyền ngoài schema ai.
            c.execute("CREATE EXTENSION vector")
            c.execute(f"CREATE ROLE mig_ai LOGIN PASSWORD '{mig_pw}' NOINHERIT NOBYPASSRLS")
            c.execute("CREATE ROLE svc_ai NOLOGIN NOINHERIT NOBYPASSRLS")
            c.execute("CREATE ROLE ro_ai NOLOGIN NOINHERIT NOBYPASSRLS")
            c.execute("GRANT CREATE ON DATABASE ai TO mig_ai")
            c.execute("CREATE SCHEMA okr; CREATE TABLE okr.objective (id uuid PRIMARY KEY, tenant_id uuid)")
        mig = admin.replace("postgres:", "mig_ai:", 1).split("@")
        mig_dsn = f"postgresql://mig_ai:{mig_pw}@{mig[1]}"
        with psycopg.connect(mig_dsn, autocommit=True) as c:
            for f in MIGRATIONS:
                c.execute(f.read_text(encoding="utf-8"))
        with psycopg.connect(admin, autocommit=True) as c:
            c.execute(f"ALTER ROLE svc_ai LOGIN PASSWORD '{svc_pw}'")
        host = admin.split("@")[1]
        yield {"admin": admin, "svc": f"postgresql://svc_ai:{svc_pw}@{host}"}
    finally:
        pg.stop()


@pytest.fixture(scope="session")
def opa() -> Iterator[str]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    from testcontainers.core.container import DockerContainer

    c = (DockerContainer("openpolicyagent/opa:1.4.0")
         .with_volume_mapping(str(REPO / "backend" / "opa"), "/policies", "ro")
         .with_command("run --server --addr=0.0.0.0:8181 --disable-telemetry /policies")
         .with_exposed_ports(8181))
    c.start()
    try:
        url = f"http://{c.get_container_host_ip()}:{c.get_exposed_port(8181)}"
        for _ in range(100):
            try:
                if httpx.get(url + "/health?bundles", timeout=1).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(0.2)
        yield url
    finally:
        c.stop()


