"""Kiểm thử tích hợp DV-15-02 trên PostgreSQL 17, Kafka 3.9 và Debezium Connect 3.0 thật, với plugin
TenantPseudonymize đóng từ analytics/cdc/smt: cấu hình do cdc-config-gen sinh từ pg_description, publication do công
cụ sinh, thông điệp trên topic cdc.* không có cột C2, mã người là băm 32 byte khác nhau giữa hai tenant cho cùng giá
trị, bản ghi sửa và xoá mang __op, __lsn, __source_ts_ms, __deleted và đủ cột nhờ REPLICA IDENTITY FULL."""

from __future__ import annotations

import json
import os
import secrets
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Iterator
from typing import Any

import pytest

import cdc_config_gen as gen

pytestmark = pytest.mark.integration
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

PG_IMAGE = os.environ.get("POSTGRES_IMAGE", "mirror.gcr.io/library/postgres:17-alpine")
KAFKA_IMAGE = os.environ.get("KAFKA_IMAGE", "mirror.gcr.io/apache/kafka:3.9.1")
CONNECT_IMAGE = os.environ.get("CONNECT_IMAGE", "quay.io/debezium/connect:3.0.0.Final")
SMT = gen.HERE / "smt"
TENANT_A = "0192f000-0000-7000-8000-00000000000a"
TENANT_B = "0192f000-0000-7000-8000-00000000000b"
PERSON = "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701"

SCHEMA = """
CREATE TABLE public.debezium_heartbeat (slot_name text PRIMARY KEY, beat_at timestamptz NOT NULL);
CREATE SCHEMA task;
CREATE TABLE task.task_progress (
  id uuid NOT NULL, tenant_id uuid NOT NULL, period_bucket int NOT NULL, task_id uuid NOT NULL,
  created_by uuid NOT NULL, progress_pct numeric(6,4) NOT NULL, note_enc bytea, contact_phone varchar(20),
  reported_at timestamptz NOT NULL, reported_on date NOT NULL,
  CONSTRAINT pk_task_progress PRIMARY KEY (period_bucket, tenant_id, id)
) PARTITION BY LIST (period_bucket);
CREATE TABLE task.task_progress_p202604 PARTITION OF task.task_progress FOR VALUES IN (202604);
CREATE SCHEMA org_replica;
CREATE TABLE org_replica.person_v (
  tenant_id uuid NOT NULL, id uuid NOT NULL, full_name varchar(200) NOT NULL, is_party_member boolean NOT NULL,
  CONSTRAINT pk_person_v PRIMARY KEY (tenant_id, id)
);
CREATE TABLE task.no_comment (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
COMMENT ON COLUMN task.no_comment.id IS '{"cls":"C1"}';
"""
COMMENTS = {
    "task.task_progress": {"id": "C1", "tenant_id": "C1", "period_bucket": "C1", "task_id": "C1", "created_by": "C1",
                           "progress_pct": "C1", "note_enc": "C2", "contact_phone": "C2", "reported_at": "C1",
                           "reported_on": "C1"},
    "org_replica.person_v": {"tenant_id": "C1", "id": "C1", "full_name": "C1", "is_party_member": "C1"},
}
WANTED = [("task", "task_progress"), ("org_replica", "person_v")]


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


def _smt_jar(tmp: Any) -> str:
    """Đóng jar plugin bằng Maven của backend (chỉ biên dịch, kiểm thử Java chạy riêng) và đặt vào thư mục plugin."""
    jar = SMT / "target" / "analytics-cdc-smt-0.1.0-SNAPSHOT.jar"
    if not jar.exists():
        subprocess.run([str(gen.REPO / "backend" / "mvnw"), "-q", "-f", str(SMT / "pom.xml"), "package",
                        "-DskipTests"], check=True, timeout=600)
    plugin = tmp / "plugins" / "vt-analytics-smt"
    plugin.mkdir(parents=True)
    shutil.copy(jar, plugin / jar.name)
    os.chmod(tmp / "plugins", 0o755)
    os.chmod(plugin, 0o755)
    os.chmod(plugin / jar.name, 0o644)
    return str(tmp / "plugins" / "vt-analytics-smt")


