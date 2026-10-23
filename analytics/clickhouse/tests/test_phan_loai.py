"""Kiểm tra tĩnh DV-15-01 (không cần Docker), chạy trong CI cùng mỗi lần đổi lược đồ:

1. Mọi cột mà bảng Kafka engine `src_*` đọc phải có chú thích phân loại `cls` ở bảng nguồn PostgreSQL (COMMENT ON
   COLUMN của migration trong repo, hoặc từ điển TL-03 cho schema chưa có migration). Thiếu chú thích là lỗi.
2. Không cột C3 nào, không cột `_enc`, `_bidx` nào được đọc. Cột C2 chỉ được đọc khi đã giả danh (COMMENT 'gia_danh').
3. Cột mã người phải được giả danh và chỉ đi vào bảng đích qua mission_cdc_key (băm 32 byte), không ở dạng rõ.
4. Danh sách cho phép của Cube và job bất thường không gồm bảng nội bộ hay cột kỹ thuật (TL-03 bảng tổng hợp xử lý
   theo phân loại: ClickHouse chỉ có C0, C1 và C2 đã giả danh)."""

from __future__ import annotations

import json
import re

import pytest

from tests.conftest import REPO, ROOT

MIG = ROOT / "migrations"
SOURCES = {
    "tenant_data": REPO / "backend" / "mission-core" / "src" / "main" / "resources" / "db" / "migration",
    "org": REPO / "backend" / "services" / "org-service" / "src" / "main" / "resources" / "db" / "migration",
    "flowable": REPO / "backend" / "services" / "workflow-service" / "src" / "main" / "resources" / "db" / "migration",
}
# Cột chứa mã một con người cụ thể (TL-03: person_id, actor_id, created_by và các cột cùng nghĩa).
PERSON_COLUMNS = {"person_id", "actor_id", "created_by", "updated_by", "assigner_id", "acceptor_id", "verified_by",
                  "approved_by", "owner_person_id", "verifier_person_id", "appraiser_id", "decider_id", "locked_by",
                  "decided_by", "approver_id"}
# Thuộc tính định danh trực tiếp: không vào kho phân tích dù phân loại C1 (dim_person chỉ có thuộc tính làm thô).
IDENTIFYING = {("org_replica", "person_v", "full_name"), ("org_replica", "person_v", "cbccvc_code")}
COMMENT_RE = re.compile(
    r"COMMENT ON COLUMN (?:\$\{flyway:defaultSchema\}|(\w+))\.(\w+)\.(\w+) IS '(\{.*?\})';", re.S)
KAFKA_RE = re.compile(r"CREATE TABLE \{\{db\}\}\.(src_\w+) ON CLUSTER '\{\{cluster\}\}'\n\((.*?)\n\)\nENGINE = Kafka"
                      r"\nSETTINGS .*?kafka_topic_list = '\{\{topic_(\w+)\}\}\.(\w+)\.(\w+)'", re.S)
DB_OF_TOPIC = {"core": "tenant_data", "org": "org", "workflow": "flowable"}


def classifications() -> dict[tuple[str, str, str], dict[str, object]]:
    out: dict[tuple[str, str, str], dict[str, object]] = {}
    for base in SOURCES.values():
        for f in base.glob("*/V*.sql"):
            for m in COMMENT_RE.finditer(f.read_text(encoding="utf-8")):
                schema = m.group(1) or f.parent.name
                out[(schema, m.group(2), m.group(3))] = json.loads(m.group(4))
    extra = json.loads((ROOT / "phan-loai-ngoai-repo.json").read_text(encoding="utf-8"))
    for qualified, cols in extra.items():
        if qualified.startswith("_"):
            continue
        schema, table = qualified.split(".")
        for col, cls in cols.items():
            out.setdefault((schema, table, col), {"cls": cls})
    return out


def migrations_from(first: str) -> str:
    """Nội dung các migration từ tệp `first` trở đi, theo thứ tự áp (tệp sau dựng lại bảng thì định nghĩa sau thắng)."""
    return "\n".join(f.read_text(encoding="utf-8") for f in sorted(MIG.glob("V*.sql")) if f.name >= first)


def kafka_tables() -> list[tuple[str, str, str, str, list[tuple[str, bool]]]]:
    out: dict[str, tuple[str, str, str, str, list[tuple[str, bool]]]] = {}
    for m in KAFKA_RE.finditer(migrations_from("V005")):
        cols = []
        for line in m.group(2).strip().splitlines():
            c = re.match(r"\s*`(\w+)` String( COMMENT 'gia_danh')?,?$", line)
            assert c, f"{m.group(1)}: dòng cột không đúng dạng: {line!r}"
            cols.append((c.group(1), bool(c.group(2))))
        out[m.group(1)] = (m.group(1), DB_OF_TOPIC[m.group(3)], m.group(4), m.group(5), cols)
    return list(out.values())


def test_every_source_table_is_parsed() -> None:
    tables = kafka_tables()
    assert len(tables) == 21
    assert {(s, t) for _, _, s, t, _ in tables} >= {("task", "task_progress"), ("org", "subject_group"),
                                                   ("appraisal", "rating_proposal"), ("org_replica", "person_v")}


