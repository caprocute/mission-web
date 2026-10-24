#!/usr/bin/env python3
"""Nạp dữ liệu thử tối thiểu của mission-core cho dev qua API thật (APISIX → mission-core).

Chạy sau `seed-org.sh` (đơn vị, hồ sơ cán bộ): tạo kỳ tháng hiện tại nếu chưa có kỳ đang mở bao trùm hôm
nay (tài khoản `quantri`), rồi tạo một nhiệm vụ mẫu do `quanly` giao cho `canbo` nếu kỳ đó chưa có nhiệm vụ
mẫu, để màn hình "Việc của tôi" và trang chủ có dữ liệu. Chạy lại không sinh bản ghi trùng. Gọi API như
người dùng nên mọi kiểm tra nghiệp vụ, kiểm toán và sự kiện chạy như thao tác trên màn hình.

Đọc KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET từ môi trường (hoặc infra/dev/.env) qua lay-token-thu.py;
SEED_API (mặc định http://127.0.0.1:35000/api/v1). Chỉ dùng thư viện chuẩn, không in token.

    python3 -I infra/dev/scripts/seed-ky-nhiem-vu.py
"""

import base64
import datetime as dt
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = os.environ.get("SEED_API", "http://127.0.0.1:35000/api/v1").rstrip("/")
# Nhiệm vụ mẫu nhận theo đúng tên; bản ghi do bản cũ tạo (có tiền tố) vẫn được nhận để không sinh trùng.
TEN_MAU = "Rà soát số liệu báo cáo tháng"
TEN_MAU_CU = "[Mẫu dev] " + TEN_MAU


def nap_env() -> dict:
    env = dict(os.environ)
    f = HERE.parent / ".env"
    if f.exists():
        for dong in f.read_text(encoding="utf-8").splitlines():
            if "=" in dong and not dong.lstrip().startswith("#"):
                k, v = dong.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env


def token(user: str, env: dict) -> str:
    e = dict(env, SMOKE_USER=user)
    kq = subprocess.run(
        [sys.executable, "-I", str(HERE / "lay-token-thu.py")], env=e, capture_output=True, text=True
    )
    if kq.returncode != 0 or not kq.stdout.strip():
        sys.exit(f"Không lấy được token của {user}: {kq.stderr.strip()[:300]}")
    return kq.stdout.strip()


def claims(tok: str) -> dict:
    p = tok.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))


def goi(tok: str, method: str, path: str, body=None, if_match=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/json")
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if method in ("POST", "PUT", "PATCH", "DELETE"):
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    if if_match:
        req.add_header("If-Match", if_match)
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), r.headers.get("ETag")
    except urllib.error.HTTPError as e:
        raw = e.read()
        sys.exit(f"{method} {path} → {e.code}: {raw[:400].decode('utf-8', 'replace')}")


def main() -> int:
    env = nap_env()
    hom = dt.date.today()
    t_qt = token("quantri", env)
    _, ds, _ = goi(t_qt, "GET", "/periods?status=MO&limit=200")
    ky = next(
        (k for k in ds["items"] if k["startDate"] <= hom.isoformat() <= k["endDate"]), None
    )
    if ky:
        print(f"Đã có kỳ đang mở: {ky['name']}")
    else:
        dau = hom.replace(day=1)
        cuoi = (dau + dt.timedelta(days=32)).replace(day=1) - dt.timedelta(days=1)
        _, ky, _ = goi(
            t_qt,
            "POST",
            "/periods",
            {
                "code": f"T{hom.month:02d}-{hom.year}",
                "name": f"Tháng {hom.month}/{hom.year}",
                "kind": "THANG",
                "flow": "CA_HAI",
                "startDate": dau.isoformat(),
                "endDate": cuoi.isoformat(),
            },
        )
        print(f"Đã tạo kỳ: {ky['name']}")

    t_ql = token("quanly", env)
    t_cb = token("canbo", env)
    ql, cb = claims(t_ql), claims(t_cb)
    _, viec, _ = goi(t_ql, "GET", f"/tasks?period={ky['id']}&limit=200")
    mau = next((v for v in viec["items"] if v["title"] in (TEN_MAU, TEN_MAU_CU)), None)
    if mau and mau["status"] != "NHAP":
        print("Đã có nhiệm vụ mẫu trong kỳ")
        return 0
    if mau:
        # Lần chạy trước dừng sau bước soạn (ví dụ registry chưa có lược đồ): giao tiếp bản nháp đó.
        _, ct, etag = goi(t_ql, "GET", f"/tasks/{mau['id']}")
        goi(t_ql, "POST", f"/tasks/{mau['id']}/assign", {"similarReason": "Dữ liệu thử của môi trường dev"},
            if_match=etag or f'"{ct["rowVersion"]}"')
        print(f"Đã giao nhiệm vụ mẫu {ct.get('code', '')} (bản nháp có sẵn)")
        return 0
    han = min(hom + dt.timedelta(days=14), dt.date.fromisoformat(ky["endDate"]))
    moc = min(hom + dt.timedelta(days=7), han)
    _, nhap, etag = goi(
        t_ql,
        "POST",
        "/tasks",
        {
            "source": "DOT_XUAT",
            "status": "NHAP",
            "periodId": ky["id"],
            "unitName": "",
            "assignerId": ql["sub"],
            "assignerName": ql.get("name", "quanly"),
            "title": TEN_MAU,
            "taskGroup": "THUONG_XUYEN",
            "priority": "TRUNG_BINH",
            "productName": "Bảng số liệu đã rà soát",
            "quantity": 1,
            "unit": "bảng",
            "evidenceRequired": "Bảng số liệu có ý kiến của người rà soát",
            "qualityCriteria": "Đủ chỉ tiêu, khớp số liệu nguồn",
            "basis": "Dữ liệu thử của môi trường dev, không phải nhiệm vụ thật",
            "leadPersonId": cb["sub"],
            "coAssignees": [],
            "dueDate": han.isoformat(),
            "milestones": [{"date": moc.isoformat(), "content": "Hoàn thành rà soát lần một"}],
        },
    )
    goi(
        t_ql,
        "POST",
        f"/tasks/{nhap['id']}/assign",
        {"similarReason": "Dữ liệu thử của môi trường dev"},
        if_match=etag or f'"{nhap["rowVersion"]}"',
    )
    print(f"Đã giao nhiệm vụ mẫu {nhap.get('code', '')} cho {cb.get('preferred_username', 'canbo')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
