#!/usr/bin/env python3
"""Nối luồng CDC phân tích của dev: PostgreSQL → Debezium → Kafka → ClickHouse (DV-15-01, DV-15-02).

Production có ba CSDL nguồn (`tenant_data` của mission-core, `org` của org-service, `flowable` của workflow-service),
mỗi CSDL một connector sinh sẵn ở analytics/cdc/generated/<db>/connector.yaml. Dev gộp mission-core và org-service
vào một CSDL `mission`, nên script dựng hai connector từ chính các tệp sinh đó (không chép tay danh sách bảng, cột
loại, cột giả danh):

- `cdc-mission`: gộp bảng của `tenant_data` và `org`, đọc CSDL `mission`, topic `cdc.mission.<schema>.<table>`;
- `cdc-flowable`: giữ nguyên tệp sinh của `flowable`, topic `cdc.flowable.<schema>.<table>`.

Các bước, mỗi bước chạy lại an toàn:

1. Áp migration kho ClickHouse (analytics/clickhouse/migrate.py) với ANALYTICS_CORE_DB = ANALYTICS_ORG_DB = mission để
   bảng Kafka engine đọc đúng topic của dev.
2. Trong từng CSDL nguồn (vai trò quản trị qua docker exec): bảng `public.debezium_heartbeat`, quyền đọc của vai trò
   CDC, publication `pub_analytics` (publish_via_partition_root) và REPLICA IDENTITY FULL cho bảng cha và mọi phân
   vùng hiện có. Phân vùng theo kỳ tạo sau (tao-phan-vung-ky.sh) cần chạy lại script để đặt REPLICA IDENTITY.
3. Đăng ký hoặc cập nhật connector qua REST của Kafka Connect (PUT /connectors/<tên>/config) rồi chờ RUNNING.
   Mật khẩu vai trò CDC và khoá giả danh nằm ở dạng `${env:...}`: worker Debezium đọc từ biến môi trường của
   container (docker-compose.yml), không lưu giá trị vào topic cấu hình của Connect.
4. Chờ bảng `dim_org_scope` (làm mới mỗi phút) có dòng, tức analytics-api đã dựng được phạm vi theo cây tổ chức.

    python3 -I infra/dev/scripts/noi-cdc-phan-tich.py            # mọi bước
    python3 -I infra/dev/scripts/noi-cdc-phan-tich.py --kiem     # chỉ in trạng thái connector và số dòng kho
    python3 -I infra/dev/scripts/noi-cdc-phan-tich.py --chup-lai # chụp lại ảnh ban đầu (ví dụ kho vừa dựng lại)

Đọc infra/dev/.env (CLICKHOUSE_PASSWORD, ANALYTICS_*_PASSWORD, POSTGRES_USER, cổng). Chỉ dùng thư viện chuẩn, không in
giá trị bí mật.
"""

import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import List

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
GENERATED = REPO / "analytics" / "cdc" / "generated"
PROJECT = "mission-dev"
DEV_DB = "mission"


def nap_env() -> dict:
    env = dict(os.environ)
    f = HERE.parent / ".env"
    if f.exists():
        for dong in f.read_text(encoding="utf-8").splitlines():
            if "=" in dong and not dong.lstrip().startswith("#"):
                k, v = dong.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env


def doc_connector(db: str) -> dict:
    """Khối `config:` của KafkaConnector sinh sẵn: mỗi dòng `    khoá: "giá trị"` (chuỗi JSON hợp lệ)."""
    cfg, trong = {}, False
    for dong in (GENERATED / db / "connector.yaml").read_text(encoding="utf-8").splitlines():
        if dong.strip() == "config:":
            trong = True
            continue
        m = re.match(r'^    ([A-Za-z0-9_.]+): (".*")$', dong) if trong else None
        if m:
            cfg[m.group(1)] = json.loads(m.group(2))
    if not cfg:
        sys.exit(f"Không đọc được cấu hình connector sinh sẵn của {db}")
    return cfg


def gop(*phan: str) -> str:
    return ",".join(p for p in phan if p)


