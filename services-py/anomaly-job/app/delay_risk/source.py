"""Đọc nhiệm vụ và lịch sử tiến độ từ kho ClickHouse của cell (chỉ cột C1 và mã; không đọc ghi chú tiến độ)."""

from __future__ import annotations

import datetime as dt
from collections.abc import Sequence
from dataclasses import dataclass

from app.clickhouse import ClickHouse
from app.delay_risk.model import Task

VN = dt.timezone(dt.timedelta(hours=7))


@dataclass(frozen=True)
class Loaded:
    task: Task
    progress_ids: frozenset[str]


# Bí danh cột trả về không trùng tên cột nguồn: ClickHouse cho bí danh có hiệu lực cả trong WHERE.
def load(ch: ClickHouse, tenant: str, task_ids: Sequence[str]) -> dict[str, Loaded]:
    if not task_ids:
        return {}
    tasks = ch.rows(
        "SELECT toString(id) AS ma, status,"
        " toString(coalesce(start_date, toDate32(assigned_at), toDate32(created_at))) AS bat_dau,"
        " toString(coalesce(extended_due_date, due_date)) AS han, toFloat64(planned_quantity) AS so_luong"
        " FROM fact_task FINAL WHERE tenant_id = {tenant:UUID} AND id IN {ids:Array(UUID)}",
        {"tenant": tenant, "ids": list(task_ids)})
    progress = ch.rows(
        "SELECT toString(task_id) AS ma_nv, toString(id) AS ma, toUnixTimestamp64Milli(reported_at) AS luc,"
        " toFloat64(progress_pct) AS pct, toFloat64(done_quantity) AS qty"
        " FROM fact_task_progress FINAL WHERE tenant_id = {tenant:UUID} AND task_id IN {ids:Array(UUID)}"
        " ORDER BY reported_at, id",
        {"tenant": tenant, "ids": list(task_ids)})
    out: dict[str, Loaded] = {}
    for t in tasks:
        planned = float(t["so_luong"])
        rows = [p for p in progress if p["ma_nv"] == t["ma"]]
        points = []
        for p in rows:
            at = dt.datetime.fromtimestamp(int(p["luc"]) / 1000, VN)
            if planned > 0:
                v = float(p["qty"]) if p["qty"] is not None else float(p["pct"]) * planned
            else:
                v = float(p["pct"])
            points.append((at, v))
        task = Task(t["ma"], t["status"], dt.date.fromisoformat(t["bat_dau"]), dt.date.fromisoformat(t["han"]),
                    planned, tuple(points))
        out[t["ma"]] = Loaded(task, frozenset(p["ma"] for p in rows))
    return out
