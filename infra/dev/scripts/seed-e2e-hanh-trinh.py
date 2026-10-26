#!/usr/bin/env python3
"""Nạp dữ liệu dựng trước cho hành trình đầu cuối trên hệ thống thật (QA-03, E2E_MODE=he-thong) ở tenant thử của
realm dev (tài khoản quanly, lanhdao, quantri, canbo, kiemtra), qua API thật như người dùng thao tác:

  1. Kế hoạch quý hiện hành của đơn vị quản lý ở trạng thái đã duyệt, có dòng thuộc danh mục, để HT-13 (giao nhiệm
     vụ từ kế hoạch, SCR-0501) có dòng kế hoạch đã duyệt để chọn. Bản đang chờ duyệt mà không có dòng (HT-11 để lại)
     thì lãnh đạo trả lại, quản lý nhập dòng, trình lại rồi lãnh đạo duyệt kèm ký số.
  2. Một kỳ quý đã qua ở trạng thái đã khoá, để HT-34 (tạo yêu cầu mở lại kỳ, SCR-1301) có kỳ để chọn: quản trị tạo
     kỳ, lập lệnh khoá, lãnh đạo duyệt, quản trị khởi chạy khoá kỳ, chờ saga hoàn tất.

Chạy lại không sinh trùng: bước đã đạt thì bỏ qua. Chỉ dùng thư viện chuẩn (Python 3.6 của server dev), không in
token. Biến môi trường như seed-da-tenant-api.py: SEED_API, KEYCLOAK_PUBLIC_URL, KC_TEST_USER_PASSWORD,
KC_TEST_OTP_SECRET (đọc thêm từ infra/dev/.env).

    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py              # cả hai phần
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py ke-hoach     # chỉ kế hoạch
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py ky-khoa      # chỉ kỳ đã khoá
"""
import datetime as dt
import importlib.util
import sys
import time
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_da_tenant_api", str(HERE / "seed-da-tenant-api.py"))
A = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(A)
goi, etag, LoiApi = A.goi, A.etag, A.LoiApi

QUAN_LY, LANH_DAO, QUAN_TRI, CAN_BO = "quanly", "lanhdao", "quantri", "canbo"
# Kỳ đã khoá dành riêng cho hành trình: quý I của năm hiện hành, đã qua
NAM_KHOA, QUY_KHOA = dt.date.today().year, 1
DONG = [
    ("Tổng hợp báo cáo tình hình hoạt động tháng", "THUONG_XUYEN", 3, 40),
    ("Soạn thảo công văn trả lời đơn vị", "THUONG_XUYEN", 5, 45),
    ("Tham mưu đề án chuyển đổi số nội bộ", "THEN_CHOT", 1, 60),
]


def ky_hien_hanh(tok):
    hom = dt.date.today().isoformat()
    ds, _ = goi(tok, "GET", "/periods?limit=200")
    ky = next((k for k in ds["items"] if k["status"] == "MO" and k["kind"] == "QUY"
               and k["startDate"] <= hom <= k["endDate"]), None)
    if not ky:
        raise RuntimeError("Chưa có kỳ quý đang mở bao trùm hôm nay; chạy seed-ky-hien-hanh.sh trước")
    return ky


def dong_ke_hoach(tok_ql, ky, chu):
    ds, _ = goi(tok_ql, "GET", "/catalog/items?limit=200")
    muc = {m["name"]: m for m in ds["items"]}
    cuoi = dt.datetime.strptime(ky["endDate"][:10], "%Y-%m-%d").date()
    out = []
    for ten, nhom, sl, so_ngay in DONG:
        m = muc.get(ten)
        if not m:
            raise RuntimeError("Danh mục thiếu mục %r; chạy seed-org.sh (task-danh-muc-thu.sql)" % ten)
        han = min(dt.date.today() + dt.timedelta(days=so_ngay), cuoi)
        out.append({"title": ten, "taskGroup": nhom, "leadAssignee": chu, "coAssignees": [],
                    "catalogItemId": m["id"], "productName": m["productName"], "quantity": sl,
                    "unit": m.get("unit"), "dueDate": han.isoformat()})
    return out