def connector_dev() -> dict:
    core, org, wf = doc_connector("tenant_data"), doc_connector("org"), doc_connector("flowable")
    mission = dict(core)
    mission.update(
        {
            "database.dbname": DEV_DB,
            "topic.prefix": f"cdc.{DEV_DB}",
            "slot.name": f"dbz_cdc_{DEV_DB}",
            "heartbeat.action.query": core["heartbeat.action.query"].replace("dbz_cdc_tenant_data", f"dbz_cdc_{DEV_DB}"),
            "table.include.list": gop(core.get("table.include.list", ""), org.get("table.include.list", "")),
            "column.exclude.list": gop(core.get("column.exclude.list", ""), org.get("column.exclude.list", "")),
            "transforms.pseudo.topic.prefix": f"cdc.{DEV_DB}",
            "transforms.pseudo.fields": gop(core.get("transforms.pseudo.fields", ""), org.get("transforms.pseudo.fields", "")),
        }
    )
    out = {}
    for ten, cfg in ((f"cdc-{DEV_DB}", mission), ("cdc-flowable", wf)):
        cfg = dict(cfg)
        cfg["connector.class"] = "io.debezium.connector.postgresql.PostgresConnector"
        cfg["tasks.max"] = "1"
        # Kafka dev một broker: Connect tự tạo topic cdc.* với một bản sao (production: danh mục topic của INF-10).
        cfg["topic.creation.default.replication.factor"] = "1"
        cfg["topic.creation.default.partitions"] = "1"
        out[ten] = cfg
    return out


def bang(cfg: dict) -> List[str]:
    return [t.replace("\\.", ".") for t in cfg["table.include.list"].split(",") if t]


def psql(env: dict, db: str, sql: str) -> str:
    user = env.get("POSTGRES_USER") or env.get("PG_SUPERUSER") or "mission_admin"
    kq = subprocess.run(
        ["docker", "exec", "-i", f"{PROJECT}-postgres", "psql", "-v", "ON_ERROR_STOP=1", "-q", "-At", "-U", user, "-d", db],
        input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
    )
    if kq.returncode != 0:
        sys.exit(f"psql ({db}) lỗi: {kq.stderr.strip()[:600]}")
    return kq.stdout


