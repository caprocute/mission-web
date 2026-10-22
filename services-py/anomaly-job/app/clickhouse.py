"""Client HTTP tối thiểu cho ClickHouse. Giá trị đưa vào truy vấn luôn qua tham số {tên:Kiểu} (param_<tên>), không
ghép chuỗi."""

from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

import httpx


class ClickHouseError(RuntimeError):
    pass


class ClickHouse:
    def __init__(self, url: str, user: str, password: str, database: str, timeout: float = 1800) -> None:
        self._http = httpx.Client(base_url=url, auth=(user, password), timeout=timeout)
        self._db = database

    def close(self) -> None:
        self._http.close()

    def run(self, sql: str, params: Mapping[str, Any] | None = None) -> str:
        q: dict[str, str] = {"database": self._db}
        for k, v in (params or {}).items():
            q[f"param_{k}"] = _param(v)
        r = self._http.post("/", params=q, content=sql.encode())
        if r.status_code != 200:
            # Lỗi ClickHouse lặp lại cả truy vấn; giữ phần đầu (mã lỗi) và phần cuối (vị trí lỗi).
            raise ClickHouseError(r.text if len(r.text) <= 4000 else r.text[:2000] + " … " + r.text[-2000:])
        return r.text

    def rows(self, sql: str, params: Mapping[str, Any] | None = None) -> list[dict[str, Any]]:
        out = self.run(sql + "\nFORMAT JSONEachRow", params)
        return [json.loads(line) for line in out.splitlines() if line]


def _param(v: Any) -> str:
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, list | tuple):
        return "[" + ",".join(_quote(x) if isinstance(x, str) else str(x) for x in v) + "]"
    return str(v)


def _quote(s: str) -> str:
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
