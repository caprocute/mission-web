"""Ghi kết quả về `task.task.delay_risk` qua cổng nội bộ của MOD-02, không ghi thẳng schema `task` (TL-03 chương 2).

Operation đề xuất (chưa có trong `tasks.yaml`, chờ người phụ trách hợp đồng duyệt, CORE-02 hiện thực; wiki/14):
`PUT /api/v1/tasks/{taskId}/delay-risk`, client dịch vụ cộng `X-Tenant-Id`, có `Idempotency-Key`. MOD-02 chỉ nhận
khi `basedOnReportedAt` không cũ hơn giá trị đã lưu, ngược lại trả 409; vì vậy phát lại EVT-05 cũ không làm lùi giá trị.
Thân là dữ liệu để giao diện dựng MSG-06-02 ({n}, {m}), không có câu tự do."""

from __future__ import annotations

import datetime as dt
import uuid
from typing import Any

import httpx

from app.delay_risk.model import Forecast
from app.thresholds import ServiceToken

NS = uuid.UUID("3e1d3f4c-6a0b-4f61-9a7e-5d9a1c2b7f10")


class SinkError(RuntimeError):
    pass


def body(f: Forecast, today: dt.date) -> dict[str, Any]:
    return {
        "level": f.level,
        "neededWorkingDays": f.needed_days,
        "remainingWorkingDays": f.remaining_days,
        "pace": f.pace,
        "unit": f.unit,
        "remainingQuantity": f.remaining,
        "asOf": today.isoformat(),
        "basedOnReportedAt": f.based_on.isoformat(timespec="milliseconds") if f.based_on else None,
    }


class DelayRiskSink:
    def __init__(self, http: httpx.Client, base_url: str, token: ServiceToken) -> None:
        self._http, self._base, self._token = http, base_url, token

    def put(self, tenant: str, task_id: str, f: Forecast, today: dt.date) -> str:
        """Trả 'ghi', 'cu' (409: đã có giá trị mới hơn) hoặc 'khong_co' (404); lỗi khác ném SinkError để thử lại."""
        b = body(f, today)
        key = uuid.uuid5(NS, f"{tenant}:{task_id}:{b['basedOnReportedAt']}:{b['asOf']}:{f.level}:{f.needed_days}")
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
