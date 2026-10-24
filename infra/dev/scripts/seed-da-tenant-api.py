#!/usr/bin/env python3
"""Nạp phần nghiệp vụ của bộ dữ liệu thử đa tenant qua API thật (APISIX → mission-core), như người dùng thao tác.

Chạy sau `seed-da-tenant.sh` phần SQL, tài khoản Keycloak và đồng bộ tổ chức. Với từng tenant trong
`data/seed/da-tenant/du_lieu.py`, lần lượt (mỗi bước độc lập, lỗi một bước không chặn bước khác):
  1. Quản trị tenant tạo kỳ quý hiện hành nếu chưa có kỳ đang mở bao trùm hôm nay (luồng theo tenant).
  2. Mục tiêu (OKR) theo cây phân rã ở bốn trạng thái: bản nháp, chờ duyệt, bị trả lại kèm ý kiến, đã duyệt.
  3. Lịch sử check-in nhiều lần của KR cá nhân và tập thể (có lần mức tự tin thấp kèm vướng mắc).
  4. Nhiệm vụ ở đủ trạng thái: nháp, mới giao, đang làm, chờ nghiệm thu, hoàn thành, tạm dừng, huỷ; kèm điểm
     nghẽn (mở, đang xử lý, đã giải quyết), đề nghị điều chỉnh (chờ duyệt, đã duyệt), nhắc việc, giao lại.
  5. Kế hoạch công tác quý: đang lập, chờ duyệt, bị trả lại, (thử) duyệt kèm ký số.
  6. Uỷ quyền duyệt thay, check-in thay; phân quyền có thời hạn chờ duyệt và đã duyệt (bốn mắt).
  7. Kỳ quý liền trước đã kết thúc: mục tiêu đã duyệt với lịch sử check-in tới cuối kỳ, lệnh khoá kỳ đã lập và
     được phê duyệt (việc khởi chạy khoá kỳ để người thử tự làm).
  8. UI Pack mặc định cho tenant (chỉ khi Python ≥ 3.10, vì công cụ nạp UI Pack dùng cú pháp mới).
Chạy lại không sinh trùng: bản ghi nhận theo tên, bước đã xong thì bỏ qua, bước dở thì đi tiếp. Cuối cùng in bảng
thống kê theo tenant: số bản ghi tạo mới, đã có sẵn, và danh sách bước lỗi kèm lý do (để đối chiếu với
docs/kich-ban-trai-nghiem/BANG-DOI-CHIEU-VAI-TRO.md). Mã thoát 1 nếu có lỗi. Chỉ dùng thư viện chuẩn, tương thích
Python 3.6, không in token.

    python3 -I infra/dev/scripts/seed-da-tenant-api.py            # cả ba tenant
    python3 -I infra/dev/scripts/seed-da-tenant-api.py dang cq    # chỉ một số tenant (dang, cq, dn)

Biến môi trường như seed-ky-nhiem-vu.py: SEED_API, KEYCLOAK_PUBLIC_URL, KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET
(đọc thêm từ infra/dev/.env).
"""

import datetime as dt
import importlib.util
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_spec = importlib.util.spec_from_file_location("seed_ky_nhiem_vu", str(HERE / "seed-ky-nhiem-vu.py"))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
_spec2 = importlib.util.spec_from_file_location("du_lieu", str(ROOT / "data/seed/da-tenant/du_lieu.py"))
D = importlib.util.module_from_spec(_spec2)
_spec2.loader.exec_module(D)

API = _base.API
LA_MA = {1: "I", 2: "II", 3: "III", 4: "IV"}
UI_PACK = ROOT / "data/seed/ui-pack/nap_ui_pack.py"


class LoiApi(Exception):
    def __init__(self, method, path, code, body):
        Exception.__init__(self, "%s %s → %s: %s" % (method, path, code, body[:400]))
        self.code = code
        self.body = body


