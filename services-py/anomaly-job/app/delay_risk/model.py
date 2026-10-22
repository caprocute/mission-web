"""Công thức dự báo (thuần, không vào ra). Nhịp là lượng hoàn thành mỗi ngày làm việc trong cửa sổ gần nhất; n là số
ngày làm việc cần để làm nốt phần còn lại theo nhịp đó; m là số ngày làm việc còn lại tới hạn. Mức theo tỷ lệ n/m.

Ví dụ của TL-02a (SCR-0601): nhịp 3 hồ sơ mỗi ngày, còn 7 hồ sơ nên n = 3, còn m = 2 ngày nên mức Cao."""

from __future__ import annotations

import datetime as dt
import math
from collections.abc import Sequence
from dataclasses import dataclass

from app.delay_risk.calendar import WorkingCalendar

EPS = 1e-9


@dataclass(frozen=True)
class Params:
    """Ngưỡng mặc định là suy luận (wiki/14, DV-15-10); đổi bằng biến môi trường DELAY_RISK_*."""

    window_days: int = 5        # cửa sổ tính nhịp, ngày làm việc
    min_days: int = 1           # số ngày làm việc tối thiểu đã thực hiện để có dự báo
    vua_ratio: float = 0.8      # n/m vượt mức này thì Trung bình
    cao_ratio: float = 1.0      # n/m vượt mức này thì Cao


@dataclass(frozen=True)
class Task:
    task_id: str
    status: str
    start: dt.date               # ngày bắt đầu, hoặc ngày giao
    due: dt.date                 # hạn hiện hành (hạn gia hạn nếu có)
    planned_quantity: float      # 0 thì tính theo phần trăm
    progress: Sequence[tuple[dt.datetime, float]]   # (thời điểm báo cáo, lượng đã hoàn thành), tăng dần theo thời gian


@dataclass(frozen=True)
class Forecast:
    level: str                   # THAP, VUA, CAO
    needed_days: int | None      # n; None khi nhịp bằng 0
    remaining_days: int          # m
    pace: float                  # lượng mỗi ngày làm việc
    unit: str                    # SO_LUONG hoặc PHAN_TRAM
    remaining: float             # lượng còn lại
    based_on: dt.datetime | None  # thời điểm báo cáo mới nhất đã dùng


ACTIVE = {"DA_GIAO", "DANG_THUC_HIEN"}


def _value_at(progress: Sequence[tuple[dt.datetime, float]], day: dt.date) -> float:
    v = 0.0
    for at, x in progress:
        if at.date() <= day:
            v = x
    return v


def forecast(task: Task, today: dt.date, cal: WorkingCalendar, p: Params) -> Forecast | None:
    """None khi nhiệm vụ không còn thực hiện hoặc chưa đủ dữ liệu; khi đó giữ giá trị cũ."""
    if task.status not in ACTIVE:
        return None
    by_qty = task.planned_quantity > 0
    total = task.planned_quantity if by_qty else 1.0
    done = min(_value_at(task.progress, today), total)
    remaining = max(total - done, 0.0)
    m = cal.count(today, task.due)
    based_on = max((at for at, _ in task.progress), default=None)
    unit = "SO_LUONG" if by_qty else "PHAN_TRAM"
    if remaining <= EPS:
        return Forecast("THAP", 0, m, 0.0, unit, 0.0, based_on)
    elapsed = cal.count(task.start, today)
    if elapsed < p.min_days:
        return None
    window_start = max(task.start, cal.add(today, -p.window_days))
    days = cal.count(window_start, today)
    pace = (done - _value_at(task.progress, window_start)) / days if days else 0.0
    n = math.ceil(remaining / pace - EPS) if pace > EPS else None
    if m == 0 or n is None:
        level = "CAO"
    else:
        r = n / m
        level = "CAO" if r > p.cao_ratio else "VUA" if r > p.vua_ratio else "THAP"
    return Forecast(level, n, m, round(pace, 6), unit, round(remaining, 6), based_on)
