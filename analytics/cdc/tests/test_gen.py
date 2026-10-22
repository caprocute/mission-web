"""Kiểm thử không cần Docker của cdc-config-gen (DV-15-02): cấu hình đã commit trùng với bản sinh lại, cột thiếu
COMMENT làm công cụ thất bại, cột C2/C3/_enc bị loại, mã người được giả danh, và hai phía CDC, ClickHouse khớp nhau."""

from __future__ import annotations

import pathlib
import re

import pytest

import cdc_config_gen as gen

CH_MIG = gen.REPO / "analytics" / "clickhouse" / "migrations"


def test_committed_config_matches_regenerated() -> None:
    assert gen.main(["--check"]) == 0


def test_each_database_has_one_connector_with_own_slot() -> None:
    plans = gen.build()
    assert sorted(p.db for p in plans) == ["org", "tenant_data"]
    slots = [gen.connector_config(p)["slot.name"] for p in plans]
    assert sorted(slots) == ["dbz_cdc_org", "dbz_cdc_tenant_data"]
    for p in plans:
        cfg = gen.connector_config(p)
        assert cfg["publication.name"] == "pub_analytics" and cfg["topic.prefix"] == f"cdc.{p.db}"
        assert cfg["transforms.unwrap.add.fields"] == "op,lsn,source.ts_ms"
        assert "password" not in "".join(v for k, v in cfg.items() if k != "database.password").lower()
        assert cfg["database.password"].startswith("${env:") and cfg["transforms.pseudo.key"].startswith("${env:")


def _write(tmp: pathlib.Path, sql: str) -> pathlib.Path:
    d = tmp / "mig" / "task"
    d.mkdir(parents=True)
    (d / "V1__x.sql").write_text(sql, encoding="utf-8")
    return tmp / "mig"


SQL = """CREATE TABLE ${flyway:defaultSchema}.task_progress (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_by uuid NOT NULL,
    note_enc bytea,
    secret varchar(10),
    hidden varchar(10),
    progress_pct numeric(6,4) NOT NULL,
    CONSTRAINT pk_task_progress PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_x CHECK (progress_pct BETWEEN 0 AND 1
        AND progress_pct >= 0)
);
ALTER TABLE ${flyway:defaultSchema}.task_progress ADD COLUMN extra_c1 int;
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.created_by IS '{"cls":"C1","desc":"Người báo cáo, ''mã''"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.note_enc IS '{"cls":"C2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.secret IS '{"cls":"C2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.hidden IS '{"cls":"C3"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.progress_pct IS '{"cls":"C1"}';
"""


def test_plan_excludes_sensitive_and_hashes_person_columns(tmp_path: pathlib.Path) -> None:
    sql = SQL + "COMMENT ON COLUMN ${flyway:defaultSchema}.task_progress.extra_c1 IS '{\"cls\":\"C0\"}';\n"
    tables = gen.tables_from_migrations("tenant_data", [_write(tmp_path, sql)], extra=None)
    assert list(tables[("task", "task_progress")].columns) == [
        "id", "tenant_id", "created_by", "note_enc", "secret", "hidden", "progress_pct", "extra_c1"]
    p = gen.plan("tenant_data", [("task", "task_progress")], tables)
    assert p.excluded == ["task.task_progress.note_enc", "task.task_progress.secret", "task.task_progress.hidden"]
    assert p.pseudonymized == {"task.task_progress": ["created_by"]}
    cfg = gen.connector_config(p)
    assert cfg["column.exclude.list"] == (
        "task\\.task_progress\\.note_enc,task\\.task_progress\\.secret,task\\.task_progress\\.hidden")


def test_missing_comment_fails(tmp_path: pathlib.Path) -> None:
    tables = gen.tables_from_migrations("tenant_data", [_write(tmp_path, SQL)], extra=None)
    with pytest.raises(gen.GenError, match=r"task\.task_progress\.extra_c1: thiếu COMMENT phân loại cls"):
        gen.plan("tenant_data", [("task", "task_progress")], tables)
    with pytest.raises(gen.GenError, match=r"không tìm thấy bảng nguồn task\.khong_co"):
        gen.plan("tenant_data", [("task", "khong_co")], tables)


def test_main_reports_classification_error(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch,
                                           capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setitem(gen.MIGRATIONS, "tenant_data", [_write(tmp_path, SQL)])
    assert gen.main(["--check"]) == 2
    assert "thiếu COMMENT phân loại cls" in capsys.readouterr().err


def test_check_detects_stale_file(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import shutil

    out = tmp_path / "generated"
    shutil.copytree(gen.OUT, out)
    monkeypatch.setattr(gen, "OUT", out)
    monkeypatch.setattr(gen, "REPO", tmp_path)
    assert gen.main(["--check"]) == 0
    f = out / "org" / "connector.yaml"
    f.write_text(f.read_text(encoding="utf-8") + "# sửa tay\n", encoding="utf-8")
    assert gen.main(["--check"]) == 1


def test_clickhouse_side_agrees_with_connector() -> None:
    """Mỗi cột ClickHouse đọc phải còn trong bản ghi CDC (không bị loại), và cột ClickHouse đánh dấu gia_danh phải
    đúng là cột bộ biến đổi băm; ngược lại cột bị băm mà ClickHouse đọc phải được ClickHouse coi là mã giả danh."""
    # Bảng nguồn từ V005 trở đi; tệp sau dựng lại bảng thì định nghĩa sau thắng.
    sql = "\n".join(f.read_text(encoding="utf-8") for f in sorted(CH_MIG.glob("V*.sql")) if f.name >= "V005")
    plans = {p.db: p for p in gen.build()}
    latest = {(prefix, schema, table): body for body, prefix, schema, table in re.findall(
        r"\n\((.*?)\n\)\nENGINE = Kafka\nSETTINGS .*?kafka_topic_list = '\{\{topic_(\w+)\}\}\.(\w+)\.(\w+)'",
        sql, re.S)}
    for (prefix, schema, table), body in latest.items():
        p = plans[gen.TOPIC_DB[prefix]]
        cols = dict(re.findall(r"`(\w+)` String( COMMENT 'gia_danh')?", body))
        excluded = {c.rsplit(".", 1)[1] for c in p.excluded if c.startswith(f"{schema}.{table}.")}
        hashed = set(p.pseudonymized.get(f"{schema}.{table}", []))
        read = {c for c in cols if not c.startswith("__")}
        assert not read & excluded, (schema, table, read & excluded)
        marked = {c for c, m in cols.items() if m}
        assert marked == read & hashed, (schema, table, marked, read & hashed)
