"""cdc-config-gen (DV-15-02, SVC-15/ADR-14, TL-03 "CDC phân tích sang ClickHouse").

Sinh cấu hình connector Debezium phân tích cho từng CSDL từ chú thích phân loại `COMMENT ON COLUMN ... '{"cls":..}'`:

- `table.include.list`: các bảng mà kho ClickHouse đọc (lấy từ `kafka_topic_list` của
  analytics/clickhouse/migrations/V005__nguon_cdc_kafka.sql, nên hai phía không lệch nhau);
- `column.exclude.list`: mọi cột C3, cột C2, cột `_enc`, `_bidx`, cột đánh dấu `pii` và thuộc tính định danh trực tiếp
  không có cách giả danh;
- danh sách cột mã người cho bộ biến đổi `TenantPseudonymize` (băm HMAC có muối theo tenant). TL-03 ghi
  `column.mask.hash.SHA-256.with.salt.<muối_tenant>`, nhưng tham số đó chỉ nhận một muối cho cả connector và không áp
  cho cột uuid, nên giả danh theo tenant làm bằng bộ biến đổi riêng (wiki/14, DV-15-02).

Thiếu chú thích `cls` ở bất kỳ cột nào của bảng được đưa vào là lỗi. Đầu ra mỗi CSDL: `generated/<db>/connector.yaml`
(KafkaConnector của Strimzi) và `generated/<db>/publication.sql` (publication `pub_analytics` và REPLICA IDENTITY FULL).

Hai nguồn phân loại:
- mặc định: đọc migration trong repo (COMMENT ON COLUMN, CREATE TABLE, ALTER TABLE ADD COLUMN), cộng
  analytics/clickhouse/phan-loai-ngoai-repo.json cho schema chưa có migration; chỉ dùng thư viện chuẩn để chạy trong CI;
- `--dsn <db>=<url>`: đọc pg_description của CSDL đang chạy (cần psycopg), dùng khi kiểm tra một môi trường.

    python3 analytics/cdc/cdc_config_gen.py            # ghi lại generated/
    python3 analytics/cdc/cdc_config_gen.py --check    # khác tệp đã commit thì thoát mã 1 (bước CI)
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = HERE / "generated"
V005 = REPO / "analytics" / "clickhouse" / "migrations" / "V005__nguon_cdc_kafka.sql"
EXTRA = REPO / "analytics" / "clickhouse" / "phan-loai-ngoai-repo.json"
MIGRATIONS = {
    "tenant_data": [REPO / "backend" / "mission-core" / "src" / "main" / "resources" / "db" / "migration"],
    "org": [REPO / "backend" / "services" / "org-service" / "src" / "main" / "resources" / "db" / "migration"],
}
TOPIC_DB = {"core": "tenant_data", "org": "org"}

# Cột mã một con người cụ thể (TL-03: person_id, actor_id, created_by và các cột cùng nghĩa).
PERSON_COLUMNS = frozenset({
    "person_id", "actor_id", "created_by", "updated_by", "assigner_id", "acceptor_id", "verified_by", "approved_by",
    "owner_person_id", "verifier_person_id", "appraiser_id", "decider_id", "locked_by", "submitted_by", "reviewer_id",
})
# Bảng mà khoá chính là mã người.
PERSON_TABLES = frozenset({("org_replica", "person_v")})
# Thuộc tính định danh trực tiếp phân loại C1 nhưng không vào kho phân tích (dim_person chỉ có thuộc tính làm thô).
IDENTIFYING = frozenset({("org_replica", "person_v", "full_name"), ("org_replica", "person_v", "cbccvc_code")})


class GenError(Exception):
    pass


@dataclass
class Table:
    schema: str
    name: str
    columns: dict[str, dict[str, object]] = field(default_factory=dict)  # tên cột -> chú thích (None nếu thiếu)

    @property
    def qualified(self) -> str:
        return f"{self.schema}.{self.name}"


def wanted_tables(v005: pathlib.Path = V005) -> dict[str, list[tuple[str, str]]]:
    """CSDL -> danh sách (schema, bảng) theo thứ tự xuất hiện trong V005 của kho ClickHouse."""
    out: dict[str, list[tuple[str, str]]] = {}
    for prefix, schema, table in re.findall(r"kafka_topic_list = '\{\{topic_(\w+)\}\}\.(\w+)\.(\w+)'",
                                            v005.read_text(encoding="utf-8")):
        out.setdefault(TOPIC_DB[prefix], []).append((schema, table))
    return out


_CREATE_RE = re.compile(r"CREATE TABLE (?:IF NOT EXISTS )?(?:\$\{flyway:defaultSchema\}|(\w+))\.(\w+)\s*\((.*?)\n\)",
                        re.S)
_ALTER_RE = re.compile(r"ALTER TABLE (?:IF EXISTS )?(?:ONLY )?(?:\$\{flyway:defaultSchema\}|(\w+))\.(\w+)\s+(.*?);",
                       re.S)
_COMMENT_RE = re.compile(r"COMMENT ON COLUMN (?:\$\{flyway:defaultSchema\}|(\w+))\.(\w+)\.(\w+) IS '((?:[^']|'')*)';",
                         re.S)
_COLUMN_RE = re.compile(r"^\s*([a-z_][a-z0-9_]*)\s+[a-z]", re.I)
_NOT_COLUMN = frozenset({"constraint", "primary", "unique", "check", "foreign", "exclude", "like"})


def _migration_files(dirs: Iterable[pathlib.Path]) -> list[pathlib.Path]:
    files = []
    for d in dirs:
        files += [p for p in d.glob("*/V*__*.sql")]
    return sorted(files, key=lambda p: (p.parent.name, int(p.name[1:].split("__")[0])))


def tables_from_migrations(db: str, dirs: Iterable[pathlib.Path] | None = None,
                           extra: pathlib.Path | None = EXTRA) -> dict[tuple[str, str], Table]:
    """Dựng danh sách cột và chú thích từ migration trong repo. Schema của tệp dùng ${flyway:defaultSchema} là tên
    thư mục chứa tệp."""
    tables: dict[tuple[str, str], Table] = {}
    for f in _migration_files(MIGRATIONS[db] if dirs is None else dirs):
        sql = f.read_text(encoding="utf-8")
        default_schema = f.parent.name
        for m in _CREATE_RE.finditer(sql):
            t = tables.setdefault((m.group(1) or default_schema, m.group(2)),
                                  Table(m.group(1) or default_schema, m.group(2)))
            depth = 0
            for line in m.group(3).splitlines():
                c = _COLUMN_RE.match(line) if depth == 0 else None
                if c and c.group(1).lower() not in _NOT_COLUMN:
                    t.columns.setdefault(c.group(1), {})
                depth += line.count("(") - line.count(")")
        for m in _ALTER_RE.finditer(sql):
            key = (m.group(1) or default_schema, m.group(2))
            for col in re.findall(r"ADD COLUMN (?:IF NOT EXISTS )?([a-z_][a-z0-9_]*)", m.group(3), re.I):
                tables.setdefault(key, Table(*key)).columns.setdefault(col, {})
        for m in _COMMENT_RE.finditer(sql):
            key = (m.group(1) or default_schema, m.group(2))
            t = tables.setdefault(key, Table(*key))
            try:
                t.columns[m.group(3)] = json.loads(m.group(4).replace("''", "'"))
            except json.JSONDecodeError:
                t.columns[m.group(3)] = {"_invalid": m.group(4)}
    if extra is not None and extra.exists():
        for qualified, cols in json.loads(extra.read_text(encoding="utf-8")).items():
            if qualified.startswith("_"):
                continue
            schema, name = qualified.split(".")
            if (schema, name) in tables:
                continue  # migration trong repo thắng
            tables[(schema, name)] = Table(schema, name, {c: {"cls": cls} for c, cls in cols.items()})
    return tables


def tables_from_database(dsn: str, wanted: Iterable[tuple[str, str]]) -> dict[tuple[str, str], Table]:
    """Đọc cột và pg_description của CSDL đang chạy. Với bảng phân vùng chỉ đọc bảng cha."""
    import psycopg

    tables: dict[tuple[str, str], Table] = {}
    with psycopg.connect(dsn) as c:
        for schema, name in wanted:
            rows = c.execute(
                """SELECT a.attname, col_description(a.attrelid, a.attnum)
                     FROM pg_attribute a JOIN pg_class r ON r.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = r.relnamespace
                    WHERE n.nspname = %s AND r.relname = %s AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attnum""", (schema, name)).fetchall()
            t = Table(schema, name)
            for col, comment in rows:
                try:
                    t.columns[col] = json.loads(comment) if comment else {}
                except json.JSONDecodeError:
                    t.columns[col] = {"_invalid": comment}
            if rows:
                tables[(schema, name)] = t
    return tables


@dataclass
class Plan:
    db: str
    tables: list[Table]
    excluded: list[str]                 # schema.table.column
    pseudonymized: dict[str, list[str]]  # schema.table -> cột


def plan(db: str, wanted: list[tuple[str, str]], tables: Mapping[tuple[str, str], Table]) -> Plan:
    errors: list[str] = []
    chosen, excluded, pseudo = [], [], {}
    for schema, name in wanted:
        t = tables.get((schema, name))
        if t is None or not t.columns:
            errors.append(f"{db}: không tìm thấy bảng nguồn {schema}.{name}")
            continue
        chosen.append(t)
        hashed = []
        for col, meta in t.columns.items():
            where = f"{schema}.{name}.{col}"
            if "_invalid" in meta:
                errors.append(f"{where}: COMMENT không phải JSON hợp lệ")
                continue
            cls = meta.get("cls")
            if cls is None:
                errors.append(f"{where}: thiếu COMMENT phân loại cls")
                continue
            if cls not in ("C0", "C1", "C2", "C3"):
                errors.append(f"{where}: phân loại không hợp lệ {cls!r}")
                continue
            person = col in PERSON_COLUMNS or ((schema, name) in PERSON_TABLES and col == "id")
            if person:
                if cls == "C3":
                    errors.append(f"{where}: mã người phân loại C3")
                hashed.append(col)
                continue
            if (cls in ("C2", "C3") or col.endswith(("_enc", "_bidx")) or meta.get("pii") is True
                    or (schema, name, col) in IDENTIFYING):
                excluded.append(where)
        if hashed:
            pseudo[t.qualified] = hashed
    if errors:
        raise GenError("\n".join(errors))
    return Plan(db, chosen, excluded, pseudo)


def _regex(qualified: str) -> str:
    return qualified.replace(".", "\\.")


def connector_config(p: Plan) -> dict[str, str]:
    """Cấu hình Debezium của connector phân tích (khoá theo thứ tự cố định để đầu ra ổn định)."""
    db = p.db
    return {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "plugin.name": "pgoutput",
        "database.hostname": "${env:PG_HOST}",
        "database.port": "${env:PG_PORT}",
        "database.user": "${env:PG_CDC_ANALYTICS_USER}",
        "database.password": "${env:PG_CDC_ANALYTICS_PASSWORD}",
        "database.dbname": db,
        "topic.prefix": f"cdc.{db}",
        "slot.name": f"dbz_cdc_{db}",
        "publication.name": "pub_analytics",
        "publication.autocreate.mode": "disabled",
        "snapshot.mode": "initial",
        "table.include.list": ",".join(_regex(t.qualified) for t in p.tables),
        "column.exclude.list": ",".join(_regex(c) for c in p.excluded),
        "decimal.handling.mode": "string",
        "tombstones.on.delete": "false",
        "heartbeat.interval.ms": "10000",
        "heartbeat.action.query": (
            "INSERT INTO public.debezium_heartbeat (slot_name, beat_at) VALUES ('dbz_cdc_" + db
            + "', now()) ON CONFLICT (slot_name) DO UPDATE SET beat_at = EXCLUDED.beat_at"),
        "key.converter": "org.apache.kafka.connect.json.JsonConverter",
        "key.converter.schemas.enable": "false",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "false",
        "transforms": "unwrap,pseudo",
        "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
        "transforms.unwrap.add.fields": "op,lsn,source.ts_ms",
        "transforms.unwrap.delete.tombstone.handling.mode": "rewrite",
        "transforms.pseudo.type": "vn.viettel.okrkpi.analytics.cdc.TenantPseudonymize",
        "transforms.pseudo.topic.prefix": f"cdc.{db}",
        "transforms.pseudo.fields": ",".join(f"{t}:{'|'.join(cols)}" for t, cols in p.pseudonymized.items()),
        "transforms.pseudo.tenant.field": "tenant_id",
        "transforms.pseudo.key": "${env:ANALYTICS_PSEUDONYM_KEY}",
        "producer.override.acks": "all",
        "producer.override.enable.idempotence": "true",
        "producer.override.compression.type": "zstd",
    }


HEADER = "# Sinh bởi analytics/cdc/cdc_config_gen.py (DV-15-02). Không sửa tay; chạy lại công cụ.\n"


def connector_yaml(p: Plan) -> str:
    cfg = connector_config(p)
    lines = [HEADER.rstrip(),
             "# Connector Debezium phân tích của CSDL " + p.db + ": slot riêng, publication pub_analytics, topic",
             "# cdc." + p.db + ".<schema>.<table>. Mật khẩu vai trò CDC và khoá chủ giả danh lấy từ biến môi trường",
             "# do Secret (đồng bộ từ OpenBao) cấp cho Kafka Connect, không nằm trong tệp này.",
             "apiVersion: kafka.strimzi.io/v1",
             "kind: KafkaConnector",
             "metadata:",
             f"  name: cdc-{p.db.replace('_', '-')}",
             "  labels:",
             "    strimzi.io/cluster: outbox",
             "    app.kubernetes.io/part-of: mission-analytics",
             "spec:",
             "  class: io.debezium.connector.postgresql.PostgresConnector",
             "  tasksMax: 1",
             "  config:"]
    for k, v in cfg.items():
        if k == "connector.class" or v == "":
            continue
        lines.append(f"    {k}: {json.dumps(v, ensure_ascii=False)}")
    return "\n".join(lines) + "\n"


def publication_sql(p: Plan) -> str:
    names = ", ".join(t.qualified for t in p.tables)
    regclasses = ", ".join(f"'{t.qualified}'" for t in p.tables)
    return f"""-- Sinh bởi analytics/cdc/cdc_config_gen.py (DV-15-02). Không sửa tay; chạy lại công cụ.
