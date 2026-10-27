#!/usr/bin/env python3
"""Phát hành biểu mẫu Mẫu 1B (`mau-1b`, phiếu tự đánh giá SCR-0901) cho tenant thử của dev qua API thật.

Phiếu tự đánh giá dựng từ `GET /ui/forms/mau-1b/current`; dev mới dựng không có biểu mẫu này nên trả 404 và
HT-25 (QA-06) không nộp được phiếu. Script đi đúng luồng QT-18 như trên màn hình SCR-1802:
  1. `quantri` tạo phiên bản nháp mới của `mau-1b` (nhân bản bản mới nhất nếu có), ghi lược đồ và giao diện lấy
     từ dữ liệu giả lập của web (`frontend/projects/api-client/src/mock/ui-forms/ui-forms.json`, bản có trường
     `self_rating_code`), chạy bộ test một ca hợp lệ (BR-18-08).
  2. `quantri` lập phiên bản UI Pack nháp (nhân bản bản hiện hành, tăng số vá), gắn biểu mẫu, trình duyệt.
  3. `quantri2` (người thứ hai, bốn mắt) phát hành phiên bản UI Pack khi kiểm tra tự động xong.
Bỏ trống `celRules`: quy tắc của dữ liệu giả lập viết theo tên trường camelCase, không khớp lược đồ snake_case của
máy chủ (wiki 14 mục QA-06). Chạy lại an toàn: biểu mẫu hiện hành đã có `self_rating_code` thì dừng; phiên bản
nháp còn dở thì đi tiếp; đặt PHAT_HANH_LAI=1 để phát hành lại dù đã đủ. Chỉ dùng thư viện chuẩn, tương thích Python 3.6, không in token.

Đọc KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET, KEYCLOAK_PUBLIC_URL qua lay-token-thu.py như các script seed khác;
SEED_API (mặc định http://127.0.0.1:35000/api/v1).

    python3 -I infra/dev/scripts/phat-hanh-mau-1b.py              # tenant thử d000 (quantri, quantri2)
    python3 -I infra/dev/scripts/phat-hanh-mau-1b.py --tien-to cq # tenant UBND tỉnh e2 (cq.quantri, cq.quantri2)
    python3 -I infra/dev/scripts/phat-hanh-mau-1b.py --tat-ca     # mọi tenant có đủ hai tài khoản quản trị

Mỗi tenant cần hai tài khoản quản trị khác nhau cho bước bốn mắt (`<tiền tố>.quantri`, `<tiền tố>.quantri2`); tenant
chỉ có một tài khoản quản trị (tu., dn.) thì không phát hành được bằng script này.
"""

import argparse

import importlib.util
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_ky_nhiem_vu", str(HERE / "seed-ky-nhiem-vu.py"))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
nap_env, token, API = _base.nap_env, _base.token, _base.API

KHOA = "mau-1b"
MAU_GIA_LAP = HERE.parents[2] / "frontend/projects/api-client/src/mock/ui-forms/ui-forms.json"
DANG_KIEM = ("DANG_KIEM_TRA", "CHO_KIEM_TRA", "DANG_CHAY")


def goi(tok, method, path, body=None, if_match=None):
    """Gọi API, trả (mã, thân, ETag); lỗi HTTP trả mã lỗi để script tự quyết định."""
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
        with urllib.request.urlopen(req, data=data, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw.decode("utf-8")) if raw else None), r.headers.get("ETag")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8")), None
        except ValueError:
            return e.code, {"detail": raw[:300].decode("utf-8", "replace")}, None


def can(kq, *ma):
    """Dừng script khi mã trả về không thuộc `ma`."""
    if kq[0] not in ma:
        sys.exit("API trả {}: {}".format(kq[0], json.dumps(kq[1], ensure_ascii=False)[:400]))
    return kq


def etag(kq):
    return kq[2] or '"{}"'.format((kq[1] or {}).get("rowVersion"))


def tang_va(v):
    so = [int(x) for x in v.split(".")]
    so[-1] += 1
    return ".".join(str(x) for x in so)


def phien_ban_bieu_mau(tok, mau):
    """Phiên bản nháp của `mau-1b` đã ghi nội dung và đạt bộ test; trả số phiên bản."""
    ds = can(goi(tok, "GET", "/ui/forms?limit=200"), 200)[1]["items"]
    hien = next((f for f in ds if f["formKey"] == KHOA), None)
    if hien and hien["status"] == "NHAP":
        ban = hien["latestVersion"]
    else:
        ban = str(int(hien["latestVersion"]) + 1) if hien else "1"
        tao = {"version": ban, "cloneFromVersion": hien["latestVersion"] if hien else None}
        can(goi(tok, "POST", "/ui/forms/{}/versions".format(KHOA), tao), 201)
        print("Đã tạo phiên bản nháp {} của {}".format(ban, KHOA))
    kq = can(goi(tok, "GET", "/ui/forms/{}/versions/{}".format(KHOA, ban)), 200)
    noi_dung = {"schemaJson": mau["schemaJson"], "uiJson": mau["uiJson"], "celRules": []}
    can(goi(tok, "PUT", "/ui/forms/{}/versions/{}".format(KHOA, ban), noi_dung, etag(kq)), 200)
    ca = {
        "caseId": "TC-01",
        "input": {"self_rating_code": "HTTNV", "content": "Hoàn thành đúng hạn các nhiệm vụ được giao."},
        "browserValid": True,
        "browserErrors": [],
    }
    kq = can(goi(tok, "POST", "/ui/forms/{}/versions/{}/test-runs".format(KHOA, ban), {"cases": [ca], "renderMs50Fields": 40}), 200, 201)
    if kq[1].get("passed") != kq[1].get("total"):
        sys.exit("Bộ test của biểu mẫu chưa đạt: " + json.dumps(kq[1], ensure_ascii=False)[:400])
    print("Phiên bản {} của {} đã đạt bộ test".format(ban, KHOA))
    return ban