def nap_ke_hoach(phien):
    t_ql, t_ld = phien(QUAN_LY), phien(LANH_DAO)
    ky = ky_hien_hanh(t_ql)
    co, _ = goi(t_ql, "GET", "/plans/items?" + urllib.parse.urlencode({"period": ky["id"], "limit": 1}))
    if co.get("items"):
        print("  Kế hoạch %s đã có dòng đã duyệt, bỏ qua" % ky["name"])
        return
    me, _ = goi(t_ql, "GET", "/org/me")
    ql = [a for a in me["assignments"] if "manager" in (a.get("roles") or [])]
    unit = next((a for a in ql if a.get("kind") == "CHINH"), ql[0])["orgUnitId"]
    cb, _ = goi(phien(CAN_BO), "GET", "/org/me")
    chu = {"kind": "PERSON", "id": cb["personId"], "name": cb["fullName"]}
    ds, _ = goi(t_ql, "GET", "/plans?" + urllib.parse.urlencode({"period": ky["id"], "limit": 50}))
    plan = next((p for p in ds["items"] if p.get("unitId") == unit), None)
    if not plan:
        plan, _ = goi(t_ql, "POST", "/plans", {"periodId": ky["id"], "unitId": unit})
        print("  Đã lập kế hoạch %s" % ky["name"])
    v = (plan.get("currentVersion") or {}).get("versionNo", 1)
    duong = "/plans/%s/versions/%s" % (plan["id"], v)
    ver, e = goi(t_ql, "GET", duong)
    if ver["status"] == "CHO_DUYET" and not ver.get("items"):
        ver, e = goi(t_ld, "GET", duong)
        ver, e = goi(t_ld, "POST", duong + "/return",
                     {"comment": "Kế hoạch chưa có dòng công việc, đề nghị bổ sung."}, if_match=etag(ver, e))
        print("  Lãnh đạo trả lại bản chờ duyệt không có dòng")
        ver, e = goi(t_ql, "GET", duong)
    if ver["status"] in ("NHAP", "TRA_LAI"):
        ver, e = goi(t_ql, "PUT", duong, {"items": dong_ke_hoach(t_ql, ky, chu)}, if_match=etag(ver, e))
        ver, e = goi(t_ql, "POST", duong + "/submit", None, if_match=etag(ver, e))
        print("  Đã nhập %d dòng danh mục và trình duyệt" % len(DONG))
    if ver["status"] == "CHO_DUYET":
        ver, e = goi(t_ld, "GET", duong)
        goi(t_ld, "POST", duong + "/approve", {"signMethod": "MYSIGN", "comment": "Đồng ý kế hoạch."},
            if_match=etag(ver, e))
        print("  Lãnh đạo duyệt kèm ký số (tác vụ nền)")
    for _ in range(30):
        co, _ = goi(t_ql, "GET", "/plans/items?" + urllib.parse.urlencode({"period": ky["id"], "limit": 1}))
        if co.get("items"):
            print("  Kế hoạch %s đã duyệt, có dòng để giao việc" % ky["name"])
            return
        time.sleep(4)
    raise RuntimeError("Kế hoạch chưa có dòng đã duyệt sau 2 phút (ký số hoặc duyệt chưa xong)")


def nap_ky_khoa(phien):
    t_qt, t_ld = phien(QUAN_TRI), phien(LANH_DAO)
    dau = dt.date(NAM_KHOA, 3 * QUY_KHOA - 2, 1)
    cuoi = dt.date(NAM_KHOA, 3 * QUY_KHOA + 1, 1) - dt.timedelta(days=1)
    ds, _ = goi(t_qt, "GET", "/periods?limit=200")
    ky = next((k for k in ds["items"] if k["kind"] == "QUY" and k["startDate"] == dau.isoformat()
               and k["endDate"] == cuoi.isoformat()), None)
    if not ky:
        mau = ky_hien_hanh(t_qt)
        ky, _ = goi(t_qt, "POST", "/periods", {
            "code": "%d-Q%d" % (NAM_KHOA, QUY_KHOA), "name": "Quý %s/%d" % (A.LA_MA[QUY_KHOA], NAM_KHOA),
            "kind": "QUY", "flow": mau["flow"], "startDate": dau.isoformat(), "endDate": cuoi.isoformat()})
        print("  Đã tạo kỳ %s" % ky["name"])
    duong = "/periods/%s" % ky["id"]
    for _ in range(60):
        ky, e = goi(t_qt, "GET", duong)
        if ky["status"] == "DA_KHOA":
            print("  Kỳ %s đã khoá" % ky["name"])
            return
        lenh = ky.get("currentLockOrder")
        if not lenh or lenh.get("status") == "DA_HUY":
            lenh, _ = goi(t_qt, "POST", duong + "/lock-orders",
                          {"basisDoc": "Thông báo khoá kỳ dữ liệu kiểm thử đầu cuối"})
            print("  Đã lập lệnh khoá kỳ")
        elif lenh["status"] == "CHO_DUYET":
            goi(t_ld, "POST", duong + "/lock-orders/%s/approve" % lenh["id"],
                {"comment": "Đồng ý khoá kỳ dữ liệu kiểm thử."}, if_match=etag(lenh))
            print("  Lãnh đạo duyệt lệnh khoá kỳ")
        elif lenh["status"] == "DA_DUYET":
            # Máy chủ đòi khoá chống lặp của lệnh khởi chạy theo kỳ: CK-<năm>Q<quý>
            goi(t_qt, "POST", duong + "/lock", {"lockOrderId": lenh["id"]}, if_match=etag(lenh),
                idem="CK-%dQ%d" % (NAM_KHOA, QUY_KHOA))
            print("  Đã khởi chạy khoá kỳ")
        else:
            time.sleep(5)
    raise RuntimeError("Kỳ %s chưa khoá xong sau 5 phút (trạng thái %s)" % (ky["name"], ky["status"]))


def main():
    chon = sys.argv[1:] or ["ke-hoach", "ky-khoa"]
    phien = A.Phien(A._base.nap_env())
    loi = 0
    for ten, fn in (("ke-hoach", nap_ke_hoach), ("ky-khoa", nap_ky_khoa)):
        if ten not in chon:
            continue
        print("==> %s" % ten)
        try:
            fn(phien)
        except (LoiApi, RuntimeError, KeyError, StopIteration) as ex:
            loi += 1
            print("  LỖI: %s" % ex, file=sys.stderr)
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