def check(tables: list[tuple[str, str, str, str, list[tuple[str, bool]]]],
          cls: dict[tuple[str, str, str], dict[str, object]]) -> list[str]:
    errors = []
    for name, _db, schema, table, cols in tables:
        for col, hashed in cols:
            if col.startswith("__"):
                continue  # trường do ExtractNewRecordState thêm
            where = f"{name}.{col} ({schema}.{table})"
            meta = cls.get((schema, table, col))
            if meta is None:
                errors.append(f"{where}: thiếu chú thích phân loại cls ở bảng nguồn")
                continue
            level = meta.get("cls")
            if level == "C3" or col.endswith(("_enc", "_bidx")):
                errors.append(f"{where}: cột {level} hoặc cột mã hoá không được vào kho phân tích")
            elif level == "C2" and not hashed:
                errors.append(f"{where}: cột C2 chưa giả danh")
            elif level not in ("C0", "C1", "C2"):
                errors.append(f"{where}: phân loại không hợp lệ {level!r}")
            if (col in PERSON_COLUMNS or (table == "person_v" and col == "id")) and not hashed:
                errors.append(f"{where}: mã người phải giả danh")
            if (schema, table, col) in IDENTIFYING:
                errors.append(f"{where}: thuộc tính định danh trực tiếp")
    return errors


def test_no_c2_unhashed_or_c3_column_reaches_clickhouse() -> None:
    assert check(kafka_tables(), classifications()) == []


def test_check_rejects_bad_columns() -> None:
    cls = {("x", "t", "a"): {"cls": "C2"}, ("x", "t", "b"): {"cls": "C3"}, ("x", "t", "note_enc"): {"cls": "C1"},
           ("x", "t", "created_by"): {"cls": "C1"}, ("org_replica", "person_v", "full_name"): {"cls": "C1"}}
    tables = [("src_x__t", "tenant_data", "x", "t",
               [("a", False), ("b", True), ("note_enc", False), ("created_by", False), ("missing", False)]),
              ("src_p", "tenant_data", "org_replica", "person_v", [("full_name", False)])]
    errors = check(tables, cls)
    assert len(errors) == 6, errors
    assert any("C2 chưa giả danh" in e for e in errors) and any("thiếu chú thích" in e for e in errors)


def test_hashed_columns_only_reach_targets_as_pseudonymous_keys() -> None:
    mv = migrations_from("V006")
    views: dict[str, str] = {}
    for m in re.finditer(
        r"CREATE MATERIALIZED VIEW \{\{db\}\}\.mv__\w+ .*?\n(SELECT .*?)FROM \{\{db\}\}\.(src_\w+)"
        r" WHERE length\(_error\) = 0",
        mv, re.S):
        views[m.group(2)] = views.get(m.group(2), "") + m.group(1)
    tables = kafka_tables()
    assert set(views) == {t[0] for t in tables}, "mỗi nguồn có view nạp dữ liệu"
    for name, _, _, _, cols in tables:
        body = views[name]
        for col, hashed in cols:
            if not hashed:
                continue
            uses = re.findall(rf"(\w+)\(\s*{col}\b", body)
            bare = re.findall(rf"(?<![\w(]){col}\b(?!\s*\))", body.replace(f"mission_cdc_key({col})", ""))
            assert uses and set(uses) == {"mission_cdc_key"}, (name, col, uses)
            assert not bare, (name, col, "dùng cột giả danh ngoài mission_cdc_key")


@pytest.mark.parametrize("role", ["cube", "anomaly"])
def test_read_grants_exclude_internal_objects(role: str) -> None:
    # V010 cấp cả view cube_* (định nghĩa dòng đã lọc cột), V011 cấp bảng kết quả anomaly_out: không thuộc kiểm tra này.
    sql = (MIG / "V009__phan_quyen_doc.sql").read_text(encoding="utf-8") + migrations_from("V012")
    grants = re.findall(rf"SELECT\((.*?)\)\n  ON \{{\{{db\}}\}}\.(\w+) TO \{{\{{db\}}\}}_{role};", sql)
    assert grants
    for cols, table in grants:
        assert table.startswith(("dim_", "fact_", "agg_")) or table == "data_freshness", table
        assert not {c.strip() for c in cols.split(",")} & {"_version", "_is_deleted", "_source_ts", "_ingested_at"}
    assert not re.search(rf"GRANT .*SELECT ON .* TO \{{\{{db\}}\}}_{role}", sql), "không cấp cả bảng"
    policies = set(re.findall(r"ROW POLICY IF NOT EXISTS \w+ ON \{\{db\}\}\.(\w+) ON CLUSTER '\{\{cluster\}\}'\n"
                              rf"  USING .*? TO \{{\{{db\}}\}}_{role};", sql))
    assert policies == {t for _, t in grants}, "mỗi bảng được đọc có row policy"
    # Quyền ghi duy nhất: Cube dựng pre-aggregation trong CSDL {db}_pa_*.
    writes = re.findall(rf"GRANT ON CLUSTER '\{{\{{cluster\}}\}}' ((?:CREATE|DROP|ALTER|INSERT|TRUNCATE)[^\n]*)\n"
                        rf"  ON (\S+) TO \{{\{{db\}}\}}_{role};", sql)
    assert writes == ([] if role == "anomaly" else [(writes[0][0], "{{db}}_pa_*.*")])
