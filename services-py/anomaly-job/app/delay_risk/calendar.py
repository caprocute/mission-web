"""Lịch ngày làm việc của tenant (`getWorkingCalendar`, MOD-08): thứ Bảy, Chủ nhật nghỉ trừ ngày làm bù; ngày thường
làm việc trừ ngày nghỉ lễ, nghỉ bù. Ngữ nghĩa `dem`, `cong` giống bộ ca `data/golden/ngay-lam-viec` (CORE-08-04)."""

from __future__ import annotations

import datetime as dt
import time
from collections.abc import Iterable, Mapping
from typing import Any

import httpx

from app.thresholds import ServiceToken, ThresholdError

OFF = {"NGHI_LE", "NGHI_BU"}


class CalendarError(ThresholdError):
    pass


class WorkingCalendar:
    def __init__(self, days: Mapping[dt.date, str], weekend: Iterable[int] = (6, 7)) -> None:
        self._days = dict(days)
        self._weekend = frozenset(weekend)

    def is_working(self, d: dt.date) -> bool:
        kind = self._days.get(d)
        if kind == "LAM_BU":
            return True
        if kind in OFF:
            return False
        return d.isoweekday() not in self._weekend

    def count(self, start: dt.date, end: dt.date) -> int:
        """Số ngày làm việc trong nửa khoảng (start, end]; bằng 0 khi start >= end."""
        n, d = 0, start
        while d < end:
            d += dt.timedelta(days=1)
            n += self.is_working(d)
        return n

    def add(self, start: dt.date, n: int) -> dt.date:
        """Ngày làm việc thứ n sau start (n âm thì lùi, n = 0 trả nguyên start)."""
        d, step = start, (1 if n > 0 else -1)
        left = abs(n)
        while left:
            d += dt.timedelta(days=step)
            left -= self.is_working(d)
        return d


class CalendarClient:
    """Lấy và đệm lịch theo (tenant, năm) trong `ttl` giây; lịch hai năm được gộp khi khoảng tính qua năm."""

    def __init__(self, http: httpx.Client, base_url: str, token: ServiceToken, ttl: float = 3600) -> None:
        self._http, self._base, self._token, self._ttl = http, base_url, token, ttl
        self._cache: dict[tuple[str, int], tuple[float, dict[dt.date, str], tuple[int, ...]]] = {}

    def _year(self, tenant: str, year: int) -> tuple[dict[dt.date, str], tuple[int, ...]]:
        hit = self._cache.get((tenant, year))
        if hit and hit[0] > time.monotonic():
            return hit[1], hit[2]
        r = self._http.get(f"{self._base}/api/v1/config/calendar", params={"year": year},
                           headers={"Authorization": f"Bearer {self._token.get()}", "X-Tenant-Id": tenant,
                                    "Accept": "application/json"})
        if r.status_code != 200:
            raise CalendarError(f"getWorkingCalendar trả {r.status_code} cho tenant {tenant}")
        body: dict[str, Any] = r.json()
        try:
            days = {dt.date.fromisoformat(x["date"]): str(x["dayKind"]) for x in body["days"]}
            weekend = tuple(int(x) for x in body.get("weekendDays") or (6, 7))
        except (KeyError, TypeError, ValueError) as e:
            raise CalendarError(f"lịch ngày làm việc sai định dạng: {e}") from e
        self._cache[(tenant, year)] = (time.monotonic() + self._ttl, days, weekend)
        return days, weekend

    def get(self, tenant: str, years: Iterable[int]) -> WorkingCalendar:
        days: dict[dt.date, str] = {}
        weekend: tuple[int, ...] = (6, 7)
        for y in sorted(set(years)):
            d, weekend = self._year(tenant, y)
            days.update(d)
        return WorkingCalendar(days, weekend)