def chuan_bi_nguon(env: dict, db: str, tables: List[str]) -> None:
    role = env.get("DEBEZIUM_DB_USER", "cdc_debezium")
    if not re.fullmatch(r"[a-z_][a-z0-9_]*", role):
        sys.exit("DEBEZIUM_DB_USER không hợp lệ")
    co = set(
        psql(env, db, "SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                      " WHERE c.relkind IN ('r', 'p');\n").split()
    )
    thieu = [t for t in tables if t not in co]
    if thieu:
        print(f"  {db}: bỏ qua bảng chưa có ({', '.join(thieu)}); chạy lại sau khi service đã migrate")
    tables = [t for t in tables if t in co]
    schemas = sorted({t.split(".")[0] for t in tables})
    ds = ", ".join(tables)
    mang = "ARRAY[" + ", ".join(f"'{t}'" for t in tables) + "]::regclass[]"
    sql = f"""
CREATE TABLE IF NOT EXISTS public.debezium_heartbeat (slot_name text PRIMARY KEY, beat_at timestamptz NOT NULL);
GRANT CONNECT ON DATABASE {db} TO {role};
GRANT USAGE ON SCHEMA public TO {role};
GRANT SELECT, INSERT, UPDATE ON public.debezium_heartbeat TO {role};
{''.join(f'GRANT USAGE ON SCHEMA {s} TO {role};' + chr(10) for s in schemas)}GRANT SELECT ON {ds} TO {role};
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pub_analytics') THEN
    CREATE PUBLICATION pub_analytics WITH (publish = 'insert, update, delete', publish_via_partition_root = true);
  END IF;
END $$;
ALTER PUBLICATION pub_analytics SET (publish = 'insert, update, delete', publish_via_partition_root = true);
ALTER PUBLICATION pub_analytics SET TABLE {ds};
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT root.oid AS rel FROM unnest({mang}) AS root(oid)
           UNION
           SELECT t.relid::regclass FROM unnest({mang}) AS root(oid), LATERAL pg_partition_tree(root.oid) AS t
  LOOP
    EXECUTE format('ALTER TABLE %s REPLICA IDENTITY FULL', r.rel);
  END LOOP;
END $$;
-- Bảng nghiệp vụ bật RLS chỉ có chính sách cho svc_/ro_, nên ảnh chụp ban đầu (SELECT) của vai trò CDC thấy 0 dòng
-- dù luồng WAL vẫn mang đủ thay đổi. Dev thêm chính sách chỉ đọc cho vai trò CDC trên đúng các bảng nguồn phân tích.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT n.nspname AS s, c.relname AS t FROM unnest({mang}) AS root(oid)
           JOIN pg_class c ON c.oid = root.oid JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE c.relrowsecurity
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = r.s AND tablename = r.t
                   AND policyname = 'rls_' || r.t || '__cdc_phan_tich') THEN
      EXECUTE format('CREATE POLICY %I ON %I.%I FOR SELECT TO {role} USING (true)', 'rls_' || r.t || '__cdc_phan_tich', r.s, r.t);
    END IF;
  END LOOP;
END $$;
"""
    psql(env, db, sql)
    print(f"  {db}: publication pub_analytics với {len(tables)} bảng, REPLICA IDENTITY FULL, quyền của {role}")


def http(method: str, url: str, body=None, timeout: float = 30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:600].decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        return 0, str(e)


def ap_kho(env: dict) -> None:
    e = dict(env)
    e.setdefault("CLICKHOUSE_URL", f"http://127.0.0.1:{env.get('CLICKHOUSE_HTTP_PORT', '35123')}")
    e.setdefault("CLICKHOUSE_USER", "default")
    e.setdefault("ANALYTICS_CELL", "dev")
    e["ANALYTICS_CORE_DB"] = DEV_DB
    e["ANALYTICS_ORG_DB"] = DEV_DB
    e["ANALYTICS_WORKFLOW_DB"] = "flowable"
    kq = subprocess.run(
        [sys.executable, "-I", str(REPO / "analytics" / "clickhouse" / "migrate.py")], env=e, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True
    )
    if kq.returncode != 0:
        loi = (kq.stderr or kq.stdout).strip()[-800:]
        if "tổng kiểm" in loi:
            loi += (
                "\n  Kho đã được áp với tham số khác (thường là topic cdc.tenant_data mặc định). Dữ liệu kho dev dựng lại"
                " được từ CDC: xoá CSDL kho (DROP DATABASE analytics_dev ON CLUSTER cell_dev SYNC, bảng lịch sử migration"
                " nằm trong CSDL đó) rồi chạy lại script."
            )
        sys.exit(f"Áp migration ClickHouse lỗi:\n{loi}")
    dong = [d for d in kq.stdout.strip().splitlines() if d.strip()]
    print("  " + (dong[-1] if dong else "Kho ClickHouse đã ở phiên bản mới nhất"))


def dang_ky(connect: str, ten: str, cfg: dict) -> None:
    st, kq = http("PUT", f"{connect}/connectors/{ten}/config", cfg)
    if st not in (200, 201):
        sys.exit(f"Đăng ký connector {ten} lỗi {st}: {kq}")
    for _ in range(36):
        st, kq = http("GET", f"{connect}/connectors/{ten}/status")
        if st == 200:
            tasks = kq.get("tasks") or []
            if kq["connector"]["state"] == "RUNNING" and tasks and all(t["state"] == "RUNNING" for t in tasks):
                print(f"  {ten}: RUNNING")
                return
            hong = [t for t in tasks if t["state"] == "FAILED"]
            if hong:
                sys.exit(f"Connector {ten} FAILED: {hong[0].get('trace', '')[:800]}")
        time.sleep(5)
    sys.exit(f"Connector {ten} chưa RUNNING sau 3 phút: {kq}")


def chup_lai(connect: str, ten: str) -> None:
    """Xoá offset để connector chụp lại ảnh ban đầu (Kafka Connect 3.6+: dừng, xoá offset, chạy tiếp)."""
    st, kq = http("PUT", f"{connect}/connectors/{ten}/stop")
    if st not in (200, 202, 204):
        sys.exit(f"Dừng connector {ten} lỗi {st}: {kq}")
    for _ in range(24):
        st, kq = http("GET", f"{connect}/connectors/{ten}/status")
        if st == 200 and kq["connector"]["state"] == "STOPPED":
            break
        time.sleep(5)
    st, kq = http("DELETE", f"{connect}/connectors/{ten}/offsets")
    if st not in (200, 202, 204):
        sys.exit(f"Xoá offset của {ten} lỗi {st}: {kq}")
    http("PUT", f"{connect}/connectors/{ten}/resume")
    print(f"  {ten}: đã xoá offset, chụp lại ảnh ban đầu")


def ch(env: dict, sql: str) -> str:
    url = f"http://127.0.0.1:{env.get('CLICKHOUSE_HTTP_PORT', '35123')}/"
    req = urllib.request.Request(url, data=sql.encode("utf-8"), method="POST")
    req.add_header("X-ClickHouse-User", "default")
    req.add_header("X-ClickHouse-Key", env.get("CLICKHOUSE_PASSWORD", ""))
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8").strip()


def kiem(env: dict, connect: str, cho: bool) -> int:
    for ten in connector_dev():
        st, kq = http("GET", f"{connect}/connectors/{ten}/status")
        trang_thai = kq["connector"]["state"] if st == 200 else f"chưa đăng ký ({st})"
        print(f"  {ten}: {trang_thai}")
    db = env.get("ANALYTICS_DB", "analytics_dev")
    if not re.fullmatch(r"[A-Za-z0-9_]+", db):
        sys.exit("ANALYTICS_DB không hợp lệ")
    # dim_org_scope là refreshable materialized view (mỗi phút) dựng từ bảng đơn vị và bao đóng cây đã nạp từ CDC.
    han = time.time() + (240 if cho else 0)
    while True:
        try:
            don_vi = int(ch(env, f"SELECT count() FROM {db}.dim_org_scope"))
        except (urllib.error.URLError, OSError, ValueError) as e:
            print(f"  Không đọc được kho ClickHouse: {e}")
            return 1
        if don_vi > 0 or time.time() >= han:
            break
        time.sleep(10)
    print(f"  Kho {db}: dim_org_scope {don_vi} đơn vị")
    return 0 if don_vi > 0 else 1


def main() -> int:
    env = nap_env()
    connect = f"http://127.0.0.1:{env.get('DEBEZIUM_PORT', '35083')}"
    if "--kiem" in sys.argv[1:]:
        return kiem(env, connect, cho=False)
    print("==> Kho ClickHouse (analytics/clickhouse/migrate.py, topic cdc.mission.* và cdc.flowable.*)")
    ap_kho(env)
    conns = connector_dev()
    print("==> Nguồn PostgreSQL (publication, REPLICA IDENTITY, quyền vai trò CDC)")
    chuan_bi_nguon(env, DEV_DB, bang(conns[f"cdc-{DEV_DB}"]))
    chuan_bi_nguon(env, "flowable", bang(conns["cdc-flowable"]))
    print("==> Connector Debezium")
    st, kq = http("GET", f"{connect}/connector-plugins")
    if st != 200:
        sys.exit(f"Kafka Connect chưa sẵn sàng ở {connect}: {kq}")
    for ten, cfg in conns.items():
        if "--chup-lai" in sys.argv[1:] and http("GET", f"{connect}/connectors/{ten}")[0] == 200:
            chup_lai(connect, ten)
        dang_ky(connect, ten, cfg)
    print("==> Chờ bảng phạm vi theo cây tổ chức có dữ liệu")
    return kiem(env, connect, cho=True)


if __name__ == "__main__":
    sys.exit(main())
