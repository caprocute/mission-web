"""Chạy các luật phát hiện cho từng tenant của cell (QT-14 bước 1) và đánh dấu tự đóng (bước 10).

Luật ghi mọi dòng phát hiện của lượt vào `anomaly_run`. Sau khi mọi luật đang bật của tenant chạy xong, bộ chạy chép
dòng có `alert_key` chưa từng có sang `anomaly_out` (cảnh báo mới), rồi đặt `resolved_at` cho cảnh báo đang mở của các
dấu hiệu vừa chạy mà lượt này không còn phát hiện. Dấu hiệu bị tắt, tenant lỗi ngưỡng hay lịch không bị đánh dấu, vì
"không chạy" không có nghĩa là "hết dấu hiệu". Cảnh báo đã tự đóng mà sau đó lại xuất hiện không mở lại (cùng alertKey
đã có), đúng quy tắc chống trùng của MOD-07 (suy luận, wiki/14)."""

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
    resolved: int = 0


def tenants(ch: ClickHouse) -> list[str]:
    """Tenant có hồ sơ đánh giá trong kho của cell."""
    return [r["t"] for r in ch.rows("SELECT DISTINCT toString(tenant_id) AS t FROM fact_appraisal_profile ORDER BY t")]


def count_alerts(ch: ClickHouse, tenant: str, run_id: str) -> dict[str, int]:
    rows = ch.rows("SELECT signal_code, count() AS n FROM anomaly_out FINAL"
                   " WHERE tenant_id = {tenant:UUID} AND run_id = {run:UUID} GROUP BY signal_code",
                   {"tenant": tenant, "run": run_id})
    return {r["signal_code"]: int(r["n"]) for r in rows}


COLUMNS = ("tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id, evidence_chain,"
           " model_version, detected_at, run_id, published_at")


# Chỉ ghép tên cột cố định vào câu lệnh; mọi giá trị đi qua tham số {x:Type} của ClickHouse.
def copy_new(ch: ClickHouse, tenant: str, run_id: str) -> None:
    ch.run(f"INSERT INTO anomaly_out ({COLUMNS}, _version) SELECT {COLUMNS}, 1 FROM anomaly_run"  # noqa: S608
           " WHERE tenant_id = {tenant:UUID} AND run_id = {run:UUID}"
           " AND alert_key NOT IN (SELECT alert_key FROM anomaly_out WHERE tenant_id = {tenant:UUID})",
           {"tenant": tenant, "run": run_id})


def resolve_gone(ch: ClickHouse, tenant: str, run_id: str, signals: list[str]) -> int:
    """Đặt resolved_at cho cảnh báo đang mở của `signals` không có trong lượt `run_id`; trả số cảnh báo vừa đóng."""
    if not signals:
        return 0
    where = ("tenant_id = {tenant:UUID} AND resolved_at IS NULL AND signal_code IN {signals:Array(String)}"
             " AND alert_key NOT IN (SELECT alert_key FROM anomaly_run WHERE tenant_id = {tenant:UUID}"
             " AND run_id = {run:UUID})")
    params = {"tenant": tenant, "run": run_id, "signals": signals}
    n = int(ch.rows(f"SELECT count() AS n FROM anomaly_out FINAL WHERE {where}", params)[0]["n"])  # noqa: S608
    if n:
        sql = (f"INSERT INTO anomaly_out ({COLUMNS}, resolved_at, resolution_published_at, _version)"  # noqa: S608
               f" SELECT {COLUMNS}, now64(3), NULL, _version + 1 FROM anomaly_out FINAL WHERE {where}")
        ch.run(sql, params)
    return n


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
        copy_new(ch, tenant, run_id)
        result.resolved += resolve_gone(ch, tenant, run_id, [r.signal_code for r in enabled])
        for code, n in count_alerts(ch, tenant, run_id).items():
            result.new_alerts[code] = result.new_alerts.get(code, 0) + n
    log.info("lượt %s: cảnh báo mới %s, hết dấu hiệu %d, tenant lỗi %d", run_id, result.new_alerts, result.resolved,
             len(result.failed_tenants))
    return result
