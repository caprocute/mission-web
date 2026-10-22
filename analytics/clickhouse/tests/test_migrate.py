"""Kiểm thử không cần Docker của bộ áp migration DV-15-01: thế tham số, tổng kiểm không phụ thuộc mật khẩu, tách câu
lệnh, kiểm tra tham số đầu vào, đủ tham số cho mọi tệp migration."""

from __future__ import annotations

import pytest

import migrate

ENV = {"ANALYTICS_CELL": "hcm", "ANALYTICS_CUBE_PASSWORD": "c" * 20, "ANALYTICS_ANOMALY_PASSWORD": "a" * 20,
       "ANALYTICS_DICT_PASSWORD": "d" * 20}


def test_params_defaults_per_cell() -> None:
    p = migrate.params_from_env(ENV)
    assert (p["db"], p["cluster"], p["topic_core"], p["topic_org"]) == (
        "analytics_hcm", "cell_hcm", "cdc.tenant_data", "cdc.org")


@pytest.mark.parametrize("bad", [{"ANALYTICS_DB": "x; DROP"}, {"ANALYTICS_CUBE_PASSWORD": "ngan"},
                                 {"ANALYTICS_DICT_PASSWORD": "x" * 20 + "'"}, {"ANALYTICS_KAFKA_CONSUMERS": "-1"},
                                 {"ANALYTICS_KAFKA_BROKERS": "k:9092 SETTINGS"}])
def test_params_rejects_unsafe_values(bad: dict[str, str]) -> None:
    with pytest.raises(SystemExit):
        migrate.params_from_env({**ENV, **bad})


def test_render_masks_secrets_in_checksum() -> None:
    sql = "CREATE USER {{db}}_cube IDENTIFIED BY '{{cube_password}}';"
    p1 = migrate.params_from_env(ENV)
    p2 = migrate.params_from_env({**ENV, "ANALYTICS_CUBE_PASSWORD": "z" * 20})
    (s1, c1), (s2, c2) = migrate.render(sql, p1), migrate.render(sql, p2)
    assert "c" * 20 in s1 and "z" * 20 in s2 and c1 == c2
    assert migrate.render(sql.replace("hcm", ""), {**p1, "db": "analytics_hn"})[1] != c1
    with pytest.raises(SystemExit, match="thiếu tham số"):
        migrate.render("SELECT {{khong_co}};", p1)


def test_statements_split_on_line_end_semicolon() -> None:
    sql = "-- chú thích đầu tệp;\nCREATE TABLE a\n(x UInt8) -- cột\nENGINE = Memory;\n\nSELECT 'a;b';\n"
    assert migrate.statements(sql) == ["CREATE TABLE a\n(x UInt8) -- cột\nENGINE = Memory", "SELECT 'a;b'"]
    with pytest.raises(SystemExit):
        migrate.statements("SELECT 1")


def test_every_migration_renders_with_cell_params() -> None:
    p = migrate.params_from_env(ENV)
    files = migrate.discover()
    assert [m.version for m in files] == list(range(1, len(files) + 1))
    for m in files:
        sql, _ = migrate.render(m.path.read_text(encoding="utf-8"), p)
        assert "{{" not in sql
        for stmt in migrate.statements(sql):
            assert "ON CLUSTER 'cell_hcm'" in stmt, (m.path.name, stmt[:80])
