#!/usr/bin/env python3
"""Nạp dữ liệu dựng trước cho hành trình đầu cuối trên hệ thống thật (QA-03, E2E_MODE=he-thong) ở tenant thử của
realm dev (tài khoản quanly, lanhdao, quantri, canbo, kiemtra), qua API thật như người dùng thao tác:

  1. Kế hoạch quý hiện hành của đơn vị quản lý ở trạng thái đã duyệt, có dòng thuộc danh mục, để HT-13 (giao nhiệm
     vụ từ kế hoạch, SCR-0501) có dòng kế hoạch đã duyệt để chọn. Bản đang chờ duyệt mà không có dòng (HT-11 để lại)
     thì lãnh đạo trả lại, quản lý nhập dòng, trình lại rồi lãnh đạo duyệt kèm ký số.
  2. Một kỳ quý đã qua ở trạng thái đã khoá, để HT-34 (tạo yêu cầu mở lại kỳ, SCR-1301) có kỳ để chọn: quản trị tạo
     kỳ, lập lệnh khoá, lãnh đạo duyệt, quản trị khởi chạy khoá kỳ, chờ saga hoàn tất.
  3. Phát hành Policy Pack luồng Đảng nếu tenant chưa có (phat-hanh): ca vàng, gửi duyệt với kỳ hiệu lực tương lai,
     người quyết định ký phát hành.
  4. Hồ sơ đánh giá quý cho HT-25..33, HT-39 (ho-so): khoá kỳ quý đầu tiên trong khoảng hiệu lực của gói luồng Đảng
     (hồ sơ quý luồng Nhà nước đứng ở CHUA_LAP đúng thiết kế), chờ hồ sơ của canbo sang bước tự đánh giá. Các bước tự đánh giá, thẩm định,
     quyết định ký lô do chính HT-25..27 làm trên giao diện.
  5. Kỳ quý chưa bắt đầu còn mở (luồng CA_HAI) để HT-38 (cấu hình luồng duyệt, SCR-1504) có kỳ bắt đầu áp dụng.

Chạy lại không sinh trùng: bước đã đạt thì bỏ qua. Chỉ dùng thư viện chuẩn (Python 3.6 của server dev), không in
token. Biến môi trường như seed-da-tenant-api.py: SEED_API, KEYCLOAK_PUBLIC_URL, KC_TEST_USER_PASSWORD,
KC_TEST_OTP_SECRET (đọc thêm từ infra/dev/.env).

    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py              # cả hai phần
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py ke-hoach     # chỉ kế hoạch
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py ky-khoa      # chỉ kỳ đã khoá
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py phat-hanh ho-so ky-sau   # chuỗi hồ sơ đánh giá
    python3 -I infra/dev/scripts/seed-e2e-hanh-trinh.py ky-sau       # chỉ kỳ tương lai còn mở
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
CHINH_SACH, QUYET_DINH = "chinhsach", "quyetdinh"
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


def ky_quy(nam, q):
    dau = dt.date(nam, 3 * q - 2, 1)
    cuoi = dt.date(nam + (q == 4), 1 if q == 4 else 3 * q + 1, 1) - dt.timedelta(days=1)
    return dau, cuoi


def nap_ky_khoa(phien, nam=NAM_KHOA, quy=QUY_KHOA):
    t_qt, t_ld = phien(QUAN_TRI), phien(LANH_DAO)
    dau, cuoi = ky_quy(nam, quy)
    ds, _ = goi(t_qt, "GET", "/periods?limit=200")
    ky = next((k for k in ds["items"] if k["kind"] == "QUY" and k["startDate"] == dau.isoformat()
               and k["endDate"] == cuoi.isoformat()), None)
    if not ky:
        mau = ky_hien_hanh(t_qt)
        ky, _ = goi(t_qt, "POST", "/periods", {
            "code": "%d-Q%d" % (nam, quy), "name": "Quý %s/%d" % (A.LA_MA[quy], nam),
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
                idem="CK-%dQ%d" % (nam, quy))
            print("  Đã khởi chạy khoá kỳ")
        else:
            time.sleep(5)
    raise RuntimeError("Kỳ %s chưa khoá xong sau 5 phút (trạng thái %s)" % (ky["name"], ky["status"]))


def nap_ky_sau(phien):
    """Bảo đảm có một kỳ quý chưa bắt đầu còn mở (kỳ kế tiếp có thể đã bị khoá để lập hồ sơ ở bước ho-so)."""
    t_qt = phien(QUAN_TRI)
    hom = dt.date.today()
    nam, q = hom.year, (hom.month - 1) // 3 + 1
    ds, _ = goi(t_qt, "GET", "/periods?limit=200")
    for _ in range(8):
        nam, q = (nam + 1, 1) if q == 4 else (nam, q + 1)
        dau, cuoi = ky_quy(nam, q)
        co = next((k for k in ds["items"] if k["kind"] == "QUY" and k["startDate"] == dau.isoformat()), None)
        if co and co["status"] == "MO":
            print("  Kỳ %s chưa bắt đầu và đang mở, bỏ qua" % co["name"])
            return
        if not co:
            ky, _ = goi(t_qt, "POST", "/periods", {
                "code": "%d-Q%d" % (nam, q), "name": "Quý %s/%d" % (A.LA_MA[q], nam), "kind": "QUY",
                "flow": "CA_HAI", "startDate": dau.isoformat(), "endDate": cuoi.isoformat()})
            print("  Đã tạo kỳ %s" % ky["name"])
            return
    raise RuntimeError("Không tìm được quý tương lai còn trống để tạo kỳ")


def nap_ho_so(phien):
    """Hồ sơ đánh giá quý chỉ được lập khi khoá một kỳ có Policy Pack đã phát hành áp dụng (EVT-09, MOD-04).
    Khoá kỳ quý đầu tiên trong khoảng hiệu lực của gói đã phát hành sớm nhất, rồi chờ hồ sơ của cán bộ thử sang
    bước tự đánh giá (EVT-10 của scoring-service mở hồ sơ CHUA_LAP)."""
    t_cs = phien(CHINH_SACH)
    ds, _ = goi(t_cs, "GET", "/policies/packs?limit=200")
    tu = []
    # Hồ sơ quý luồng Nhà nước đứng ở CHUA_LAP đúng thiết kế (chỉ xếp loại năm), nên chỉ xét gói luồng Đảng
    for g in [x for x in ds["items"] if x.get("flow") == "DANG"]:
        for v in g.get("versions", []):
            if v.get("status") != "DA_PHAT_HANH":
                continue
            ct, _ = goi(t_cs, "GET", "/policies/packs/%s/versions/%s" % (g["id"], v["version"]))
            if ct.get("effectiveFrom"):
                tu.append(ct["effectiveFrom"][:10])
    if not tu:
        raise RuntimeError("Chưa có Policy Pack luồng Đảng đã phát hành; chạy bước phat-hanh trước")
    d = dt.datetime.strptime(min(tu), "%Y-%m-%d").date()
    nam, q = d.year, (d.month - 1) // 3 + 1
    if dt.date(nam, 3 * q - 2, 1) < d:
        nam, q = (nam + 1, 1) if q == 4 else (nam, q + 1)
    # Kỳ trong khoảng hiệu lực đã khoá ở lần trước mà chưa có hồ sơ luồng Đảng thì lấy quý kế tiếp
    for _ in range(4):
        dau, _c = ky_quy(nam, q)
        ds, _ = goi(phien(QUAN_TRI), "GET", "/periods?limit=200")
        k = next((x for x in ds["items"] if x["kind"] == "QUY" and x["startDate"] == dau.isoformat()), None)
        if not k or k["status"] != "DA_KHOA":
            break
        nam, q = (nam + 1, 1) if q == 4 else (nam, q + 1)
    nap_ky_khoa(phien, nam, q)
    t_cb = phien(CAN_BO)
    for _ in range(36):
        hs, _ = goi(t_cb, "GET", "/appraisals/profiles/mine?limit=50")
        tt = sorted({h["status"] for h in hs.get("items", [])})
        if any(x != "CHUA_LAP" for x in tt):
            print("  Hồ sơ của %s: %s" % (CAN_BO, ", ".join(tt)))
            return
        time.sleep(5)
    raise RuntimeError("Hồ sơ của %s chưa sang tự đánh giá sau 3 phút (trạng thái %s); kiểm scoring-service" %
                       (CAN_BO, ", ".join(tt) or "chưa có hồ sơ"))


def nap_phat_hanh(phien):
    """Phát hành Policy Pack luồng Đảng (QT-15) khi tenant chưa có bản nào đã phát hành: chạy ca vàng, gửi duyệt với
    kỳ hiệu lực là kỳ quý tương lai còn mở, người quyết định ký phát hành (tác vụ nền)."""
    t_cs, t_qd = phien(CHINH_SACH), phien(QUYET_DINH)
    ds, _ = goi(t_cs, "GET", "/policies/packs?limit=200")
    g = next((x for x in ds["items"] if x.get("flow") == "DANG"), None)
    if not g:
        raise RuntimeError("Tenant chưa có Policy Pack luồng Đảng (seed-org.sh nạp policy-goi-mau.sql)")
    if any(v.get("status") == "DA_PHAT_HANH" for v in g.get("versions", [])):
        print("  Gói luồng Đảng đã có bản phát hành, bỏ qua")
        return
    v = next((x["version"] for x in g.get("versions", []) if x.get("status") in ("NHAP", "CHO_PHE_DUYET")), None)
    if not v:
        raise RuntimeError("Gói luồng Đảng không có bản nháp hoặc bản chờ phê duyệt để phát hành")
    duong = "/policies/packs/%s/versions/%s" % (g["id"], v)
    ver, e = goi(t_cs, "GET", duong)
    if ver["status"] == "NHAP":
        goi(t_cs, "POST", duong + "/test-runs", {}, if_match=etag(ver, e))
        for _ in range(60):
            runs, _ = goi(t_cs, "GET", duong + "/test-runs?limit=1")
            r = (runs.get("items") or [{}])[0]
            if r.get("result") and r["result"] != "DANG_CHAY":
                break
            time.sleep(3)
        if r.get("result") != "DAT":
            raise RuntimeError("Ca vàng gói luồng Đảng %s: %s %s/%s, khớp hai engine %s" % (
                v, r.get("result"), r.get("passed"), r.get("total"), r.get("engineMatch")))
        print("  Ca vàng đạt %s/%s" % (r["passed"], r["total"]))
        hom = dt.date.today().isoformat()
        kys, _ = goi(phien(QUAN_TRI), "GET", "/periods?limit=200")
        sau = sorted((k for k in kys["items"] if k["status"] == "MO" and k["startDate"] > hom
                      and k["flow"] in ("DANG", "CA_HAI")), key=lambda k: k["startDate"])
        if not sau:
            raise RuntimeError("Không có kỳ tương lai còn mở làm kỳ hiệu lực; chạy bước ky-sau trước")
        ver, e = goi(t_cs, "GET", duong)
        goi(t_cs, "POST", duong + "/submit", {"effectivePeriodId": sau[0]["id"], "scopes": [{"flow": "DANG"}]},
            if_match=etag(ver, e))
        print("  Đã gửi duyệt phát hành bản %s, hiệu lực từ %s" % (v, sau[0]["name"]))
    ver, e = goi(t_qd, "GET", duong)
    if ver["status"] == "CHO_PHE_DUYET":
        goi(t_qd, "POST", duong + "/approve", {"comment": "Đồng ý phát hành bộ quy định cho dữ liệu thử."},
            if_match=etag(ver, e))
        print("  Người quyết định ký phát hành (tác vụ nền)")
    for _ in range(40):
        ver, _ = goi(t_cs, "GET", duong)
        if ver["status"] == "DA_PHAT_HANH":
            print("  Bản %s đã phát hành" % v)
            return
        time.sleep(3)
    raise RuntimeError("Bản %s chưa phát hành sau 2 phút (trạng thái %s)" % (v, ver["status"]))


def main():
    chon = sys.argv[1:] or ["ke-hoach", "ky-khoa", "ky-sau", "phat-hanh", "ho-so", "ky-sau"]
    phien = A.Phien(A._base.nap_env())
    loi = 0
    buoc = {"ke-hoach": nap_ke_hoach, "ky-khoa": nap_ky_khoa, "ky-sau": nap_ky_sau, "phat-hanh": nap_phat_hanh,
            "ho-so": nap_ho_so}
    for ten in chon:
        fn = buoc[ten]
        print("==> %s" % ten)
        try:
            fn(phien)
        except (LoiApi, RuntimeError, KeyError, StopIteration) as ex:
            loi += 1
            print("  LỖI: %s" % ex, file=sys.stderr)
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
