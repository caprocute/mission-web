"""Kiểm thử không cần Docker của dự báo trễ hạn (DV-15-10): lịch ngày làm việc theo bộ ca dùng chung, công thức theo
bộ ca vàng data/golden/delay-risk (mức, n, m đúng 100 %), thân gửi cổng MOD-02."""

from __future__ import annotations

import datetime as dt
import json
from typing import Any

import pytest
import yaml

from app.delay_risk.calendar import WorkingCalendar
from app.delay_risk.model import Forecast, Params, Task, forecast
from app.delay_risk.sink import body, explanation, needed_days
from tests.conftest import REPO

GOLDEN = REPO / "data" / "golden" / "delay-risk"


def golden() -> tuple[dict[str, Any], dict[str, Any]]:
    data = json.loads((GOLDEN / "du-lieu.json").read_text(encoding="utf-8"))
    cases = json.loads((GOLDEN / "ca-kiem-thu.json").read_text(encoding="utf-8"))["ket_qua"]
    return data, cases


def golden_calendar(data: dict[str, Any]) -> WorkingCalendar:
    return WorkingCalendar({dt.date.fromisoformat(d["date"]): d["dayKind"] for d in data["lich"]})


def to_task(t: dict[str, Any]) -> Task:
    return Task(t["ma"], t["trang_thai"], dt.date.fromisoformat(t["bat_dau"]),
                dt.date.fromisoformat(t.get("han_gia_han") or t["han"]), float(t["so_luong"]),
                tuple((dt.datetime.fromisoformat(p["luc"]), float(p["gia_tri"])) for p in t["tien_do"]))


def test_calendar_matches_shared_working_day_cases() -> None:
    suite = yaml.safe_load((REPO / "data" / "golden" / "ngay-lam-viec" / "ca-ngay-lam-viec.yaml").read_text("utf-8"))
    cal = WorkingCalendar({dt.date.fromisoformat(str(x["ngay"])): x["loai"] for x in suite["lich"]})
    for c in suite["ca"]:
        if c["phep"] == "dem":
            got: Any = cal.count(dt.date.fromisoformat(str(c["tu"])), dt.date.fromisoformat(str(c["den"])))
        elif c["phep"] == "cong":
            got = cal.add(dt.date.fromisoformat(str(c["tu"])), c["n"]).isoformat()
        else:
            got = cal.is_working(dt.date.fromisoformat(str(c["ngay"])))
        assert got == c["mong_doi"], c["ma"]


def test_golden_cases_all_match() -> None:
    data, cases = golden()
    cal, today = golden_calendar(data), dt.date.fromisoformat(data["hom_nay"])
    assert len(data["nhiem_vu"]) >= 10
    for t in data["nhiem_vu"]:
        f = forecast(to_task(t), today, cal, Params())
        want = cases[t["ma"]]
        if want is None:
            assert f is None, t["ma"]
        else:
            assert f is not None and (f.level, f.needed_days, f.remaining_days) == (
                want["muc"], want["n"], want["m"]), (t["ma"], f)


@pytest.mark.parametrize(("vua", "cao", "level"), [(0.8, 1.0, "THAP"), (0.5, 1.0, "VUA"), (0.5, 0.7, "CAO")])
def test_thresholds_are_configurable(vua: float, cao: float, level: str) -> None:
    data, _ = golden()
    t02 = next(t for t in data["nhiem_vu"] if t["ma"] == "T02")
    f = forecast(to_task(t02), dt.date(2026, 10, 19), golden_calendar(data), Params(vua_ratio=vua, cao_ratio=cao))
    assert f is not None and f.level == level


def test_sink_body_is_data_only() -> None:
    data, _ = golden()
    t01 = next(t for t in data["nhiem_vu"] if t["ma"] == "T01")
    f = forecast(to_task(t01), dt.date(2026, 10, 19), golden_calendar(data), Params())
    assert f is not None
    at = dt.datetime(2026, 10, 19, 16, 30, tzinfo=dt.timezone(dt.timedelta(hours=7)))
    b = body(f, at, "0190a3c2-7a1e-7c00-8000-000000000001")
    assert b == {"level": "CAO", "neededWorkingDays": 3,
                 "explanation": "Nhiệm vụ có nguy cơ trễ hạn cao: cần khoảng 3 ngày làm việc, còn 2 ngày.",
                 "computedAt": "2026-10-19T09:30:00.000Z", "basedOnProgressId": "0190a3c2-7a1e-7c00-8000-000000000001"}
    assert "basedOnProgressId" not in body(f, at)


def test_sink_explanation_and_zero_pace() -> None:
    def fc(level: str, n: int | None, m: int, remaining: float = 1.0) -> Forecast:
        return Forecast(level, n, m, 0.0 if n is None else 1.0, "SO_LUONG", remaining, None)

    assert needed_days(fc("CAO", None, 4)) == 5
    assert explanation(fc("CAO", None, 4)).endswith("còn 4 ngày.")
    assert explanation(fc("VUA", 4, 5)) == "Nhiệm vụ có nguy cơ trễ hạn: cần khoảng 4 ngày làm việc, còn 5 ngày."
    assert explanation(fc("THAP", 2, 5)).startswith("Tiến độ hiện tại đủ")
    assert explanation(fc("THAP", 0, 5, 0.0)) == "Nhiệm vụ đã đủ khối lượng, không có nguy cơ trễ hạn."