class Phien:
    """Token theo tài khoản: đăng nhập một lần cho mỗi tài khoản (mã OTP không dùng lại được trong cùng 30 giây)."""

    def __init__(self, env):
        self.env = env
        self.tok = {}

    def __call__(self, tk):
        t = self.tok.get(tk)
        if t and _base.claims(t).get("exp", 0) > time.time() + 60:
            return t
        if self.env.get("SEED_FAKE_TOKEN"):
            # Chỉ cho kiểm thử bộ nạp với máy chủ giả lập: token không ký, không dùng được với máy chủ thật.
            import base64
            than = json.dumps({"sub": tk, "preferred_username": tk, "exp": int(time.time()) + 3600}).encode()
            self.tok[tk] = "e30." + base64.urlsafe_b64encode(than).decode().rstrip("=") + ".x"
            return self.tok[tk]
        for lan in range(3):
            e = dict(self.env, SMOKE_USER=tk)
            kq = subprocess.run([sys.executable, "-I", str(HERE / "lay-token-thu.py")], env=e,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
            if kq.returncode == 0 and kq.stdout.strip():
                self.tok[tk] = kq.stdout.strip()
                return self.tok[tk]
            if lan < 2:
                time.sleep(31)
        raise RuntimeError("Không đăng nhập được %s: %s" % (tk, kq.stderr.strip()[:300]))


def goi(tok, method, path, body=None, if_match=None, idem=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/json")
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if method in ("POST", "PUT", "PATCH", "DELETE"):
        req.add_header("Idempotency-Key", idem or str(uuid.uuid4()))
    if if_match:
        req.add_header("If-Match", if_match)
    try:
        with urllib.request.urlopen(req, data=data, timeout=60) as r:
            raw = r.read()
            return (json.loads(raw.decode("utf-8")) if raw else None), r.headers.get("ETag")
    except urllib.error.HTTPError as e:
        raise LoiApi(method, path, e.code, e.read().decode("utf-8", "replace"))


def etag(o, e=None):
    return e or '"%s"' % o["rowVersion"]


def ngay(d):
    return d.isoformat()


def ref_id(x):
    """Mã của một tham chiếu {id, name} hoặc chính chuỗi mã."""
    return x.get("id") if isinstance(x, dict) else x


def gio_utc(d, h=1):
    """Thời điểm ISO-8601 (UTC) đầu ngày làm việc Việt Nam của ngày d."""
    return dt.datetime.combine(d, dt.time(h, 0)).strftime("%Y-%m-%dT%H:%M:%SZ")


class ThongKe:
    """Đếm bản ghi tạo mới, đã có sẵn và bước lỗi theo tenant, in bảng cuối cùng."""

    def __init__(self):
        self.dong = {}
        self.loi = {}

    def dem(self, tenant, hang_muc, moi):
        d = self.dong.setdefault(tenant, {}).setdefault(hang_muc, [0, 0])
        d[0 if moi else 1] += 1

    def ghi_loi(self, tenant, buoc, e):
        self.loi.setdefault(tenant, []).append((buoc, str(e)))

    def in_bang(self):
        print("\n==> Thống kê nạp dữ liệu (mới / đã có)")
        for tenant, hm in self.dong.items():
            print("  %s" % tenant)
            for k, (moi, cu) in hm.items():
                print("    %-32s %3d / %3d" % (k, moi, cu))
            for buoc, ly_do in self.loi.get(tenant, []):
                print("    LỖI %s: %s" % (buoc, ly_do[:300]))
        for tenant, ds in self.loi.items():
            if tenant not in self.dong:
                for buoc, ly_do in ds:
                    print("  %s LỖI %s: %s" % (tenant, buoc, ly_do[:300]))


class NapTenant:
    def __init__(self, t, phien, tk):
        self.t = t
        self.p = phien
        self.tk = tk
        self.cb = {c["tk"]: c for c in t["can_bo"]}
        self.ky = None
        self.ky_truoc = None
        self.muc_tieu = {}
        self.muc_tieu_truoc = {}
        self.loi = 0

    # Tiện ích ---------------------------------------------------------------------------------------------
    def u(self, so):
        return D.uid(self.t["tien_to"], so)

    def id_nguoi(self, tk):
        return self.u(self.cb[tk]["so"])

    def ten_nguoi(self, tk):
        return self.cb[tk]["ten"]

    def tok(self, tk):
        return self.p(tk)

    def quan_tri(self):
        return next(c["tk"] for c in self.t["can_bo"] if "tenant_admin" in c["vai_tro"])

    def dem(self, hang_muc, moi):
        self.tk.dem(self.t["ten_ngan"], hang_muc, moi)

    def buoc(self, ten, fn):
        """Chạy một bước, lỗi chỉ ghi nhận và in ra, không chặn các bước sau."""
        try:
            fn()
        except (LoiApi, RuntimeError, KeyError, StopIteration, ValueError) as e:
            self.loi += 1
            self.tk.ghi_loi(self.t["ten_ngan"], ten, e)
            print("  LỖI [%s]: %s" % (ten, e), file=sys.stderr)

    # 1. Kỳ ------------------------------------------------------------------------------------------------
    def ky_quy(self, nam, q):
        dau = dt.date(nam, 3 * q - 2, 1)
        cuoi = dt.date(nam + (q == 4), 1 if q == 4 else 3 * q + 1, 1) - dt.timedelta(days=1)
        return dau, cuoi

    def tim_hoac_tao_ky(self, nam, q, trang_thai):
        tok = self.tok(self.quan_tri())
        dau, cuoi = self.ky_quy(nam, q)
        ds, _ = goi(tok, "GET", "/periods?limit=200")
        ky = next((k for k in ds["items"] if k["startDate"] == ngay(dau) and k["endDate"] == ngay(cuoi)
                   and k.get("flow") == self.t["luong_ky"]), None)
        if ky:
            self.dem("Kỳ đánh giá", False)
            return ky
        ky, _ = goi(tok, "POST", "/periods", {
            "code": "%d-Q%d" % (nam, q), "name": "Quý %s/%d" % (LA_MA[q], nam), "kind": "QUY",
            "flow": self.t["luong_ky"], "startDate": ngay(dau), "endDate": ngay(cuoi)})
        self.dem("Kỳ đánh giá", True)
        print("  Đã tạo kỳ: %s" % ky["name"])
        return ky

    def nap_ky(self):
        hom = dt.date.today()
        q = (hom.month - 1) // 3 + 1
        self.ky = self.tim_hoac_tao_ky(hom.year, q, "MO")
        print("  Kỳ hiện hành: %s" % self.ky["name"])

    # 2. OKR -------------------------------------------------------------------------------------------------
    def tim_muc_tieu(self, tok, ky, dv, ten):
        q = urllib.parse.urlencode({"period": ky["id"], "unit": self.u(dv), "view": "table", "limit": 200})
        ds, _ = goi(tok, "GET", "/okr/objectives?" + q)
        return next((o for o in ds["items"] if o["title"] == ten), None)

    def nap_muc_tieu(self, o, ky, kho):
        t_soan, t_duyet = self.tok(o["soan"]), self.tok(o["duyet"])
        dich = o.get("dich", "DA_DUYET")
        mt = self.tim_muc_tieu(t_soan, ky, o["dv"], o["ten"])
        moi = mt is None
        if not mt:
            than = {
                "periodId": ky["id"], "ownerType": o["loai"], "ownerUnitId": self.u(o["dv"]), "title": o["ten"],
                "basisIds": [self.u(b) for b in o["can_cu"]],
                "keyResults": [{
                    "title": k[0], "metricType": k[1], "unit": k[2], "baselineValue": k[3], "targetValue": k[4],
                    "direction": k[5], "weight": k[6], "verifierPersonId": self.id_nguoi(k[7]),
                    "checkinFrequency": "TUAN"} for k in o["kr"]],
            }
            if o["loai"] == "CA_NHAN":
                than["ownerPersonId"] = self.id_nguoi(o["soan"])
            if o.get("cha"):
                than["parentObjectiveId"] = kho[o["cha"]]["id"]
            mt, _ = goi(t_soan, "POST", "/okr/objectives", than)
            print("  Đã soạn mục tiêu: %s" % o["ten"])
        if dich != "NHAP" and (mt["status"] == "NHAP" or (mt["status"] == "TRA_LAI" and dich != "TRA_LAI")):
            mt, e = goi(t_soan, "GET", "/okr/objectives/%s" % mt["id"])
            mt, _ = goi(t_soan, "POST", "/okr/objectives/%s/submit" % mt["id"], {}, if_match=etag(mt, e))
            print("  Đã trình mục tiêu: %s" % o["ten"])
        if dich == "TRA_LAI" and mt["status"] == "CHO_DUYET":
            mt, e = goi(t_duyet, "GET", "/okr/objectives/%s" % mt["id"])
            goi(t_duyet, "POST", "/okr/objectives/%s/return" % mt["id"], {"comment": o["y_kien"]},
                if_match=etag(mt, e))
            print("  Đã trả lại mục tiêu: %s" % o["ten"])
        if dich == "DA_DUYET" and mt["status"] == "CHO_DUYET":
            mt, e = goi(t_duyet, "GET", "/okr/objectives/%s" % mt["id"])
            goi(t_duyet, "POST", "/okr/objectives/%s/approve" % mt["id"],
                {"comment": "Đồng ý, đề nghị triển khai thực hiện.", "notifyPeers": False}, if_match=etag(mt, e))
            print("  Đã phê duyệt mục tiêu: %s" % o["ten"])
        mt, _ = goi(t_soan, "GET", "/okr/objectives/%s" % mt["id"])
        kho[o["khoa"]] = mt
        self.dem("Mục tiêu (%s)" % mt["status"], moi)

    def kr(self, khoa, thu_tu, nguon=None, kho=None):
        nguon = nguon if nguon is not None else D.OKR[self.t["ma"]]
        kho = kho if kho is not None else self.muc_tieu
        o = next(x for x in nguon if x["khoa"] == khoa)
        ten = o["kr"][thu_tu - 1][0]
        return next(k for k in kho[khoa]["keyResults"] if k["title"] == ten)

    # 3. Check-in ---------------------------------------------------------------------------------------------
    def nap_check_in(self, o, nguon, kho):
        if o.get("dich", "DA_DUYET") != "DA_DUYET" or o["khoa"] not in kho:
            return
        tok = self.tok(o["soan"])
        for i, k in enumerate(o["kr"], 1):
            lan = self.check_in_cua(k)
            if not lan:
                continue
            kr = self.kr(o["khoa"], i, nguon, kho)
            ds, _ = goi(tok, "GET", "/okr/key-results/%s/checkins?limit=50" % kr["id"])
            da_co = sum(1 for c in ds.get("items", []) if c.get("status") == "DA_GUI")
            for gia_tri, tu_tin in lan[:da_co]:
                self.dem("Check-in", False)
            for gia_tri, tu_tin in lan[da_co:]:
                than = {"status": "DA_GUI", "value": gia_tri, "confidence": tu_tin, "channel": "WEB",
                        "confirmLargeChange": True,
                        "highlights": "Cập nhật tiến độ theo số liệu đã đối chiếu với người xác nhận."}
                if tu_tin == "THAP":
                    than["blockerNote"] = "Tiến độ chậm so với kế hoạch, đề nghị lãnh đạo hỗ trợ đôn đốc các đơn vị phối hợp."
                goi(tok, "POST", "/okr/key-results/%s/checkins" % kr["id"], than)
                self.dem("Check-in", True)
                print("  Đã check-in: %s = %s (%s)" % (k[0][:50], gia_tri, tu_tin))

    @staticmethod
    def check_in_cua(k):
        if k[8] is None:
            return []
        if isinstance(k[8], (list, tuple)):
            return [tuple(x) for x in k[8]]
        return [(k[8], k[9])]

    # 4. Nhiệm vụ -----------------------------------------------------------------------------------------------
    def tim_nhiem_vu(self, tok, dv, ten):
        q = urllib.parse.urlencode({"period": self.ky["id"], "unit": self.u(dv), "limit": 200})
        ds, _ = goi(tok, "GET", "/tasks?" + q)
        return next((v for v in ds["items"] if v["title"] == ten), None)

    def chi_tiet(self, tok, nv_id):
        return goi(tok, "GET", "/tasks/%s" % nv_id)

    def nap_nhiem_vu(self, n):
        giao, chu, ten = n["giao"], n["chu"], n["ten"]
        dich = D.dich_nhiem_vu(n)
        t_giao = self.tok(giao)
        hom = dt.date.today()
        cuoi_ky = dt.datetime.strptime(self.ky["endDate"][:10], "%Y-%m-%d").date()
        nv = self.tim_nhiem_vu(t_giao, self.cb[giao]["dv"], ten)
        moi = nv is None
        if not nv:
            han = min(hom + dt.timedelta(days=n["so_ngay"]), cuoi_ky)
            moc = min(hom + dt.timedelta(days=max(1, n["so_ngay"] // 2)), han)
            than = {
                "source": "DOT_XUAT", "status": "NHAP", "periodId": self.ky["id"], "unitName": "",
                "assignerId": self.id_nguoi(giao), "assignerName": self.ten_nguoi(giao), "title": ten,
                "taskGroup": n["nhom"], "priority": n["uu_tien"], "productName": n["sp"], "quantity": n["sl"],
                "unit": n["dvt"], "evidenceRequired": n["mc"], "qualityCriteria": n["cl"], "basis": n["can_cu"],
                "leadPersonId": self.id_nguoi(chu),
                "coAssignees": [{"kind": "PERSON", "id": self.id_nguoi(p), "name": self.ten_nguoi(p)}
                                for p in n["phoi_hop"]],
                "dueDate": ngay(han),
                "milestones": [{"date": ngay(moc), "content": "Báo cáo kết quả bước đầu với người giao việc"}],
            }
            if n["kr"]:
                khoa, so = n["kr"].split("#")
                than["keyResultId"] = self.kr(khoa, int(so))["id"]
            nv, _ = goi(t_giao, "POST", "/tasks", than)
            print("  Đã soạn nhiệm vụ: %s" % ten)
        if dich == "NHAP":
            self.dem("Nhiệm vụ (NHAP)", moi)
            return
        if nv["status"] == "NHAP":
            ct, e = self.chi_tiet(t_giao, nv["id"])
            nv, _ = goi(t_giao, "POST", "/tasks/%s/assign" % nv["id"],
                        {"similarReason": "Nhiệm vụ riêng của đơn vị, không trùng việc đã giao"}, if_match=etag(ct, e))
            print("  Đã giao nhiệm vụ: %s" % ten)
        ct, e = self.chi_tiet(t_giao, nv["id"])
        # Giao lại cho người khác (người giao, nhiệm vụ mới giao)
        gl = n.get("giao_lai")
        if gl and ct["status"] == "DA_GIAO":
            ds, _ = goi(t_giao, "GET", "/tasks/%s/assignees" % nv["id"])
            chu_tri = next((a for a in (ds.get("items") if isinstance(ds, dict) else ds)
                            if a.get("role") == "CHU_TRI"), None)
            if chu_tri and chu_tri["id"] != self.id_nguoi(gl["cho"]):
                goi(t_giao, "POST", "/tasks/%s/reassign" % nv["id"],
                    {"leadPersonId": self.id_nguoi(gl["cho"]), "reason": gl["ly_do"]}, if_match=etag(ct, e))
                self.dem("Giao lại nhiệm vụ", True)
                print("  Đã giao lại cho %s: %s" % (gl["cho"], ten))
                ct, e = self.chi_tiet(t_giao, nv["id"])
            else:
                self.dem("Giao lại nhiệm vụ", False)
            chu = gl["cho"]
        # Nhắc việc (người giao; cùng người cùng nhiệm vụ trong 24 giờ chỉ nhắc một lần)
        if n.get("nhac") and ct["status"] in ("DA_GIAO", "DANG_THUC_HIEN"):
            try:
                goi(t_giao, "POST", "/tasks/%s/reminders" % nv["id"], {"note": n["nhac"]})
                self.dem("Nhắc việc", True)
                print("  Đã nhắc việc: %s" % ten)
            except LoiApi as ex:
                if ex.code != 429:
                    raise
                self.dem("Nhắc việc", False)
        if dich == "DA_GIAO":
            self.dem("Nhiệm vụ (DA_GIAO)", moi)
            return
        t_chu = self.tok(chu)
        # Nhận việc, cập nhật tiến độ (người chủ trì)
        ct, e = self.chi_tiet(t_chu, nv["id"])
        if ct["status"] == "DA_GIAO":
            ct, e = goi(t_chu, "POST", "/tasks/%s/accept" % nv["id"], None, if_match=etag(ct, e))
            print("  Đã nhận việc: %s" % ten)
        if ct["status"] == "DANG_THUC_HIEN" and n["tien_do"] and (ct.get("progress") or 0) < n["tien_do"]:
            ct, e = self.chi_tiet(t_chu, nv["id"])
            than = {"percent": n["tien_do"], "note": n["ghi_chu"] or "Cập nhật tiến độ.", "channel": "WEB"}
            if n["tien_do"] == 100:
                than["doneQuantity"] = n["sl"]
            goi(t_chu, "POST", "/tasks/%s/progress" % nv["id"], than, if_match=etag(ct, e))
            print("  Đã cập nhật tiến độ %d%%: %s" % (n["tien_do"], ten))
        # Điểm nghẽn: người chủ trì báo, người giao nhận xử lý và ghi kết quả
        if n.get("nghen"):
            self.nap_nghen(n, nv["id"], t_chu, t_giao)
        # Đề nghị điều chỉnh: người chủ trì gửi, người giao quyết định
        if n.get("de_nghi"):
            self.nap_de_nghi(n, nv["id"], t_chu, t_giao)
        # Trình nghiệm thu, nghiệm thu
        ct, e = self.chi_tiet(t_chu, nv["id"])
        if dich in ("CHO_NGHIEM_THU", "HOAN_THANH") and ct["status"] == "DANG_THUC_HIEN":
            # Guard trình nghiệm thu: khối lượng đã làm phải đủ kế hoạch (chưa có sản phẩm nộp thì lấy doneQuantity).
            if float(ct.get("doneQuantity") or 0) < float(n["sl"]):
                goi(t_chu, "POST", "/tasks/%s/progress" % nv["id"],
                    {"percent": max(int(ct.get("progress") or 0), n["tien_do"] or 100), "doneQuantity": n["sl"],
                     "note": "Đã hoàn thành đủ khối lượng sản phẩm theo kế hoạch.", "channel": "WEB"},
                    if_match=etag(ct, e))
                ct, e = self.chi_tiet(t_chu, nv["id"])
            ct, e = self.chuyen(t_chu, nv["id"], "TRINH_NGHIEM_THU", "Đã hoàn thành sản phẩm, đề nghị nghiệm thu.",
                                etag(ct, e))
            print("  Đã trình nghiệm thu: %s" % ten)
        if dich == "HOAN_THANH" and ct["status"] == "CHO_NGHIEM_THU":
            ct, e = self.chi_tiet(t_giao, nv["id"])
            ct, e = self.chuyen(t_giao, nv["id"], "HOAN_THANH", "Sản phẩm đạt yêu cầu, nghiệm thu hoàn thành.",
                                etag(ct, e))
            print("  Đã nghiệm thu hoàn thành: %s" % ten)
        ct, _ = self.chi_tiet(t_giao, nv["id"])
        self.dem("Nhiệm vụ (%s)" % ct["status"], moi)
        if ct["status"] != dich:
            raise RuntimeError("Nhiệm vụ '%s' đang ở %s, chưa tới trạng thái đích %s" % (ten[:40], ct["status"], dich))

    def chuyen(self, tok, nv_id, lenh, ly_do, if_match):
        """Chuyển trạng thái; khi máy chủ từ chối vì guard, kèm số liệu nhiệm vụ để biết thiếu điều kiện nào."""
        try:
            return goi(tok, "POST", "/tasks/%s/transitions" % nv_id, {"transition": lenh, "reason": ly_do},
                       if_match=if_match)
        except LoiApi as ex:
            if ex.code != 422:
                raise
            ct, _ = self.chi_tiet(tok, nv_id)
            raise RuntimeError("%s (nhiệm vụ đang %s, tiến độ %s%%, đã làm %s/%s %s)" % (
                ex, ct.get("status"), ct.get("progress"), ct.get("doneQuantity"), ct.get("plannedQuantity"),
                ct.get("unit") or ""))

    def nap_nghen(self, n, nv_id, t_chu, t_giao):
        ng = n["nghen"]
        ct, _ = self.chi_tiet(t_chu, nv_id)
        b = next((x for x in ct.get("blockers") or [] if x.get("content") == ng["noi_dung"]), None)
        if not b:
            if ct["status"] not in ("DA_GIAO", "DANG_THUC_HIEN"):
                self.dem("Điểm nghẽn", False)
                return
            han = dt.date.today() + dt.timedelta(days=ng["so_ngay"])
            b, _ = goi(t_chu, "POST", "/tasks/%s/blockers" % nv_id, {
                "content": ng["noi_dung"], "causeCategory": ng["nhom"], "causeClaimed": ng["nguyen_nhan"],
                "impactLevel": ng["muc"], "resolveBy": ngay(han)})
            self.dem("Điểm nghẽn", True)
            print("  Đã báo điểm nghẽn: %s" % ng["noi_dung"][:50])
        else:
            self.dem("Điểm nghẽn", False)
        if not ng.get("xu_ly"):
            return
        if b.get("status") == "MO":
            han = dt.date.today() + dt.timedelta(days=ng["so_ngay"])
            b, _ = goi(t_giao, "PATCH", "/tasks/%s/blockers/%s" % (nv_id, b["id"]),
                       {"handlerPersonId": self.id_nguoi(n["giao"]), "resolveBy": ngay(han)}, if_match=etag(b))
            print("  Người giao đã nhận xử lý điểm nghẽn")
        if ng["xu_ly"] == "DA_GIAI_QUYET" and b.get("status") == "DANG_XU_LY":
            goi(t_giao, "PATCH", "/tasks/%s/blockers/%s" % (nv_id, b["id"]),
                {"status": "DA_GIAI_QUYET", "resolutionNote": ng["ket_qua"]}, if_match=etag(b))
            print("  Đã giải quyết điểm nghẽn")

    def nap_de_nghi(self, n, nv_id, t_chu, t_giao):
        dn = n["de_nghi"]
        ct, _ = self.chi_tiet(t_chu, nv_id)
        r = next((x for x in ct.get("changeRequests") or [] if x.get("type") == dn["loai"]), None)
        if not r:
            if ct["status"] not in ("DA_GIAO", "DANG_THUC_HIEN"):
                self.dem("Đề nghị điều chỉnh", False)
                return
            than = {"type": dn["loai"], "causeClaimed": dn["nguyen_nhan"], "reason": dn["ly_do"]}
            if dn["loai"] == "GIA_HAN":
                han = dt.datetime.strptime(ct["dueDate"][:10], "%Y-%m-%d").date() + dt.timedelta(days=dn["them_ngay"])
                cuoi_ky = dt.datetime.strptime(self.ky["endDate"][:10], "%Y-%m-%d").date()
                than["newDueDate"] = ngay(min(han, cuoi_ky))
            elif dn["loai"] == "DOI_SO_LUONG":
                than["newQuantity"] = dn["sl_moi"]
            elif dn["loai"] == "DOI_SAN_PHAM":
                than["newProduct"] = dn["sp_moi"]
            try:
                kq, _ = goi(t_chu, "POST", "/tasks/%s/change-requests" % nv_id, than)
            except LoiApi as ex:
                if ex.code == 409:
                    # Đề nghị cùng loại đang chờ duyệt từ lần nạp trước (chi tiết nhiệm vụ không liệt kê đề nghị).
                    self.dem("Đề nghị điều chỉnh", False)
                    return
                raise
            self.dem("Đề nghị điều chỉnh", True)
            print("  Đã gửi đề nghị %s: %s" % (dn["loai"], n["ten"][:40]))
            r = next((x for x in (kq or {}).get("changeRequests") or [] if x.get("type") == dn["loai"]), None)
        else:
            self.dem("Đề nghị điều chỉnh", False)
        if dn.get("quyet_dinh") is None or not r or r.get("status") != "CHO_DUYET":
            return
        goi(t_giao, "POST", "/tasks/%s/change-requests/%s/decision" % (nv_id, r["id"]),
            {"approve": bool(dn["quyet_dinh"]), "causeDecided": dn["nguyen_nhan_xd"], "note": dn["y_kien"]})
        print("  Người giao đã %s đề nghị %s" % ("duyệt" if dn["quyet_dinh"] else "từ chối", dn["loai"]))

    # 5. Kế hoạch quý --------------------------------------------------------------------------------------------
    def nap_ke_hoach(self, kh):
        t_lap, t_duyet = self.tok(kh["lap"]), self.tok(kh["duyet"])
        q = urllib.parse.urlencode({"period": self.ky["id"], "unit": self.u(kh["dv"]), "limit": 50})
        ds, _ = goi(t_lap, "GET", "/plans?" + q)
        plan = next((p for p in ds["items"] if p.get("unitId") == self.u(kh["dv"])), None)
        moi = plan is None
        if not plan:
            plan, _ = goi(t_lap, "POST", "/plans", {"periodId": self.ky["id"], "unitId": self.u(kh["dv"])})
            print("  Đã lập kế hoạch quý của đơn vị %s" % kh["dv"])
        cv = plan.get("currentVersion") or {}
        v = cv.get("versionNo", 1) if isinstance(cv, dict) else (cv or 1)
        ver, e = goi(t_lap, "GET", "/plans/%s/versions/%s" % (plan["id"], v))
        trang_thai = ver.get("status")
        if trang_thai in ("NHAP", "TRA_LAI") and not ver.get("items") and kh["dich"] != "NHAP" or (
                trang_thai == "NHAP" and not ver.get("items")):
            ver, e = goi(t_lap, "PUT", "/plans/%s/versions/%s" % (plan["id"], v), {"items": self.dong_ke_hoach(kh)},
                         if_match=etag(ver, e))
            print("  Đã nhập %d dòng kế hoạch" % len(kh["dong"]))
            trang_thai = ver.get("status")
        if kh["dich"] == "NHAP":
            self.dem("Kế hoạch quý (%s)" % trang_thai, moi)
            return
        if trang_thai == "NHAP":
            ver, e = goi(t_lap, "POST", "/plans/%s/versions/%s/submit" % (plan["id"], v), None, if_match=etag(ver, e))
            trang_thai = ver.get("status")
            print("  Đã gửi duyệt kế hoạch")
        if kh["dich"] == "TRA_LAI" and trang_thai == "CHO_DUYET":
            ver, e = goi(t_duyet, "GET", "/plans/%s/versions/%s" % (plan["id"], v))
            ver, e = goi(t_duyet, "POST", "/plans/%s/versions/%s/return" % (plan["id"], v),
                         {"comment": kh["y_kien"]}, if_match=etag(ver, e))
            trang_thai = ver.get("status")
            print("  Đã trả lại kế hoạch")
        if kh["dich"] == "DA_DUYET" and trang_thai == "CHO_DUYET":
            ver, e = goi(t_duyet, "GET", "/plans/%s/versions/%s" % (plan["id"], v))
            try:
                goi(t_duyet, "POST", "/plans/%s/versions/%s/approve" % (plan["id"], v),
                    {"signMethod": "MYSIGN", "comment": "Đồng ý kế hoạch, đề nghị triển khai."}, if_match=etag(ver, e))
                print("  Đã gửi duyệt kèm ký số kế hoạch (tác vụ nền)")
                trang_thai = "DA_DUYET (đang ký)"
            except LoiApi as ex:
                if ex.code == 422 and "định điểm" in ex.body:
                    # Bản chờ duyệt còn dòng ngoài danh mục từ lần nạp trước: trả lại, nhập lại dòng theo nguồn, trình, duyệt.
                    ver, e = goi(t_duyet, "POST", "/plans/%s/versions/%s/return" % (plan["id"], v),
                                 {"comment": "Dòng ngoài danh mục chưa định điểm, đề nghị thay bằng dòng danh mục."},
                                 if_match=etag(ver, e))
                    ver, e = goi(t_lap, "GET", "/plans/%s/versions/%s" % (plan["id"], v))
                    ver, e = goi(t_lap, "PUT", "/plans/%s/versions/%s" % (plan["id"], v),
                                 {"items": self.dong_ke_hoach(kh)}, if_match=etag(ver, e))
                    ver, e = goi(t_lap, "POST", "/plans/%s/versions/%s/submit" % (plan["id"], v), None,
                                 if_match=etag(ver, e))
                    ver, e = goi(t_duyet, "GET", "/plans/%s/versions/%s" % (plan["id"], v))
                    goi(t_duyet, "POST", "/plans/%s/versions/%s/approve" % (plan["id"], v),
                        {"signMethod": "MYSIGN", "comment": "Đồng ý kế hoạch, đề nghị triển khai."}, if_match=etag(ver, e))
                    print("  Đã thay dòng ngoài danh mục, trình lại và gửi duyệt kèm ký số kế hoạch")
                    trang_thai = "DA_DUYET (đang ký)"
                elif ex.code == 503:
                    # Duyệt kế hoạch mở saga ký số qua esign-service; môi trường dev chưa triển khai dịch vụ này nên
                    # kế hoạch dừng ở chờ duyệt. Không coi là lỗi của bộ nạp; khi có dịch vụ, lần nạp sau sẽ duyệt.
                    print("  Chưa duyệt được kế hoạch: dịch vụ ký số chưa sẵn sàng trên môi trường này")
                    trang_thai = "CHO_DUYET, chờ dịch vụ ký số"
                else:
                    self.dem("Kế hoạch quý (%s)" % trang_thai, moi)
                    raise RuntimeError("Duyệt kế hoạch cần ký số và xác thực bước lên trên dev: %s" % ex)
        self.dem("Kế hoạch quý (%s)" % trang_thai, moi)

    def dong_ke_hoach(self, kh):
        hom = dt.date.today()
        cuoi_ky = dt.datetime.strptime(self.ky["endDate"][:10], "%Y-%m-%d").date()
        muc = {m[0]: m for m in self.t["danh_muc"]["muc"]}
        out = []
        for d in kh["dong"]:
            han = min(hom + dt.timedelta(days=d["so_ngay"]), cuoi_ky)
            dong = {"title": d["ten"], "taskGroup": d["nhom"],
                    "leadAssignee": {"kind": "PERSON", "id": self.id_nguoi(d["chu"]), "name": self.ten_nguoi(d["chu"])},
                    "coAssignees": [], "productName": d["sp"], "quantity": d["sl"], "unit": d["dvt"],
                    "dueDate": ngay(han)}
            if d.get("muc"):
                dong["catalogItemId"] = self.u(d["muc"])
                dong["productName"] = muc[d["muc"]][3]
            else:
                dong["standardScore"] = d["diem"]
            if d.get("kr"):
                khoa, so = d["kr"].split("#")
                if khoa in self.muc_tieu:
                    dong["keyResultId"] = self.kr(khoa, int(so))["id"]
            out.append(dong)
        return out

    # 6. Uỷ quyền, phân quyền ------------------------------------------------------------------------------------
    def nap_uy_quyen(self, u):
        tok = self.tok(u["boi"])
        ds, _ = goi(tok, "GET", "/config/delegations?limit=200")
        items = ds.get("items", ds) if isinstance(ds, dict) else ds
        co = next((x for x in items if x.get("kind") == u["loai"]
                   and ref_id(x.get("delegator")) == self.id_nguoi(u["nguoi_uy_quyen"])
                   and ref_id(x.get("delegate")) == self.id_nguoi(u["nguoi_nhan"])
                   and x.get("status") in (None, "HIEU_LUC", "SAP_HIEU_LUC")), None)
        if co:
            self.dem("Uỷ quyền", False)
            return
        hom = dt.date.today()
        than = {"kind": u["loai"], "delegatorId": self.id_nguoi(u["nguoi_uy_quyen"]),
                "delegateId": self.id_nguoi(u["nguoi_nhan"]), "orgUnitId": self.u(u["dv"]),
                "validFrom": gio_utc(hom), "validTo": gio_utc(hom + dt.timedelta(days=u["so_ngay"]), 10),
                "reason": u["ly_do"]}
        if u.get("kr"):
            khoa, so = u["kr"].split("#")
            than["subjectId"] = self.kr(khoa, int(so))["id"]
        goi(tok, "POST", "/config/delegations", than)
        self.dem("Uỷ quyền", True)
        print("  Đã uỷ quyền %s: %s → %s" % (u["loai"], u["nguoi_uy_quyen"], u["nguoi_nhan"]))

    def nap_phan_quyen(self, pq):
        tok = self.tok(pq["boi"])
        q = urllib.parse.urlencode({"personId": self.id_nguoi(pq["nguoi"]), "limit": 200})
        ds, _ = goi(tok, "GET", "/config/role-bindings?" + q)
        items = ds.get("items", ds) if isinstance(ds, dict) else ds
        b = next((x for x in items if x.get("roleCode") == pq["vai_tro"] and ref_id(x.get("orgUnit")) == self.u(pq["dv"])
                  and ref_id(x.get("person")) == self.id_nguoi(pq["nguoi"])
                  and x.get("status") not in ("TU_CHOI", "DA_THU_HOI", "HET_HAN")), None)
        if not b:
            hom = dt.date.today()
            b, _ = goi(tok, "POST", "/config/role-bindings", {
                "personId": self.id_nguoi(pq["nguoi"]), "roleCode": pq["vai_tro"], "orgUnitId": self.u(pq["dv"]),
                "scope": pq["pham_vi"], "flow": None, "validFrom": gio_utc(hom),
                "validTo": gio_utc(hom + dt.timedelta(days=pq["so_ngay"]), 10), "reason": pq["ly_do"]})
            self.dem("Phân quyền", True)
            print("  Đã cấp vai trò %s cho %s (chờ duyệt)" % (pq["vai_tro"], pq["nguoi"]))
        else:
            self.dem("Phân quyền", False)
        if pq.get("duyet") and b.get("status") == "CHO_DUYET":
            t_duyet = self.tok(pq["duyet"])
            b2, e = goi(t_duyet, "GET", "/config/role-bindings/%s" % b["id"])
            goi(t_duyet, "POST", "/config/role-bindings/%s/approve" % b["id"], {}, if_match=etag(b2, e))
            print("  %s đã duyệt vai trò %s cho %s" % (pq["duyet"], pq["vai_tro"], pq["nguoi"]))

    # 7. Kỳ trước ---------------------------------------------------------------------------------------------------
    def nap_ky_truoc(self):
        kt = D.KY_TRUOC[self.t["ma"]]
        hom = dt.date.today()
        q = (hom.month - 1) // 3 + 1
        nam, q_truoc = (hom.year - 1, 4) if q == 1 else (hom.year, q - 1)
        self.ky_truoc = self.tim_hoac_tao_ky(nam, q_truoc, "MO")
        print("  Kỳ trước: %s (%s)" % (self.ky_truoc["name"], self.ky_truoc.get("status")))
        for o in kt["okr"]:
            self.buoc("Kỳ trước, mục tiêu: " + o["ten"][:40],
                      lambda o=o: self.nap_muc_tieu(o, self.ky_truoc, self.muc_tieu_truoc))
        for o in kt["okr"]:
            self.buoc("Kỳ trước, check-in: " + o["ten"][:40],
                      lambda o=o: self.nap_check_in(o, kt["okr"], self.muc_tieu_truoc))
        self.buoc("Kỳ trước, lệnh khoá kỳ", lambda: self.nap_lenh_khoa(kt))

    def nap_lenh_khoa(self, kt):
        t_qt = self.tok(self.quan_tri())
        ky, _ = goi(t_qt, "GET", "/periods/%s" % self.ky_truoc["id"])
        if ky.get("status") not in (None, "MO", "MO_LAI_HAU_KIEM"):
            self.dem("Lệnh khoá kỳ", False)
            print("  Kỳ trước đang ở trạng thái %s, không lập lệnh khoá" % ky.get("status"))
            return
        lenh = ky.get("currentLockOrder")
        if not lenh:
            lenh, _ = goi(t_qt, "POST", "/periods/%s/lock-orders" % ky["id"], {"basisDoc": kt["can_cu"]})
            self.dem("Lệnh khoá kỳ", True)
            print("  Đã lập lệnh khoá kỳ %s" % ky["name"])
        else:
            self.dem("Lệnh khoá kỳ", False)
        if lenh.get("status") == "CHO_DUYET":
            t_duyet = self.tok(kt["duyet"])
            goi(t_duyet, "POST", "/periods/%s/lock-orders/%s/approve" % (ky["id"], lenh["id"]),
                {"comment": "Đồng ý khoá kỳ theo kế hoạch."}, if_match=etag(lenh))
            print("  %s đã phê duyệt lệnh khoá kỳ" % kt["duyet"])

    # 8. UI Pack ----------------------------------------------------------------------------------------------------
    def nap_ui_pack(self):
        if sys.version_info < (3, 10):
            # Máy chủ dev chạy Python 3.6; UI Pack là lớp giao diện theo tenant (ADR-05), không chặn dữ liệu nghiệp vụ.
            print("  Bỏ qua UI Pack: cần Python 3.10 trở lên (máy này chạy %d.%d)" % sys.version_info[:2])
            self.dem("UI Pack (bỏ qua, cần Python 3.10)", False)
            return
        qt = [c["tk"] for c in self.t["can_bo"] if "tenant_admin" in c["vai_tro"]]
        env = dict(os.environ, MISSION_API=API, MISSION_TOKEN=self.tok(qt[0]))
        if len(qt) > 1:
            env["MISSION_TOKEN_DUYET"] = self.tok(qt[1])
        kq = subprocess.run([sys.executable, "-I", str(UI_PACK)], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True)
        for dong in (kq.stdout or "").splitlines():
            print("  " + dong)
        if kq.returncode != 0:
            raise RuntimeError((kq.stderr or kq.stdout).strip()[:300])
        self.dem("UI Pack", True)

    # Điều phối ----------------------------------------------------------------------------------------------------
    def chay(self):
        self.buoc("Kỳ hiện hành", self.nap_ky)
        if not self.ky:
            return
        okr = D.OKR[self.t["ma"]]
        for o in okr:
            self.buoc("Mục tiêu: " + o["ten"][:40], lambda o=o: self.nap_muc_tieu(o, self.ky, self.muc_tieu))
        for o in okr:
            self.buoc("Check-in: " + o["ten"][:40], lambda o=o: self.nap_check_in(o, okr, self.muc_tieu))
        for n in D.NHIEM_VU[self.t["ma"]]:
            self.buoc("Nhiệm vụ: " + n["ten"][:40], lambda n=n: self.nap_nhiem_vu(n))
        for kh in D.KE_HOACH[self.t["ma"]]:
            self.buoc("Kế hoạch quý đơn vị %x" % kh["dv"], lambda kh=kh: self.nap_ke_hoach(kh))
        for u in D.UY_QUYEN[self.t["ma"]]:
            self.buoc("Uỷ quyền %s" % u["loai"], lambda u=u: self.nap_uy_quyen(u))
        for pq in D.PHAN_QUYEN[self.t["ma"]]:
            self.buoc("Phân quyền %s cho %s" % (pq["vai_tro"], pq["nguoi"]), lambda pq=pq: self.nap_phan_quyen(pq))
        self.buoc("Kỳ trước", self.nap_ky_truoc)
        self.buoc("UI Pack", self.nap_ui_pack)


def main():
    env = _base.nap_env()
    chon = sys.argv[1:] or [t["ma"] for t in D.TENANT]
    phien = Phien(env)
    tk = ThongKe()
    loi = 0
    for t in D.TENANT:
        if t["ma"] not in chon:
            continue
        print("==> %s" % t["ten"])
        nap = NapTenant(t, phien, tk)
        try:
            nap.chay()
        except (LoiApi, RuntimeError, KeyError, StopIteration) as e:
            nap.loi += 1
            tk.ghi_loi(t["ten_ngan"], "chung", e)
            print("  LỖI: %s" % e, file=sys.stderr)
        loi += nap.loi
    tk.in_bang()
    if loi:
        print("\nCó %d bước lỗi; xem các dòng LỖI ở trên." % loi, file=sys.stderr)
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
