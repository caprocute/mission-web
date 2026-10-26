"""Kiểm tĩnh mô hình Cube (không cần Docker): tên thành viên mà hợp đồng platform.yaml và bảng điều hành SCR-1601
dùng phải là chiều, chỉ số công khai của lớp ngữ nghĩa (BR-16-02), và pre-aggregation chỉ trỏ tới thành viên có thật.
Lệch tên làm analytics-api trả 422 "Chiều không có trong lớp ngữ nghĩa" (HT-39)."""

from __future__ import annotations

import pathlib
import re
from typing import Any

import pytest

yaml = pytest.importorskip("yaml")

MODEL = pathlib.Path(__file__).resolve().parents[1] / "model" / "cubes"
FRONTEND = pathlib.Path(__file__).resolve().parents[3] / "frontend" / "projects"
# Mã frontend gọi queryAnalytics, dựng dashboard: hằng số MEASURE, DIMENSION, các trang bảng điều hành và dữ liệu giả
# lập (giả lập phải dùng đúng tên của lớp ngữ nghĩa thật, nếu không lỗi tên chỉ lộ ra trên dev như HT-39).
FRONTEND_SOURCES = [
    "api-client/src/lib/models/analytics.ts",
    "api-client/src/lib/analytics-api.ts",
    "api-client/src/mock/analytics",
    "fe-dashboard/src",
]
# Chuỗi trong nháy đơn dạng `cube.thanh_vien`; chú thích dùng nháy ngược nên không bị quét.
MEMBER = re.compile(r"'([a-z][a-z0-9_]*\.[a-z][a-z0-9_]*)'")

# SCR-1601 trường 2 đến 6: bốn chỉ số chính và bảng tiến độ theo đơn vị (mã, tên đơn vị) theo ví dụ của
# queryAnalytics và getSemanticModel trong contracts/openapi/modules/platform.yaml.
SCR_1601 = {
    "measures": ["okr.tien_do_o", "task.ty_le_dung_han", "task.so_viec_cham", "appraisal.cho_tham_dinh"],
    "dimensions": ["org_unit.id", "org_unit.name", "period.id"],
}


def cubes() -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for f in sorted(MODEL.glob("*.yml")):
        out.extend(yaml.safe_load(f.read_text(encoding="utf-8"))["cubes"])
    return out


def members(kind: str, public_only: bool) -> set[str]:
    return {
        f"{c['name']}.{m['name']}" for c in cubes() for m in c.get(kind, []) if not public_only or m.get("public", True)
    }


@pytest.mark.parametrize("kind", ["measures", "dimensions"])
def test_scr_1601_members_are_published(kind: str) -> None:
    missing = set(SCR_1601[kind]) - members(kind, public_only=True)
    assert not missing, f"Thiếu {kind} công khai: {sorted(missing)}"


def test_pre_aggregations_reference_existing_members() -> None:
    dims = members("dimensions", public_only=False)
    measures = members("measures", public_only=False)
    bad: list[str] = []
    for c in cubes():
        for p in c.get("pre_aggregations", []):
            for d in p.get("dimensions", []):
                if (d if "." in d else f"{c['name']}.{d}") not in dims:
                    bad.append(f"{c['name']}.{p['name']}: {d}")
            for m in p.get("measures", []):
                if (m if "." in m else f"{c['name']}.{m}") not in measures:
                    bad.append(f"{c['name']}.{p['name']}: {m}")
    assert not bad, bad


def frontend_members() -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for rel in FRONTEND_SOURCES:
        root = FRONTEND / rel
        files = [root] if root.is_file() else sorted(root.rglob("*.ts"))
        for f in files:
            if f.name.endswith(".spec.ts"):
                continue
            for m in MEMBER.findall(f.read_text(encoding="utf-8")):
                found.setdefault(m, set()).add(str(f.relative_to(FRONTEND)))
    return found


def test_frontend_uses_only_published_members() -> None:
    found = frontend_members()
    assert "org_unit.name" in found, "Không quét được mã frontend"
    published = members("measures", public_only=True) | members("dimensions", public_only=True)
    unknown = {m: sorted(f) for m, f in found.items() if m not in published}
    assert not unknown, f"Frontend dùng tên không có trong lớp ngữ nghĩa Cube: {unknown}"