-- Publication pub_analytics của CSDL {p.db} cho connector phân tích (slot dbz_cdc_{p.db}). Chạy bằng vai trò quản trị
-- CSDL, chạy lại được. publish_via_partition_root để thay đổi của phân vùng mang tên bảng cha (topic theo bảng cha).
-- REPLICA IDENTITY FULL để bản ghi xoá mang đủ cột khoá sắp xếp của bảng ClickHouse; đặt cho bảng cha và mọi phân
-- vùng hiện có; phân vùng tạo sau phải chạy lại tệp này (hoặc bảng mẫu pg_partman đặt sẵn).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pub_analytics') THEN
    CREATE PUBLICATION pub_analytics WITH (publish = 'insert, update, delete', publish_via_partition_root = true);
  END IF;
END $$;
ALTER PUBLICATION pub_analytics SET (publish = 'insert, update, delete', publish_via_partition_root = true);
ALTER PUBLICATION pub_analytics SET TABLE {names};
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT root.oid AS rel FROM unnest(ARRAY[{regclasses}]::regclass[]) AS root(oid)
           UNION
           SELECT t.relid::regclass FROM unnest(ARRAY[{regclasses}]::regclass[]) AS root(oid),
                  LATERAL pg_partition_tree(root.oid) AS t
  LOOP
    EXECUTE format('ALTER TABLE %s REPLICA IDENTITY FULL', r.rel);
  END LOOP;
