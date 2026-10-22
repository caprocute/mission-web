"""Kiểm thử tích hợp DV-14-01 trên PostgreSQL 17 + pgvector và OPA 1.4 thật nạp gói chính sách backend/opa:
migration, RLS giữa hai tenant, quyền của svc_ai, kiểm quyền API qua OPA, nhật ký gợi ý đã che dữ liệu."""

from __future__ import annotations

import hashlib
import os
import pathlib
import secrets
import time
import uuid
from collections.abc import Iterator
from typing import Any

import httpx
import psycopg
import pytest
import yaml
from fastapi.testclient import TestClient
from testcontainers.community.postgres import PostgresContainer
from testcontainers.core.container import DockerContainer

from app.core.config import Settings
from app.core.pipeline import SuggestionRequest
from app.llm.client import Message
from app.main import create_app
from tests.helpers import ISSUER, TENANT_A, TENANT_B, USER_1, USER_2, FakeIdp, Router, token

pytestmark = pytest.mark.integration
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


@pytest.fixture(scope="module")
def db() -> Iterator[dict[str, str]]:
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


@pytest.fixture(scope="module")
def opa() -> Iterator[str]:
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


@pytest.fixture(scope="module")
def client(db: dict[str, str], opa: str) -> Iterator[TestClient]:
    s = Settings(database_url=db["svc"], oidc_issuer=ISSUER, oidc_jwks_url=ISSUER + "/protocol/openid-connect/certs",
                 oidc_token_url=ISSUER + "/protocol/openid-connect/token", opa_url=opa,
                 core_base_url="http://core.test", llm_mode="mock")
    with TestClient(create_app(s, http_transport=Router({"idp.test": FakeIdp()}))) as tc:
        yield tc


def test_svc_ai_has_no_privilege_outside_ai(db: dict[str, str]) -> None:
    with psycopg.connect(db["admin"]) as c:
        rows = c.execute(
            """SELECT table_schema, table_name, privilege_type FROM information_schema.role_table_grants
                WHERE grantee = 'svc_ai' AND table_schema <> 'ai'""").fetchall()
        assert rows == []
        schemas = c.execute(
            """SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('ai', 'public', 'information_schema')
                 AND nspname NOT LIKE 'pg\\_%' AND (has_schema_privilege('svc_ai', oid, 'USAGE')
                 OR has_schema_privilege('svc_ai', oid, 'CREATE'))""").fetchall()
        assert schemas == []
        assert c.execute("SELECT has_schema_privilege('svc_ai', 'ai', 'CREATE')").fetchone() == (False,)
        assert c.execute("SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'svc_ai'").fetchone() == (
            False, False)
        opts = c.execute("SELECT reloptions FROM pg_class WHERE relname = 'ix_doc_chunk__embedding'").fetchone()
        assert opts is not None and sorted(opts[0]) == ["ef_construction=64", "m=16"]
    with psycopg.connect(db["svc"]) as c, pytest.raises(psycopg.errors.InsufficientPrivilege):
        c.execute("SELECT * FROM okr.objective")


def _chunk(c: psycopg.Connection[Any], tenant: str, cls: str = "C1") -> None:
    c.execute(
        """INSERT INTO ai.doc_chunk (id, tenant_id, source_type, source_id, chunk_no, content, embedding,
               embed_model, acl_unit_ids, classification)
           VALUES (%s, %s, 'VAN_BAN', %s, 0, 'Điều 4. Hệ số tiến độ', %s::vector, 'emb-vi', %s, %s)""",
        (uuid.uuid4(), tenant, uuid.uuid4(), "[" + ",".join(["0.1"] * 1024) + "]", [uuid.uuid4()], cls))


def test_rls_between_tenants(db: dict[str, str]) -> None:
    with psycopg.connect(db["svc"]) as c:
        with c.transaction():
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (TENANT_A,))
            _chunk(c, TENANT_A)
        with c.transaction():
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (TENANT_B,))
            assert c.execute("SELECT count(*) FROM ai.doc_chunk").fetchone() == (0,)
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                _chunk(c, TENANT_A)
        with c.transaction():
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (TENANT_A,))
            assert c.execute("SELECT count(*) FROM ai.doc_chunk").fetchone() == (1,)
            with pytest.raises(psycopg.errors.CheckViolation):
                _chunk(c, TENANT_A, "C2")
        with c.transaction():
            # Không đặt tenant thì không thấy gì (fail closed).
            assert c.execute("SELECT count(*) FROM ai.doc_chunk").fetchone() == (0,)