def phat_hanh_goi(tok, tok2, ban_mau):
    goi_ds = can(goi(tok, "GET", "/ui/packs"), 200)[1]["items"]
    if not goi_ds:
        sys.exit("Tenant chưa có UI Pack nào; chạy khởi tạo tenant trước.")
    p = goi_ds[0]
    ban = p.get("draftVersion")
    if not ban:
        ban = tang_va(p.get("currentVersion") or "1.0.0")
        can(goi(tok, "POST", "/ui/packs/{}/versions".format(p["id"]), {"version": ban, "cloneFromVersion": p.get("currentVersion")}), 201)
        print("Đã lập phiên bản UI Pack nháp {}".format(ban))
    duong = "/ui/packs/{}/versions/{}".format(p["id"], ban)
    kq = can(goi(tok, "GET", duong), 200)
    if kq[1].get("status") == "NHAP":
        kq = can(goi(tok, "POST", duong + "/forms", {"formKey": KHOA, "formVersion": ban_mau}, etag(kq)), 200, 201)
        kq = can(goi(tok, "POST", duong + "/submit", None, etag(kq)), 200, 202)
        print("Đã gắn biểu mẫu và trình phiên bản UI Pack {}".format(ban))
    for _ in range(30):
        kq = can(goi(tok2, "GET", duong), 200)
        if kq[1].get("status") not in DANG_KIEM:
            break
        time.sleep(4)
    if kq[1].get("status") != "DA_PHAT_HANH":
        can(goi(tok2, "POST", duong + "/publish", {"comment": "Phát hành biểu mẫu Mẫu 1B cho dev"}, etag(kq)), 200, 202)
    print("Đã phát hành phiên bản UI Pack {}".format(ban))


# Tiền tố tài khoản của các tenant có đủ hai quản trị viên (realm dev, infra/dev/keycloak/tai-khoan-da-tenant.json).
TIEN_TO_DU_HAI_QUAN_TRI = ("", "cq")


def main():
    ap = argparse.ArgumentParser(description="Phát hành Mẫu 1B cho tenant thử của dev")
    ap.add_argument("--tien-to", default="", help="tiền tố tài khoản của tenant, ví dụ cq (mặc định: tenant d000)")
    ap.add_argument("--tat-ca", action="store_true", help="phát hành cho mọi tenant có đủ hai quản trị viên")
    args = ap.parse_args()
    ma = 0
    for tien_to in (TIEN_TO_DU_HAI_QUAN_TRI if args.tat_ca else (args.tien_to,)):
        ma = phat_hanh_tenant(tien_to) or ma
    return ma


def phat_hanh_tenant(tien_to):
    env = nap_env()
    ten = (tien_to + ".") if tien_to else ""
    print("== Tenant của {}quantri".format(ten))
    tok = token(ten + "quantri", env)
    kq = goi(tok, "GET", "/ui/forms/{}/current".format(KHOA))
    ep = env.get("PHAT_HANH_LAI") == "1"
    if not ep and kq[0] == 200 and "self_rating_code" in (kq[1].get("schemaJson") or {}).get("properties", {}):
        print("Biểu mẫu {} hiện hành (phiên bản {}) đã đủ trường, không cần phát hành.".format(KHOA, kq[1].get("version")))
        return 0
    mau = json.loads(MAU_GIA_LAP.read_text(encoding="utf-8"))["forms"][KHOA]
    ban_mau = phien_ban_bieu_mau(tok, mau)
    phat_hanh_goi(tok, token(ten + "quantri2", env), ban_mau)
    # Bản hiện hành đọc qua bộ đệm nên cập nhật chậm vài giây sau khi phát hành.
    for _ in range(15):
        kq = can(goi(tok, "GET", "/ui/forms/{}/current".format(KHOA)), 200)
        if kq[1].get("version") == ban_mau:
            print("Biểu mẫu {} hiện hành: phiên bản {}".format(KHOA, ban_mau))
            return 0
        time.sleep(2)
    print("Đã phát hành nhưng biểu mẫu hiện hành vẫn là phiên bản {}".format(kq[1].get("version")))
    return 1


if __name__ == "__main__":
    sys.exit(main())
