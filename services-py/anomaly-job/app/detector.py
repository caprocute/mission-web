"""Chạy các luật phát hiện cho từng tenant của cell (QT-14 bước 1)."""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass

from app.clickhouse import ClickHouse
from app.delay_risk.calendar import CalendarClient
from app.rules import RULES, RULESET_VERSION
from app.thresholds import ThresholdClient, ThresholdError

log = logging.getLogger(__name__)


@dataclass
class RunResult:
    run_id: str
    new_alerts: dict[str, int]
    failed_tenants: list[str]


def tenants(ch: ClickHouse) -> list[str]:
    """Tenant có hồ sơ đánh giá trong kho của cell."""
    return [r["t"] for r in ch.rows("SELECT DISTINCT toString(tenant_id) AS t FROM fact_appraisal_profile ORDER BY t")]


def count_alerts(ch: ClickHouse, tenant: str, run_id: str) -> dict[str, int]:
    rows = ch.rows("SELECT signal_code, count() AS n FROM anomaly_out FINAL"
                   " WHERE tenant_id = {tenant:UUID} AND run_id = {run:UUID} GROUP BY signal_code",
                   {"tenant": tenant, "run": run_id})
    return {r["signal_code"]: int(r["n"]) for r in rows}


def calendar_params(ch: ClickHouse, calendars: CalendarClient, tenant: str) -> dict[str, list[str]]:
    """Ngày nghỉ và ngày làm bù của tenant cho các năm có kỳ trong kho (tối đa ba năm gần nhất)."""
    years = [int(r["y"]) for r in ch.rows(
        "SELECT DISTINCT toYear(end_date) AS y FROM dim_period FINAL WHERE tenant_id = {tenant:UUID}"
        " ORDER BY y DESC LIMIT 3", {"tenant": tenant})]
    cal = calendars.get(tenant, years)
    return {"off": [d.isoformat() for d in cal.days_of("NGHI_LE", "NGHI_BU")],
            "extra": [d.isoformat() for d in cal.days_of("LAM_BU")]}


def detect(ch: ClickHouse, thresholds: ThresholdClient, only_tenants: list[str] | None = None,
           calendars: CalendarClient | None = None) -> RunResult:
    run_id = str(uuid.uuid4())
    result = RunResult(run_id, {}, [])
    for tenant in only_tenants or tenants(ch):
        try:
            ts = thresholds.get(tenant)
            enabled = [r for r in RULES if r.signal_code in ts.signals and ts.signals[r.signal_code].enabled]
            if any(r.calendar for r in enabled) and calendars is None:
                raise ThresholdError("luật cần lịch ngày làm việc nhưng job không có client lịch")
            cal = calendar_params(ch, calendars, tenant) if calendars and any(r.calendar for r in enabled) else {}
        except ThresholdError as e:
            # Không có ngưỡng hay lịch thì không chạy luật cho tenant đó (fail closed); cảnh báo cũ không bị ảnh hưởng.
            log.error("bỏ qua tenant %s: %s", tenant, e)
            result.failed_tenants.append(tenant)
            continue
        model = f"luat-{RULESET_VERSION};nguong-v{ts.version}"
        for rule in enabled:
            params = {"tenant": tenant, "run_id": run_id, "model": model, **rule.params(ts.signals[rule.signal_code])}
            ch.run(rule.sql(), {**params, **(cal if rule.calendar else {})})
        for code, n in count_alerts(ch, tenant, run_id).items():
            result.new_alerts[code] = result.new_alerts.get(code, 0) + n
    log.info("lượt %s: cảnh báo mới %s, tenant lỗi %d", run_id, result.new_alerts, len(result.failed_tenants))
    return result
