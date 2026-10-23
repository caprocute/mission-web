#!/usr/bin/env python3
"""Tạo kỳ đánh giá quý hiện hành cho một tenant chưa có kỳ (server dev, tenant thử mới).

Màn hình cây mục tiêu gọi `GET /okr/tree` không kèm kỳ; máy chủ lấy kỳ đang mở bao trùm hôm nay và
trả 404 khi tenant chưa có kỳ nào (đúng hợp đồng), nên trên một tenant dev mới cây mục tiêu báo
"Chưa có kỳ đánh giá đang mở". Bộ nạp này gọi `listPeriods` để kiểm, rồi `createPeriod` cho quý chứa
ngày hôm nay nếu chưa có. Kỳ mới ở trạng thái đang mở. Chạy lại không tạo trùng.

Biến môi trường (không ghi token vào tệp hay lịch sử lệnh dùng chung):
    MISSION_API    gốc API, ví dụ http://localhost:4200/api/v1
    MISSION_TOKEN  access token của quản trị tenant (tenant_admin)
    MISSION_FLOW   (tuỳ chọn) luồng áp dụng của kỳ: DANG, NHA_NUOC hoặc CA_HAI; mặc định CA_HAI

Cách dùng:
    python3 nap_ky_hien_hanh.py            # tạo quý hiện hành nếu thiếu
    python3 nap_ky_hien_hanh.py --kiem     # chỉ in kỳ đang bao trùm hôm nay, không tạo
"""

import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

LA_MA = {1: "I", 2: "II", 3: "III", 4: "IV"}


class LoiApi(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status


def goi(method: str, url: str, token: str, body: dict | None = None):
    """Gọi API JSON; trả (mã, nội dung đã giải JSON hoặc None)."""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json, application/problem+json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if method != "GET":
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        raise LoiApi(e.code, e.read().decode("utf-8", "replace")) from None


def quy_hien_hanh(hom_nay: dt.date) -> tuple[int, dt.date, dt.date]:
    q = (hom_nay.month - 1) // 3 + 1
    bat_dau = dt.date(hom_nay.year, 3 * q - 2, 1)
    ket_thuc = (dt.date(hom_nay.year + (q == 4), 1 if q == 4 else 3 * q + 1, 1)) - dt.timedelta(days=1)
    return q, bat_dau, ket_thuc


def ky_bao_trum(api: str, token: str, hom_nay: dt.date) -> list[dict]:
    """Các kỳ của năm hiện tại có khoảng ngày chứa hôm nay (đọc hết các trang)."""
    ket_qua, con_tro = [], None
    while True:
        q = {"year": hom_nay.year, "limit": 200}
        if con_tro:
            q["cursor"] = con_tro
        _, trang = goi("GET", f"{api}/periods?{urllib.parse.urlencode(q)}", token)
        for k in trang.get("items", []):
            if k["startDate"] <= hom_nay.isoformat() <= k["endDate"]:
                ket_qua.append(k)
        con_tro = trang.get("nextCursor")
        if not con_tro:
            return ket_qua


def main() -> int:
    api = os.environ.get("MISSION_API", "").rstrip("/")
    token = os.environ.get("MISSION_TOKEN", "")
    if not api or not token:
        print("Cần đặt MISSION_API và MISSION_TOKEN (quản trị tenant).", file=sys.stderr)
        return 2
    hom_nay = dt.date.today()
    co_san = ky_bao_trum(api, token, hom_nay)
    for k in co_san:
        print(f"Đã có kỳ {k['name']} ({k['startDate']} đến {k['endDate']}), trạng thái {k['status']}.")
    if "--kiem" in sys.argv[1:]:
        return 0 if co_san else 1
    if any(k["status"] == "MO" for k in co_san):
        print("Tenant đã có kỳ đang mở bao trùm hôm nay; không tạo thêm.")
        return 0
    q, bat_dau, ket_thuc = quy_hien_hanh(hom_nay)
    than = {
        "code": f"{hom_nay.year}-Q{q}",
        "name": f"Quý {LA_MA[q]}/{hom_nay.year}",
        "kind": "QUY",
        "flow": os.environ.get("MISSION_FLOW", "CA_HAI"),
        "startDate": bat_dau.isoformat(),
        "endDate": ket_thuc.isoformat(),
    }
    try:
        _, ky = goi("POST", f"{api}/periods", token, than)
    except LoiApi as e:
        print(f"Không tạo được kỳ: {e}", file=sys.stderr)
        return 1
    print(f"Đã tạo kỳ {ky['name']} ({ky['startDate']} đến {ky['endDate']}), trạng thái {ky['status']}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