def test_api_authorization_through_real_opa(client: TestClient) -> None:
    path = f"/api/v1/ai/suggestions/{uuid.uuid4()}"
    assert client.get(path).status_code == 401
    r = client.get(path, headers={"Authorization": "Bearer " + token(roles=("inspector",))})
    assert r.status_code == 403 and r.headers["content-type"].startswith("application/problem+json")
    assert r.json()["code"] == "COMMON-403-FORBIDDEN"
    # ai_readonly chỉ được gọi POST tới module ai theo ma trận, không đọc nhật ký gợi ý.
    assert client.get(path, headers={"Authorization": "Bearer " + token(roles=("ai_readonly",))}).status_code == 403
    r = client.get(path, headers={"Authorization": "Bearer " + token(roles=("member",))})
    assert r.status_code == 404 and r.json()["code"] == "COMMON-404-NOT-FOUND"


def test_suggestion_logged_redacted_and_readable_by_owner_only(client: TestClient, db: dict[str, str]) -> None:
    app_state = client.app.state.svc  # type: ignore[attr-defined]
    raw = "Tóm tắt văn bản gửi đồng chí, email can.bo@donvi.vn, số định danh 001203004567"

    async def go() -> dict[str, Any]:
        principal = await app_state.verifier.verify(token(roles=("member",)))
        out: dict[str, Any] = await app_state.gateway.suggest(
            principal, SuggestionRequest("TOM_TAT_VAN_BAN", "v1", [Message("user", raw)],
                                         sources=[{"kind": "VAN_BAN", "title": "Văn bản chỉ đạo số 12"}]))
        return out

    assert client.portal is not None
    out = client.portal.call(go)
    sid = out["id"]
    with psycopg.connect(db["admin"]) as c:
        row = c.execute("SELECT prompt_redacted, prompt_sha256, tenant_id::text FROM ai.suggestion_log WHERE id = %s",
                        (sid,)).fetchone()
    assert row is not None
    assert "can.bo@donvi.vn" not in row[0] and "001203004567" not in row[0]
    # Rào chắn đầu vào đã che trước khi gửi mô hình, nên băm là của đúng lời nhắc đã gửi.
    assert "[EMAIL]" in row[0] and "[SỐ ĐỊNH DANH]" in row[0]
    assert bytes(row[1]) == hashlib.sha256(row[0].encode()).digest() and row[2] == TENANT_A

    r = client.get(f"/api/v1/ai/suggestions/{sid}", headers={"Authorization": "Bearer " + token(roles=("member",))})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["aiGenerated"] is True and body["label"] == "Nội dung do AI tạo"
    assert body["useCase"] == "TOM_TAT_VAN_BAN"
    _validate(body)
    other_user = token(user=USER_2, roles=("member",))
    other_tenant = token(user=USER_1, tenant=TENANT_B, roles=("member",))
    for t in (other_user, other_tenant):
        assert client.get(f"/api/v1/ai/suggestions/{sid}", headers={"Authorization": "Bearer " + t}).status_code == 404


def test_blocked_request_writes_guardrail_event(client: TestClient, db: dict[str, str]) -> None:
    gw = client.app.state.svc.gateway  # type: ignore[attr-defined]
    verifier = client.app.state.svc.verifier  # type: ignore[attr-defined]

    async def go() -> dict[str, Any]:
        principal = await verifier.verify(token(roles=("manager",)))
        out: dict[str, Any] = await gw.suggest(
            principal, SuggestionRequest("HOI_DAP_QUY_DINH", "v1",
                                         [Message("user", "Đề xuất xếp loại cho đồng chí Nguyễn Văn A")]))
        return out

    assert client.portal is not None
    out = client.portal.call(go)
    assert out["blocked"]["ruleCode"] == "OUT_OF_SCOPE" and out["outputText"] is None
    with psycopg.connect(db["admin"]) as c:
        rows = c.execute(
            """SELECT g.rule_code, g.action, g.detail, s.output_text FROM ai.guardrail_event g
                 JOIN ai.suggestion_log s ON s.id = g.suggestion_log_id WHERE s.id = %s""", (out["id"],)).fetchall()
    assert rows == [("OUT_OF_SCOPE", "CHAN", {"rule": "BR-17-04"}, None)]


def _validate(body: dict[str, Any]) -> None:
    """So khớp phản hồi với lược đồ AiSuggestion của contracts/openapi/modules/platform.yaml."""
    import jsonschema
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012

    base = REPO / "contracts" / "openapi"
    files = [base / "modules" / "platform.yaml", base / "common" / "common.yaml"]
    reg: Registry[Any] = Registry().with_resources(
        (f.as_uri(), Resource.from_contents(yaml.safe_load(f.read_text(encoding="utf-8")),
                                            default_specification=DRAFT202012)) for f in files)
    schema = {"$ref": (base / "modules" / "platform.yaml").as_uri() + "#/components/schemas/AiSuggestion"}
    jsonschema.Draft202012Validator(schema, registry=reg, format_checker=jsonschema.FormatChecker()).validate(body)
