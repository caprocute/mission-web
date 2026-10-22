"""Kiểm thử tích hợp DV-15-03 (QT-16, BR-16-01, BR-16-02, BR-16-08): chỉ số chuẩn trả đúng giá trị tính tay trong
bộ ca vàng, phạm vi theo org_path và tenant, từ chối khi thiếu ngữ cảnh, dùng pre-aggregation, p95 ở tải thử cục bộ."""

from __future__ import annotations

import json
import statistics
import time
from typing import Any

import pytest
from conftest import GOLDEN, Env, sym

CASES = json.loads((GOLDEN / "ca-kiem-thu.json").read_text(encoding="utf-8"))["cases"]
DASHBOARD = [["task.ty_le_dung_han", "task.so_viec_cham"], ["okr.tien_do_o"], ["appraisal.cho_tham_dinh"]]


def ctx(env: Env, tenant: str, org_path: str) -> str:
    return env.token({"tenant": sym(tenant.lstrip("#")), "org_path": org_path, "sub": "kiem-thu"})


def norm(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    def v(x: Any) -> Any:
        if x is None:
            return 0.0
        try:
            return round(float(x), 2)
        except (TypeError, ValueError):
            return x

    return sorted(({k: v(x) for k, x in r.items()} for r in rows), key=lambda r: json.dumps(r, sort_keys=True))


def run(env: Env, token: str, query: dict[str, Any], split: bool) -> list[dict[str, Any]]:
    """Trả dòng kết quả. Với 'tach_truy_van', mỗi cube một truy vấn rồi ghép thành một dòng."""
    groups: dict[str, list[str]] = {}
    for m in query["measures"]:
        groups.setdefault(m.split(".")[0] if split else "", []).append(m)
    merged: dict[str, Any] = {}
    rows: list[dict[str, Any]] = []
    for measures in groups.values():
        status, body = env.load({**query, "measures": measures}, token)
        assert status == 200, body
        if split:
            merged.update(body["data"][0] if body["data"] else dict.fromkeys(measures))
        else:
            rows = body["data"]
    return [merged] if split else rows


@pytest.mark.integration
@pytest.mark.parametrize("case", CASES, ids=[c["ten"] for c in CASES])
def test_golden_measures(env: Env, case: dict[str, Any]) -> None:
    rows = run(env, ctx(env, case["tenant"], case["org_path"]), case["query"], case.get("tach_truy_van", False))
    assert norm(rows) == norm(case["ket_qua"]), case["tinh_tay"]


@pytest.mark.integration
def test_rejects_missing_or_invalid_security_context(env: Env) -> None:
    q = {"measures": ["task.so_viec"]}
    assert env.load(q, None)[0] == 403
    assert env.load(q, "khong-phai-jwt")[0] == 403
    assert env.load(q, env.token({"org_path": "/tw"}))[0] == 403                    # thiếu tenant
    assert env.load(q, env.token({"tenant": sym("A")}))[0] == 403                    # thiếu org_path
    assert env.load(q, env.token({"tenant": sym("A"), "org_path": "tw' OR 1=1"}))[0] == 403
    bad = env.token({"tenant": sym("A"), "org_path": "/tw"})[:-4] + "AAAA"          # sai chữ ký
    assert env.load(q, bad)[0] == 403
    # Không truy vấn được cột ẩn (tenant_id) hay cube không công bố (BR-16-02).
    ok = ctx(env, "#A", "/tw")
    assert env.load({"dimensions": ["task.tenant_id"], "measures": ["task.so_viec"]}, ok)[0] == 403
    assert env.load({"measures": ["task.so_viec"], "filters": [
        {"member": "task.tenant_id", "operator": "equals", "values": [sym("B")]}]}, ok)[0] == 403
    assert env.load({"measures": ["khong_co.so"]}, ok)[0] == 403


@pytest.mark.integration
def test_branch_scope_hides_units_outside(env: Env) -> None:
    q = {"measures": ["task.so_viec"], "dimensions": ["org_unit.ma"]}
    status, body = env.load(q, ctx(env, "#A", "/tw/vptw"))
    assert status == 200
    assert {r["org_unit.ma"] for r in body["data"]} == {"VPTW", "VU-TONG-HOP"}
    status, body = env.load({"dimensions": ["org_unit.ma"]}, ctx(env, "#A", "/tw/vptw/vu-tong-hop"))
    assert status == 200 and [r["org_unit.ma"] for r in body["data"]] == ["VU-TONG-HOP"]
    # Người ở nhánh khác lọc theo đơn vị ngoài nhánh vẫn không thấy gì.
    status, body = env.load({"measures": ["task.so_viec"], "filters": [
        {"member": "org_unit.ma", "operator": "equals", "values": ["VU-TONG-HOP"]}]}, ctx(env, "#A", "/tw/btc"))
    assert status == 200 and norm(body["data"]) == [{"task.so_viec": 0.0}]


@pytest.mark.integration
def test_freshness_and_semantic_version(env: Env) -> None:
    status, body = env.load({"measures": ["data_freshness.cap_nhat_luc", "data_freshness.do_tre_giay"]},
                            ctx(env, "#A", "/tw/vptw"))
    assert status == 200 and body["data"][0]["data_freshness.cap_nhat_luc"] is not None
    assert float(body["data"][0]["data_freshness.do_tre_giay"]) < 3600
    import urllib.request

    req = urllib.request.Request(env.cube_url + "/cubejs-api/v1/meta",
                                 headers={"Authorization": ctx(env, "#A", "/tw")})
    with urllib.request.urlopen(req, timeout=30) as r:
        meta = json.loads(r.read())
    cubes = {c["name"]: c for c in meta["cubes"]}
    expected = {"task", "okr", "appraisal", "score", "rating", "evidence", "okr_checkin", "org_unit", "period"}
    assert expected <= set(cubes)
    assert all(c.get("meta", {}).get("phien_ban") == "2026.4" for c in cubes.values())
    names = {m["name"] for c in cubes.values() for m in c["measures"]}
    assert {"okr.tien_do_o", "task.ty_le_dung_han", "task.so_viec_cham", "appraisal.cho_tham_dinh"} <= names
    assert "task.tenant_id" not in {d["name"] for c in cubes.values() for d in c["dimensions"]}


@pytest.mark.integration
def test_dashboard_uses_pre_aggregations_and_p95(env: Env) -> None:
    paths = ["/tw", "/tw/vptw", "/tw/btc", "/tw/vptw/vu-tong-hop"]
    for p in paths:
        for measures in DASHBOARD:
            assert env.load({"measures": measures}, ctx(env, "#A", p))[0] == 200
    # Mọi truy vấn đọc của Cube vào kho trong lượt trên phải đọc bảng pre-aggregation (<db>_pa_<tenant>), không đọc view
    # nguồn; kiểm bằng nhật ký truy vấn của ClickHouse.
    env.ch("SYSTEM FLUSH LOGS")
    reads = [r.split("\t") for r in env.ch(
        f"SELECT query, toString(has(databases, '{env.db}')) FROM system.query_log WHERE user = '{env.db}_cube' "
        f"AND type = 'QueryFinish' AND query_kind = 'Select' AND event_time >= now() - 120 "
        f"AND query NOT LIKE '%system.%' AND query NOT LIKE 'SELECT 1%' FORMAT TSV").splitlines()]
    from_pa = [q for q, _ in reads if f"{env.db}_pa_" in q]
    for cube in ("task", "okr", "appraisal"):
        assert any(f"{cube}_theo_don_vi_ky" in q for q in from_pa), cube
    lat = []
    for i in range(200):
        token = ctx(env, "#A", paths[i % len(paths)])
        t0 = time.perf_counter()
        status, _ = env.load({"measures": DASHBOARD[i % len(DASHBOARD)]}, token)
        lat.append(time.perf_counter() - t0)
        assert status == 200
    p95 = statistics.quantiles(lat, n=20)[18]
    print(f"\nDV-15-03 truy vấn bảng điều hành: n={len(lat)}, p50={statistics.median(lat) * 1000:.0f} ms, "
          f"p95={p95 * 1000:.0f} ms, max={max(lat) * 1000:.0f} ms")
    assert p95 <= 1.0
    # Không trúng đệm kết quả: mỗi truy vấn một bộ lọc khác (vẫn khớp pre-aggregation), Cube phải hỏi ClickHouse.
    cold = []
    for i in range(100):
        q = {"measures": DASHBOARD[i % len(DASHBOARD)],
             "filters": [{"member": "org_unit.ma", "operator": "notEquals", "values": [f"KHONG-CO-{i}"]}]}
        token = ctx(env, "#A", paths[i % len(paths)])
        t0 = time.perf_counter()
        status, _ = env.load(q, token)
        cold.append(time.perf_counter() - t0)
        assert status == 200
    p95c = statistics.quantiles(cold, n=20)[18]
    print(f"DV-15-03 không dùng đệm kết quả: n={len(cold)}, p50={statistics.median(cold) * 1000:.0f} ms, "
          f"p95={p95c * 1000:.0f} ms, max={max(cold) * 1000:.0f} ms")
    assert p95c <= 1.0
