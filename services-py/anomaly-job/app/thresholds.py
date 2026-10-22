"""Đọc ngưỡng phát hiện hiện hành của tenant qua `getAnomalyThresholds` (MOD-08, `GET /api/v1/config/anomaly-
thresholds`) bằng token client-credentials của job; tenant đích đi ở tiêu đề `X-Tenant-Id` (cơ chế ủy quyền của
TenantContextFilter, client job phải nằm trong `okrkpi.tenant.delegating-clients`)."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

import httpx

# Tham số mặc định khi bộ ngưỡng của tenant không ghi tham số đó. Giá trị có trong TL-02b QT-14 lấy nguyên; giá trị
# đánh dấu (suy luận) là tham số TL-02b nhắc tới mà chưa cho số, ghi ở wiki/14.
DEFAULT_PARAMS: dict[str, dict[str, float]] = {
    # scoreQuarters: TL-02b "2 quý".
    "TU_CHAM_CAO": {"consecutiveQuarters": 3, "scoreGap": 5, "scoreQuarters": 2},
    # excellentPercent, minStdDev, minPeople (suy luận).
    "CAO_BANG": {"sameLevelPercent": 90, "excellentPercent": 20, "minStdDev": 2, "minPeople": 5},
    "MUC_TIEU_DE": {"earlyWeeks": 2, "belowPreviousPercent": 20},
    # minUpdates, weekLastDays (suy luận).
    "CAP_NHAT_DON": {"lastDaysPercent": 60, "lastDays": 3, "minUpdates": 10, "weekLastDays": 1},
    # volumeTolerancePercent, minTasks, sameDueTasks (suy luận).
    "CHE_NHO": {"taskIncreasePercent": 50, "volumeTolerancePercent": 10, "minTasks": 4, "sameDueTasks": 3},
}


class ThresholdError(RuntimeError):
    pass


@dataclass(frozen=True)
class Signal:
    code: str
    enabled: bool
    params: dict[str, float] = field(default_factory=dict)

    def param(self, name: str) -> float:
        if name in self.params:
            return float(self.params[name])
        return float(DEFAULT_PARAMS[self.code][name])


@dataclass(frozen=True)
class ThresholdSet:
    version: int
    signals: dict[str, Signal]

    @staticmethod
    def parse(body: dict[str, Any]) -> ThresholdSet:
        try:
            version = int(body["version"])
            signals = {}
            for s in body["signals"]:
                params = {str(k): float(v) for k, v in (s.get("params") or {}).items()}
                signals[str(s["signalCode"])] = Signal(str(s["signalCode"]), bool(s["enabled"]), params)
        except (KeyError, TypeError, ValueError) as e:
            raise ThresholdError(f"bộ ngưỡng sai định dạng: {e}") from e
        return ThresholdSet(version, signals)


class ServiceToken:
    """Token client-credentials, đệm tới 30 giây trước khi hết hạn."""

    def __init__(self, http: httpx.Client, token_url: str, client_id: str, client_secret: str) -> None:
        self._http, self._url, self._id, self._secret = http, token_url, client_id, client_secret
        self._token: str | None = None
        self._exp = 0.0

    def get(self) -> str:
        if self._token and self._exp - 30 > time.time():
            return self._token
        r = self._http.post(self._url, auth=(self._id, self._secret), data={"grant_type": "client_credentials"})
        if r.status_code != 200 or not isinstance(r.json().get("access_token"), str):
            raise ThresholdError(f"máy chủ cấp token trả {r.status_code}")
        body = r.json()
        self._token = str(body["access_token"])
        self._exp = time.time() + float(body.get("expires_in", 60))
        return self._token


class ThresholdClient:
    def __init__(self, http: httpx.Client, base_url: str, token: ServiceToken) -> None:
        self._http, self._base, self._token = http, base_url, token

    def get(self, tenant: str) -> ThresholdSet:
        r = self._http.get(f"{self._base}/api/v1/config/anomaly-thresholds",
                           headers={"Authorization": f"Bearer {self._token.get()}", "X-Tenant-Id": tenant,
                                    "Accept": "application/json"})
        if r.status_code != 200:
            raise ThresholdError(f"getAnomalyThresholds trả {r.status_code} cho tenant {tenant}")
        return ThresholdSet.parse(r.json())