def _http(method: str, url: str, body: dict[str, Any] | None = None) -> tuple[int, Any]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,  # noqa: S310 - container kiểm thử
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:  # noqa: S310
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


@pytest.fixture(scope="module")
def stack(tmp_path_factory: pytest.TempPathFactory) -> Iterator[dict[str, Any]]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    import psycopg
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.network import Network

    plugin_dir = _smt_jar(tmp_path_factory.mktemp("connect"))
    net = Network()
    net.create()
    pg_pw, cdc_pw, key = secrets.token_hex(12), secrets.token_hex(12), secrets.token_hex(32)
    pg = (DockerContainer(PG_IMAGE).with_network(net).with_network_aliases("postgres").with_exposed_ports(5432)
          .with_env("POSTGRES_PASSWORD", pg_pw).with_env("POSTGRES_DB", "tenant_data")
          .with_command("postgres -c wal_level=logical -c max_replication_slots=4 -c max_wal_senders=4"))
    kport = _free_port()
    kafka = (DockerContainer(KAFKA_IMAGE).with_network(net).with_network_aliases("kafka").with_bind_ports(9094, kport)
             .with_env("KAFKA_NODE_ID", "1").with_env("KAFKA_PROCESS_ROLES", "broker,controller")
             .with_env("KAFKA_LISTENERS", "INTERNAL://:19092,EXTERNAL://:9094,CONTROLLER://:9093")
             .with_env("KAFKA_ADVERTISED_LISTENERS", f"INTERNAL://kafka:19092,EXTERNAL://127.0.0.1:{kport}")
             .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                       "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
             .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
             .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
             .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
             .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
             .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
             .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"))
    connect = (DockerContainer(CONNECT_IMAGE).with_network(net).with_exposed_ports(8083)
               .with_env("BOOTSTRAP_SERVERS", "kafka:19092").with_env("GROUP_ID", "connect-it")
               .with_env("CONFIG_STORAGE_TOPIC", "connect-it-configs")
               .with_env("OFFSET_STORAGE_TOPIC", "connect-it-offsets")
               .with_env("STATUS_STORAGE_TOPIC", "connect-it-status")
               .with_env("CONFIG_STORAGE_REPLICATION_FACTOR", "1").with_env("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
               .with_env("STATUS_STORAGE_REPLICATION_FACTOR", "1")
               .with_env("CONNECT_CONFIG_PROVIDERS", "env")
               .with_env("CONNECT_CONFIG_PROVIDERS_ENV_CLASS",
                         "org.apache.kafka.common.config.provider.EnvVarConfigProvider")
               # Biến môi trường mà cấu hình sinh ra tham chiếu qua ${env:...}; ở cụm thật do Secret cấp.
               .with_env("PG_HOST", "postgres").with_env("PG_PORT", "5432")
               .with_env("PG_CDC_ANALYTICS_USER", "cdc_analytics").with_env("PG_CDC_ANALYTICS_PASSWORD", cdc_pw)
               .with_env("ANALYTICS_PSEUDONYM_KEY", key)
               .with_volume_mapping(plugin_dir, "/kafka/connect/vt-analytics-smt"))
    pg.start()
    kafka.start()
    try:
        dsn = (f"postgresql://postgres:{pg_pw}@{pg.get_container_host_ip()}:{pg.get_exposed_port(5432)}/tenant_data")
        deadline = time.monotonic() + 60
        while True:
            try:
                psycopg.connect(dsn).close()
                break
            except psycopg.OperationalError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(1)
        with psycopg.connect(dsn, autocommit=True) as c:
            c.execute(SCHEMA)
            for table, cols in COMMENTS.items():
                for col, cls in cols.items():
                    c.execute(f"COMMENT ON COLUMN {table}.{col} IS '{json.dumps({'cls': cls})}'")
            # Vai trò CDC phân tích: chỉ REPLICATION và SELECT bảng được đưa vào (tương ứng cdc_<csdl> của outbox).
            c.execute(f"CREATE ROLE cdc_analytics LOGIN REPLICATION PASSWORD '{cdc_pw}'")
            c.execute("GRANT USAGE ON SCHEMA task, org_replica TO cdc_analytics")
            c.execute("GRANT SELECT ON task.task_progress, task.task_progress_p202604, org_replica.person_v "
                      "TO cdc_analytics")
            c.execute("GRANT INSERT, UPDATE ON public.debezium_heartbeat TO cdc_analytics")
        connect.start()
        url = f"http://{connect.get_container_host_ip()}:{connect.get_exposed_port(8083)}"
        deadline = time.monotonic() + 120
        while True:
            try:
                if _http("GET", url + "/connector-plugins")[0] == 200:
                    break
            except (urllib.error.URLError, ConnectionError, OSError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Kafka Connect không sẵn sàng")
            time.sleep(2)
        yield {"dsn": dsn, "connect": url, "bootstrap": f"127.0.0.1:{kport}", "key": key}
    finally:
        for c in (connect, kafka, pg):
            try:
                c.stop()
            except Exception:  # noqa: S110 - dọn dẹp
                pass
        net.remove()


def test_config_from_pg_description_requires_comments(stack: dict[str, Any]) -> None:
    tables = gen.tables_from_database(stack["dsn"], [*WANTED, ("task", "no_comment")])
    with pytest.raises(gen.GenError, match=r"task\.no_comment\.tenant_id: thiếu COMMENT"):
        gen.plan("tenant_data", [*WANTED, ("task", "no_comment")], tables)


def _consume(bootstrap: str, topics: list[str], want: int, timeout: float = 120) -> list[tuple[Any, Any]]:
    from confluent_kafka import Consumer

    c = Consumer({"bootstrap.servers": bootstrap, "group.id": f"it-{uuid.uuid4()}", "auto.offset.reset": "earliest"})
    c.subscribe(topics)
    out: list[tuple[Any, Any]] = []
    deadline = time.monotonic() + timeout
    try:
        while len(out) < want and time.monotonic() < deadline:
            m = c.poll(1.0)
            if m is None or m.error():
                continue
            out.append((json.loads(m.key()) if m.key() else None, json.loads(m.value()) if m.value() else None))
    finally:
        c.close()
    return out


def test_cdc_messages_carry_no_c2_and_tenant_salted_person_keys(stack: dict[str, Any]) -> None:
    import psycopg

    tables = gen.tables_from_database(stack["dsn"], WANTED)
    p = gen.plan("tenant_data", WANTED, tables)
    assert set(p.excluded) == {"task.task_progress.note_enc", "task.task_progress.contact_phone",
                               "org_replica.person_v.full_name"}
    with psycopg.connect(stack["dsn"], autocommit=True) as c:
        c.execute(gen.publication_sql(p))
        ident = c.execute("""SELECT relname, relreplident FROM pg_class
                              WHERE relname IN ('task_progress', 'task_progress_p202604', 'person_v')
                              ORDER BY relname""").fetchall()
        assert ident == [("person_v", "f"), ("task_progress", "f"), ("task_progress_p202604", "f")]
        c.execute(gen.publication_sql(p))  # chạy lại được
    cfg = gen.connector_config(p)
    status, body = _http("POST", stack["connect"] + "/connectors", {"name": "cdc-tenant-data", "config": cfg})
    assert status == 201, body

    pa, pb = str(uuid.uuid4()), str(uuid.uuid4())
    with psycopg.connect(stack["dsn"], autocommit=True) as c:
        deadline = time.monotonic() + 90
        while not c.execute("SELECT 1 FROM pg_replication_slots WHERE slot_name = 'dbz_cdc_tenant_data' "
                            "AND active").fetchone():
            assert time.monotonic() < deadline, _http("GET", stack["connect"] + "/connectors/cdc-tenant-data/status")
            time.sleep(1)
        for pid, tenant in ((pa, TENANT_A), (pb, TENANT_B)):
            c.execute("""INSERT INTO task.task_progress (id, tenant_id, period_bucket, task_id, created_by,
                             progress_pct, note_enc, contact_phone, reported_at, reported_on)
                         VALUES (%s, %s, 202604, %s, %s, 0.25, '\\x0102', '0912345678', now(), current_date)""",
                      (pid, tenant, str(uuid.uuid4()), PERSON))
        c.execute("UPDATE task.task_progress SET progress_pct = 0.5 WHERE id = %s", (pa,))
        c.execute("DELETE FROM task.task_progress WHERE id = %s", (pb,))
        c.execute("INSERT INTO org_replica.person_v VALUES (%s, %s, 'Nguyễn Văn A', true), (%s, %s, 'Nguyễn Văn A', "
                  "false)", (TENANT_A, PERSON, TENANT_B, PERSON))

    msgs = _consume(stack["bootstrap"], ["cdc.tenant_data.task.task_progress", "cdc.tenant_data.org_replica.person_v"],
                    want=6)
    progress = [(k, v) for k, v in msgs if v and "task_id" in v]
    people = [(k, v) for k, v in msgs if v and "is_party_member" in v]
    assert len(progress) == 4 and len(people) == 2, msgs

    for _, v in progress + people:
        assert not {"note_enc", "contact_phone", "full_name"} & set(v), v
        assert {"__op", "__lsn", "__source_ts_ms", "__deleted"} <= set(v), v
        assert "0912345678" not in json.dumps(v) and "Nguyễn" not in json.dumps(v) and PERSON not in json.dumps(v)

    by_tenant = {v["tenant_id"]: v["created_by"] for _, v in progress if v["__op"] == "c"}
    ha, hb = by_tenant[TENANT_A], by_tenant[TENANT_B]
    assert len(bytes.fromhex(ha)) == 32 and len(bytes.fromhex(hb)) == 32 and ha != hb

    update = next(v for _, v in progress if v["__op"] == "u")
    assert update["progress_pct"] == "0.5000" and update["created_by"] == ha
    delete = next(v for _, v in progress if v["__op"] == "d")
    # REPLICA IDENTITY FULL: bản ghi xoá mang đủ cột (task_id) để ClickHouse gộp đúng khoá sắp xếp.
    assert delete["__deleted"] == "true" and delete["id"] == pb and delete["task_id"] and delete["created_by"] == hb
    assert isinstance(update["reported_on"], int) and update["reported_at"].endswith("Z")

    keys = {v["tenant_id"]: (k, v) for k, v in people}
    for tenant in (TENANT_A, TENANT_B):
        k, v = keys[tenant]
        assert k["id"] == v["id"] and len(bytes.fromhex(v["id"])) == 32  # khoá Kafka cũng không mang mã người rõ
    assert keys[TENANT_A][1]["id"] != keys[TENANT_B][1]["id"]
    assert keys[TENANT_A][1]["id"] == ha  # cùng người, cùng tenant: cùng mã giả danh ở mọi bảng


def test_completed_process_history_reaches_topic(stack: dict[str, Any]) -> None:
    """DV-06-11: CSDL flowable dựng bằng đúng migration của workflow-service (V1..V7, phân loại cột ở V7); một tiến
    trình đã kết thúc ghi vào ACT_HI_* như engine làm tới topic cdc.flowable.flowable.act_hi_* trong 60 s, không mang
    người xử lý hay văn bản tự do."""
    import psycopg

    admin = stack["dsn"].rsplit("/", 1)[0]
    with psycopg.connect(stack["dsn"], autocommit=True) as c:
        c.execute("CREATE DATABASE flowable")
    dsn = admin + "/flowable"
    mig = gen.MIGRATIONS["flowable"][0] / "flowable"
    with psycopg.connect(dsn, autocommit=True) as c:
        c.execute("CREATE SCHEMA flowable")
        c.execute("CREATE TABLE public.debezium_heartbeat (slot_name text PRIMARY KEY, beat_at timestamptz NOT NULL)")
        for f in sorted(mig.glob("V*.sql"), key=lambda x: int(x.name[1:].split("__")[0])):
            c.execute(f.read_text().replace("${flyway:defaultSchema}", "flowable"))
        c.execute("GRANT USAGE ON SCHEMA flowable TO cdc_analytics")
        c.execute("GRANT SELECT ON flowable.act_hi_procinst, flowable.act_hi_taskinst, flowable.act_hi_actinst "
                  "TO cdc_analytics")
        c.execute("GRANT INSERT, UPDATE ON public.debezium_heartbeat TO cdc_analytics")
    wanted = gen.wanted_tables()["flowable"]
    p = gen.plan("flowable", wanted, gen.tables_from_database(dsn, wanted))
    assert {"flowable.act_hi_procinst.start_user_id_", "flowable.act_hi_taskinst.assignee_",
            "flowable.act_hi_taskinst.description_", "flowable.act_hi_procinst.delete_reason_"} <= set(p.excluded)
    assert not p.pseudonymized
    with psycopg.connect(dsn, autocommit=True) as c:
        c.execute(gen.publication_sql(p))
    cfg = gen.connector_config(p)
    assert cfg["transforms"] == "unwrap" and not any(k.startswith("transforms.pseudo.") for k in cfg)
    status, body = _http("POST", stack["connect"] + "/connectors", {"name": "cdc-flowable", "config": cfg})
    assert status == 201, body

    pid, tid, aid = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    t0 = time.monotonic()
    with psycopg.connect(dsn, autocommit=True) as c:
        deadline = time.monotonic() + 90
        while not c.execute("SELECT 1 FROM pg_replication_slots WHERE slot_name = 'dbz_cdc_flowable' "
                            "AND active").fetchone():
            assert time.monotonic() < deadline, _http("GET", stack["connect"] + "/connectors/cdc-flowable/status")
            time.sleep(1)
        t0 = time.monotonic()
        c.execute("""INSERT INTO flowable.act_hi_procinst (id_, rev_, proc_inst_id_, business_key_, proc_def_id_,
                         start_time_, start_user_id_, start_act_id_, tenant_id_, name_)
                     VALUES (%s, 1, %s, %s, 'giao-nhiem-vu:3:abc', now() - interval '1 hour', %s, 'batDau', %s,
                             'Giao nhiệm vụ cho Nguyễn Văn A')""", (pid, pid, f"TASK:{uuid.uuid4()}", PERSON, TENANT_A))
        c.execute("""INSERT INTO flowable.act_hi_taskinst (id_, rev_, proc_def_id_, task_def_key_, proc_inst_id_,
                         name_, assignee_, description_, start_time_, priority_, tenant_id_)
                     VALUES (%s, 1, 'giao-nhiem-vu:3:abc', 'nghiemThu', %s, 'Nghiệm thu', %s, 'ghi chú riêng',
                             now() - interval '1 hour', 50, %s)""", (tid, pid, PERSON, TENANT_A))
        c.execute("""INSERT INTO flowable.act_hi_actinst (id_, rev_, proc_def_id_, proc_inst_id_, execution_id_,
                         act_id_, task_id_, act_type_, assignee_, start_time_, tenant_id_)
                     VALUES (%s, 1, 'giao-nhiem-vu:3:abc', %s, %s, 'nghiemThu', %s, 'userTask', %s,
                             now() - interval '1 hour', %s)""", (aid, pid, pid, tid, PERSON, TENANT_A))
        c.execute("UPDATE flowable.act_hi_taskinst SET end_time_ = now(), duration_ = 3600000, completed_by_ = %s "
                  "WHERE id_ = %s", (PERSON, tid))
        c.execute("UPDATE flowable.act_hi_actinst SET end_time_ = now(), duration_ = 3600000 WHERE id_ = %s", (aid,))
        c.execute("UPDATE flowable.act_hi_procinst SET end_time_ = now(), duration_ = 3600000, end_act_id_ = "
                  "'ketThuc' WHERE id_ = %s", (pid,))
    topics = [f"cdc.flowable.flowable.{t}" for t in ("act_hi_procinst", "act_hi_taskinst", "act_hi_actinst")]
    msgs = _consume(stack["bootstrap"], topics, want=6, timeout=60)
    took = time.monotonic() - t0
    print(f"\nDV-06-11 lịch sử tiến trình tới topic sau {took:.1f}s")
    assert took <= 60 and len(msgs) == 6, msgs
    ended = next(v for _, v in msgs if v["id_"] == pid and v["__op"] == "u")
    assert ended["end_time_"] and ended["end_act_id_"] == "ketThuc" and isinstance(ended["start_time_"], int)
    assert ended["tenant_id_"] == TENANT_A
    for _, v in msgs:
        assert not {"start_user_id_", "assignee_", "completed_by_", "description_", "delete_reason_"} & set(v), v
        assert PERSON not in json.dumps(v) and "Nguyễn" not in json.dumps(v) and "ghi chú" not in json.dumps(v)