END $$;
"""


def render(plans: Iterable[Plan]) -> dict[pathlib.Path, str]:
    out = {}
    for p in plans:
        out[OUT / p.db / "connector.yaml"] = connector_yaml(p)
        out[OUT / p.db / "publication.sql"] = publication_sql(p)
    return out


def build(dsns: Mapping[str, str] | None = None) -> list[Plan]:
    plans = []
    for db, wanted in wanted_tables().items():
        if dsns and db in dsns:
            tables = tables_from_database(dsns[db], wanted)
        else:
            tables = tables_from_migrations(db)
        plans.append(plan(db, wanted, tables))
    return plans


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Sinh cấu hình connector CDC phân tích từ chú thích phân loại cột")
    ap.add_argument("--check", action="store_true", help="so với generated/ đã commit, khác thì thoát mã 1")
    ap.add_argument("--dsn", action="append", default=[], metavar="DB=URL",
                    help="đọc pg_description của CSDL đang chạy thay cho migration trong repo")
    args = ap.parse_args(argv)
    try:
        files = render(build(dict(d.split("=", 1) for d in args.dsn)))
    except GenError as e:
        print(f"cdc-config-gen: lỗi phân loại cột\n{e}", file=sys.stderr)
        return 2
    if args.check:
        stale = [str(p.relative_to(REPO)) for p, text in files.items()
                 if not p.exists() or p.read_text(encoding="utf-8") != text]
        extra = [str(p.relative_to(REPO)) for p in OUT.glob("*/*") if p not in files]
        if stale or extra:
            print("cdc-config-gen: cấu hình sinh lại khác tệp đã commit: " + ", ".join(stale + extra)
                  + "\nChạy python3 analytics/cdc/cdc_config_gen.py rồi commit.", file=sys.stderr)
            return 1
        return 0
    for p, text in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        print(p.relative_to(REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
