"""Kiểm thử tích hợp DV-15-05 (SVC-15, ADR-14): dashboard nhúng qua guest token chỉ thấy số liệu trong phạm vi JWT
Cube mang trong điều kiện RLS `__user`, khớp với Cube REST; khách không vào được SQL Lab hay API dataset; cấu hình
Superset không chứa kết nối ClickHouse hay PostgreSQL nghiệp vụ."""

from __future__ import annotations

import pathlib
import re
from typing import Any

import pytest

from conftest import CATALOG, HERE, Superset, sym

pytestmark = pytest.mark.integration


def cube_jwt(s: Superset, tenant: str, org_path: str) -> str:
    return s.env.token({"tenant": sym(tenant), "org_path": org_path, "sub": "kiem-thu"})


def dashboard(s: Superset, guest: str) -> dict[str, Any]:
    emb = CATALOG["dashboards"][0]["embedded_uuid"]
    status, body = s.call("GET", f"/api/v1/embedded_dashboard/{emb}", headers={"X-GuestToken": guest})
    assert status == 200, body
    return body["result"]


def so_viec(s: Superset, guest: str) -> float:
    """Số liệu biểu đồ 'Số nhiệm vụ' như khung nhúng xin: đúng query của biểu đồ đã lưu, kèm dashboardId."""
    h = {"X-GuestToken": guest}
    dash_id = dashboard(s, guest)["dashboard_id"]
    status, charts = s.call("GET", f"/api/v1/dashboard/{dash_id}/charts", headers=h)
    assert status == 200, charts
    chart = next(c for c in charts["result"] if c["slice_name"] == "Số nhiệm vụ")
    form = {**chart["form_data"], "dashboardId": int(dash_id), "slice_id": chart["id"]}
    ds_id = int(str(form["datasource"]).split("__")[0])
    status, body = s.call("POST", "/api/v1/chart/data", {
        "datasource": {"id": ds_id, "type": "table"},
        "force": True,
        "form_data": form,
        "queries": [{"metrics": [form["metric"]], "filters": [], "extras": {"having": "", "where": ""},
                     "columns": [], "row_limit": 10}],
        "result_format": "json",
        "result_type": "full",
    }, h)
    assert status == 200, body
    return float(body["result"][0]["data"][0][form["metric"]])


def cube_so_viec(s: Superset, tenant: str, org_path: str) -> float:
    status, body = s.env.load({"measures": ["task.so_viec"]}, cube_jwt(s, tenant, org_path))
    assert status == 200, body
    return float(body["data"][0]["task.so_viec"] or 0)


def test_guest_token_scopes_numbers_like_cube_rest(superset: Superset) -> None:
    a = so_viec(superset, superset.guest_token(cube_jwt(superset, "A", "/tw")))
    b = so_viec(superset, superset.guest_token(cube_jwt(superset, "B", "/tw")))
    assert a == cube_so_viec(superset, "A", "/tw") and a > 0
    assert b == cube_so_viec(superset, "B", "/tw")
    assert a != b
    # Nhánh hẹp hơn chỉ thấy phần của nhánh.
    nhanh = so_viec(superset, superset.guest_token(cube_jwt(superset, "A", "/tw/vptw/vu-tong-hop")))
    assert nhanh == cube_so_viec(superset, "A", "/tw/vptw/vu-tong-hop") and nhanh < a


def test_guest_without_valid_cube_context_sees_nothing(superset: Superset) -> None:
    bad = cube_jwt(superset, "A", "/tw")[:-4] + "AAAA"
    guest = superset.guest_token(bad)
    h = {"X-GuestToken": guest}
    dash_id = dashboard(superset, guest)["dashboard_id"]
    status, charts = superset.call("GET", f"/api/v1/dashboard/{dash_id}/charts", headers=h)
    chart = next(c for c in charts["result"] if c["slice_name"] == "Số nhiệm vụ")
    form = {**chart["form_data"], "dashboardId": int(dash_id), "slice_id": chart["id"]}
    status, body = superset.call("POST", "/api/v1/chart/data", {
        "datasource": {"id": int(str(form["datasource"]).split("__")[0]), "type": "table"}, "force": True,
        "form_data": form, "queries": [{"metrics": [form["metric"]], "columns": [], "row_limit": 10}],
        "result_format": "json", "result_type": "full"}, h)
    assert status >= 400


def test_guest_cannot_reach_sql_lab_or_dataset_api(superset: Superset) -> None:
    h = {"X-GuestToken": superset.guest_token(cube_jwt(superset, "A", "/tw"))}
    for method, path, body in [
        ("GET", "/api/v1/dataset/", None),
        ("GET", "/api/v1/database/", None),
        ("GET", "/api/v1/sqllab/", None),
        ("GET", "/api/v1/query/", None),
        ("GET", "/api/v1/saved_query/", None),
        ("GET", "/api/v1/security/roles/", None),
    ]:
        status, res = superset.call(method, path, body, h)
        assert status in (401, 403, 404), (path, status, res)
    # Chạy SQL trực tiếp cũng bị chặn (CSRF hoặc thiếu quyền, tuỳ lớp nào chặn trước).
    assert superset.call("POST", "/api/v1/sqllab/execute/", {"database_id": 1, "sql": "SELECT 1"}, h)[0] in (
        400, 401, 403)
    # Không có token khách: không xem được dashboard nhúng.
    emb = CATALOG["dashboards"][0]["embedded_uuid"]
    assert superset.call("GET", f"/api/v1/embedded_dashboard/{emb}")[0] in (401, 403)


def test_service_account_only_grants_guest_tokens(superset: Superset) -> None:
    bearer = {"Authorization": "Bearer " + superset.access_token()}
    assert superset.call("GET", "/api/v1/dataset/", headers=bearer)[0] in (401, 403)
    assert superset.call("GET", "/api/v1/database/", headers=bearer)[0] in (401, 403)


def test_config_has_no_clickhouse_or_business_postgres() -> None:
    files = [p for p in HERE.rglob("*") if p.is_file() and p.suffix in {".py", ".yaml", ".sh", ".json"}
             and "tests" not in p.parts]
    text = "\n".join(p.read_text(encoding="utf-8") for p in files)
    assert not re.search(r"clickhouse(\+\w+)?://", text, re.I)
    uris = re.findall(r"sqlalchemy_uri:\s*(\S+)", text)
    assert uris and all("${CUBE_SQL_HOST}/cube" in u for u in uris)
    assert not list(pathlib.Path(HERE / "dashboards").rglob("*clickhouse*"))
