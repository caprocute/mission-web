"""Ghi kết quả về `task.task.delay_risk` qua cổng nội bộ của MOD-02, không ghi thẳng schema `task` (TL-03 chương 2).

Operation `setTaskDelayRisk` của `tasks.yaml` (CORE-02-26): `PUT /api/v1/tasks/{id}/delay-risk`, token client
dịch vụ cộng `X-Tenant-Id`, có `Idempotency-Key`. Thân `TaskDelayRiskInput`: `level`, `neededWorkingDays`,
`explanation` (≤ 1000 ký tự), `computedAt`, `basedOnProgressId` (tuỳ chọn). MOD-02 trả 409
`TASK-409-STALE-DELAY-RISK` khi `computedAt` cũ hơn dự báo đang lưu. Dự báo luôn tính lại từ toàn bộ lịch sử tiến
độ trong kho, nên phát lại EVT-05 cũ cho đúng giá trị hiện hành chứ không làm lùi. `explanation` là câu MSG-06-02
dựng sẵn với {n}, {m} (wiki/14, CORE-02-26 điểm 4)."""

from __future__ import annotations

import datetime as dt
import uuid
from collections.abc import Callable
from typing import Any

import httpx

from app.delay_risk.model import Forecast
from app.thresholds import ServiceToken

NS = uuid.UUID("3e1d3f4c-6a0b-4f61-9a7e-5d9a1c2b7f10")


class SinkError(RuntimeError):
    pass


def needed_days(f: Forecast) -> int:
    """n gửi đi; nhịp bằng 0 thì không tính được n, gửi m + 1 làm cận dưới (suy luận) để vẫn hiện mức Cao."""
    return f.needed_days if f.needed_days is not None else f.remaining_days + 1


def explanation(f: Forecast) -> str:
    """Câu hiển thị theo MSG-06-02 (TL-02a), không có mã kỹ thuật."""
    m = f.remaining_days
    if f.needed_days is None:
        return f"Nhiệm vụ có nguy cơ trễ hạn cao: chưa ghi nhận tiến độ trong thời gian gần đây, còn {m} ngày."
    n = f.needed_days
    if f.level == "CAO":
        return f"Nhiệm vụ có nguy cơ trễ hạn cao: cần khoảng {n} ngày làm việc, còn {m} ngày."
    if f.level == "VUA":
        return f"Nhiệm vụ có nguy cơ trễ hạn: cần khoảng {n} ngày làm việc, còn {m} ngày."
    if f.remaining <= 0:
        return "Nhiệm vụ đã đủ khối lượng, không có nguy cơ trễ hạn."
    return f"Tiến độ hiện tại đủ để hoàn thành đúng hạn: cần khoảng {n} ngày làm việc, còn {m} ngày."


def body(f: Forecast, computed_at: dt.datetime, progress_id: str | None = None) -> dict[str, Any]:
    b: dict[str, Any] = {
        "level": f.level,
        "neededWorkingDays": needed_days(f),
        "explanation": explanation(f),
        "computedAt": computed_at.astimezone(dt.UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
    }
    if progress_id:
        b["basedOnProgressId"] = progress_id
    return b


class DelayRiskSink:
    def __init__(self, http: httpx.Client, base_url: str, token: ServiceToken,
                 now: Callable[[], dt.datetime] | None = None) -> None:
        self._http, self._base, self._token = http, base_url, token
        self._now = now or (lambda: dt.datetime.now(dt.UTC))

    def put(self, tenant: str, task_id: str, f: Forecast, today: dt.date, progress_id: str | None = None) -> str:
        """Trả 'ghi', 'cu' (409: có dự báo mới hơn hoặc nhiệm vụ đã đóng), 'khong_co' (404); lỗi khác ném SinkError."""
        b = body(f, self._now(), progress_id)
        # Khoá theo đúng thân gửi (kể cả computedAt): máy chủ từ chối dùng lại khoá cho thân khác.
        key = uuid.uuid5(
            NS, f"{tenant}:{task_id}:{b['computedAt']}:{progress_id}:{f.level}:{b['neededWorkingDays']}")
        r = self._http.put(f"{self._base}/api/v1/tasks/{task_id}/delay-risk", json=b,
                           headers={"Authorization": f"Bearer {self._token.get()}", "X-Tenant-Id": tenant,
                                    "Idempotency-Key": str(key)})
        if r.status_code in (200, 204):
            return "ghi"
        if r.status_code == 409:
            return "cu"
        if r.status_code == 404:
            return "khong_co"
        raise SinkError(f"cổng MOD-02 trả {r.status_code} cho nhiệm vụ {task_id}")
