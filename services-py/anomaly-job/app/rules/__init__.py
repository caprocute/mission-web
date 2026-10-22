"""Luật phát hiện: mỗi dấu hiệu một tệp SQL `INSERT ... SELECT` vào `anomaly_run`, chạy theo từng tenant với tham số
lấy từ bộ ngưỡng của tenant. Dấu hiệu 1, 2 (DV-15-07); 3, 5, 7 (DV-15-08); 6, 8 (DV-15-09). Luật ghi vào `anomaly_run`
mọi dòng phát hiện được trong lượt chạy (kể cả cảnh báo đã có); bộ chạy chép dòng mới sang `anomaly_out` và đánh dấu
tự đóng cảnh báo đang mở không còn được phát hiện. Luật cần lịch ngày làm việc nhận thêm tham số `off` (ngày nghỉ
lễ, nghỉ bù) và `extra` (ngày làm bù) của tenant."""

from __future__ import annotations

import pathlib
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.thresholds import Signal

RULES_DIR = pathlib.Path(__file__).resolve().parent

# Phiên bản bộ luật; thay đổi khi sửa SQL của bất kỳ luật nào.
RULESET_VERSION = "2026.10.3"


@dataclass(frozen=True)
class Rule:
    signal_code: str
    sql_file: str
    params: Callable[[Signal], dict[str, Any]]
    calendar: bool = False

    def sql(self) -> str:
        return (RULES_DIR / self.sql_file).read_text(encoding="utf-8")


RULES: list[Rule] = [
    Rule("TU_CHAM_CAO", "tu_cham_cao.sql", lambda s: {
        "consecutive": int(s.param("consecutiveQuarters")),
        "gap": s.param("scoreGap"),
        "score_quarters": int(s.param("scoreQuarters")),
    }),
    Rule("CAO_BANG", "cao_bang.sql", lambda s: {
        "same_pct": s.param("sameLevelPercent"),
        "excellent_pct": s.param("excellentPercent"),
        "min_std": s.param("minStdDev"),
        "min_people": int(s.param("minPeople")),
    }),
    Rule("MUC_TIEU_DE", "muc_tieu_de.sql", lambda s: {
        "early_weeks": int(s.param("earlyWeeks")),
        "below_pct": s.param("belowPreviousPercent"),
    }),
    Rule("CAP_NHAT_DON", "cap_nhat_don.sql", lambda s: {
        "last_pct": s.param("lastDaysPercent"),
        "last_days": int(s.param("lastDays")),
        "min_updates": int(s.param("minUpdates")),
        "week_days": int(s.param("weekLastDays")),
    }, calendar=True),
    Rule("CHE_NHO", "che_nho.sql", lambda s: {
        "inc_pct": s.param("taskIncreasePercent"),
        "tol_pct": s.param("volumeTolerancePercent"),
        "min_tasks": int(s.param("minTasks")),
        "same_due": int(s.param("sameDueTasks")),
    }),
    Rule("SUA_SAU_KHOA", "sua_sau_khoa.sql", lambda s: {}),
    Rule("MIEN_TRU_LAP", "mien_tru_lap.sql", lambda s: {
        "exemptions": int(s.param("exemptionsIn2Quarters")),
        "window": int(s.param("windowQuarters")),
        "rate_gap": s.param("rateGapPercent"),
        "min_decisions": int(s.param("minDecisions")),
    }),
]
