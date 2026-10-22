"""Luật phát hiện: mỗi dấu hiệu một tệp SQL `INSERT ... SELECT` vào `anomaly_out`, chạy theo từng tenant với tham số
lấy từ bộ ngưỡng của tenant. Dấu hiệu 3 đến 8 thêm ở DV-15-08, DV-15-09 theo cùng mẫu."""

from __future__ import annotations

import pathlib
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.thresholds import Signal

RULES_DIR = pathlib.Path(__file__).resolve().parent

# Phiên bản bộ luật; thay đổi khi sửa SQL của bất kỳ luật nào.
RULESET_VERSION = "2026.10.1"


@dataclass(frozen=True)
class Rule:
    signal_code: str
    sql_file: str
    params: Callable[[Signal], dict[str, Any]]

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
]
