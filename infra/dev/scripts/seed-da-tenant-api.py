#!/usr/bin/env python3
"""Nạp phần nghiệp vụ của bộ dữ liệu thử đa tenant qua API thật (APISIX → mission-core), như người dùng thao tác.

Chạy sau `seed-da-tenant.sh` phần SQL, tài khoản Keycloak và đồng bộ tổ chức. Với từng tenant trong
`data/seed/da-tenant/du_lieu.py`:
  1. Quản trị tenant tạo kỳ quý hiện hành nếu chưa có kỳ đang mở bao trùm hôm nay (luồng theo tenant).
  2. Người soạn tạo các bộ OKR theo cây phân rã, trình; người duyệt phê duyệt (trừ bộ cố ý để chờ duyệt).
  3. Chủ sở hữu KR cá nhân gửi check-in đầu tiên.
  4. Người quản lý giao nhiệm vụ; người chủ trì nhận việc và cập nhật tiến độ với việc đã bắt đầu làm.
Chạy lại không sinh trùng: bản ghi nhận theo tên, bước đã xong thì bỏ qua, bước dở thì đi tiếp. Lỗi ở một tenant
không chặn tenant khác; mã thoát 1 nếu có lỗi. Chỉ dùng thư viện chuẩn, tương thích Python 3.6, không in token.

    python3 -I infra/dev/scripts/seed-da-tenant-api.py            # cả ba tenant
    python3 -I infra/dev/scripts/seed-da-tenant-api.py dang cq    # chỉ một số tenant (dang, cq, dn)

Biến môi trường như seed-ky-nhiem-vu.py: SEED_API, KEYCLOAK_PUBLIC_URL, KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET
(đọc thêm từ infra/dev/.env).
"""

import datetime as dt
import importlib.util
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_ky_nhiem_vu", str(HERE / "seed-ky-nhiem-vu.py"))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
_spec2 = importlib.util.spec_from_file_location("du_lieu", str(HERE.parents[2] / "data/seed/da-tenant/du_lieu.py"))
D = importlib.util.module_from_spec(_spec2)
_spec2.loader.exec_module(D)

API = _base.API
LA_MA = {1: "I", 2: "II", 3: "III", 4: "IV"}


class LoiApi(Exception):
    def __init__(self, method, path, code, body):
        Exception.__init__(self, "%s %s → %s: %s" % (method, path, code, body[:400]))
        self.code = code


class Phien:
    """Token theo tài khoản: đăng nhập một lần cho mỗi tài khoản (mã OTP không dùng lại được trong cùng 30 giây)."""

    def __init__(self, env):
        self.env = env
        self.tok = {}

    def __call__(self, tk):
        t = self.tok.get(tk)
        if t and _base.claims(t).get("exp", 0) > time.time() + 60:
            return t
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


def goi(tok, method, path, body=None, if_match=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/json")
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if method in ("POST", "PUT", "PATCH", "DELETE"):
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    if if_match:
        req.add_header("If-Match", if_match)
    try:
        with urllib.request.urlopen(req, data=data, timeout=60) as r:
            raw = r.read()
            return (json.loads(raw.decode("utf-8")) if raw else None), r.headers.get("ETag")
    except urllib.error.HTTPError as e:
        raise LoiApi(method, path, e.code, e.read().decode("utf-8", "replace"))


def etag(o, e):
    return e or '"%s"' % o["rowVersion"]


class NapTenant:
    def __init__(self, t, phien):
        self.t = t
        self.p = phien
        self.cb = {c["tk"]: c for c in t["can_bo"]}
        self.ky = None
        self.muc_tieu = {}

    def u(self, so):
        return D.uid(self.t["tien_to"], so)

    def id_nguoi(self, tk):
        return self.u(self.cb[tk]["so"])

    def tok(self, tk):
        return self.p(tk)

    def quan_tri(self):
        return next(c["tk"] for c in self.t["can_bo"] if "tenant_admin" in c["vai_tro"])

    # 1. Kỳ quý hiện hành --------------------------------------------------------------------------------------
    def nap_ky(self):
        tok = self.tok(self.quan_tri())
        hom = dt.date.today()
        ds, _ = goi(tok, "GET", "/periods?status=MO&limit=200")
        self.ky = next((k for k in ds["items"] if k["startDate"] <= hom.isoformat() <= k["endDate"]), None)
        if self.ky:
            print("  Kỳ đang mở: %s" % self.ky["name"])
            return
        q = (hom.month - 1) // 3 + 1
        dau = dt.date(hom.year, 3 * q - 2, 1)
        cuoi = dt.date(hom.year + (q == 4), 1 if q == 4 else 3 * q + 1, 1) - dt.timedelta(days=1)
        self.ky, _ = goi(tok, "POST", "/periods", {
            "code": "%d-Q%d" % (hom.year, q), "name": "Quý %s/%d" % (LA_MA[q], hom.year), "kind": "QUY",
            "flow": self.t["luong_ky"], "startDate": dau.isoformat(), "endDate": cuoi.isoformat()})
        print("  Đã tạo kỳ: %s" % self.ky["name"])

    # 2, 3. OKR và check-in ------------------------------------------------------------------------------------
    def tim_muc_tieu(self, tok, dv, ten):
        q = urllib.parse.urlencode({"period": self.ky["id"], "unit": self.u(dv), "view": "table", "limit": 200})
        ds, _ = goi(tok, "GET", "/okr/objectives?" + q)
        return next((o for o in ds["items"] if o["title"] == ten), None)

    def nap_muc_tieu(self, o):
        t_soan, t_duyet = self.tok(o["soan"]), self.tok(o["duyet"])
        dich = o.get("dich", "DA_DUYET")
        mt = self.tim_muc_tieu(t_soan, o["dv"], o["ten"])
        if not mt:
            than = {
                "periodId": self.ky["id"], "ownerType": o["loai"], "ownerUnitId": self.u(o["dv"]), "title": o["ten"],
                "basisIds": [self.u(b) for b in o["can_cu"]],
                "keyResults": [{
                    "title": k[0], "metricType": k[1], "unit": k[2], "baselineValue": k[3], "targetValue": k[4],
                    "direction": k[5], "weight": k[6], "verifierPersonId": self.id_nguoi(k[7]),
                    "checkinFrequency": "TUAN"} for k in o["kr"]],
            }
            if o["loai"] == "CA_NHAN":
                than["ownerPersonId"] = self.id_nguoi(o["soan"])
            if o.get("cha"):
                than["parentObjectiveId"] = self.muc_tieu[o["cha"]]["id"]
            mt, _ = goi(t_soan, "POST", "/okr/objectives", than)
            print("  Đã soạn mục tiêu: %s" % o["ten"])
        if mt["status"] in ("NHAP", "TRA_LAI"):
            mt, e = goi(t_soan, "GET", "/okr/objectives/%s" % mt["id"])
            mt, _ = goi(t_soan, "POST", "/okr/objectives/%s/submit" % mt["id"], {}, if_match=etag(mt, e))
            print("  Đã trình mục tiêu: %s" % o["ten"])
        if dich == "DA_DUYET" and mt["status"] == "CHO_DUYET":
            mt, e = goi(t_duyet, "GET", "/okr/objectives/%s" % mt["id"])
            goi(t_duyet, "POST", "/okr/objectives/%s/approve" % mt["id"],
                {"comment": "Đồng ý, đề nghị triển khai thực hiện.", "notifyPeers": False}, if_match=etag(mt, e))
            print("  Đã phê duyệt mục tiêu: %s" % o["ten"])
        mt, _ = goi(t_soan, "GET", "/okr/objectives/%s" % mt["id"])
        self.muc_tieu[o["khoa"]] = mt

    def kr(self, khoa, thu_tu):
        o = next(x for x in D.OKR[self.t["ma"]] if x["khoa"] == khoa)
        ten = o["kr"][thu_tu - 1][0]
        return next(k for k in self.muc_tieu[khoa]["keyResults"] if k["title"] == ten)

    def nap_check_in(self, o):
        if o["loai"] != "CA_NHAN" or o.get("dich", "DA_DUYET") != "DA_DUYET":
            return
        tok = self.tok(o["soan"])
        for i, k in enumerate(o["kr"], 1):
            if k[8] is None:
                continue
            kr = self.kr(o["khoa"], i)
            ds, _ = goi(tok, "GET", "/okr/key-results/%s/checkins?limit=20" % kr["id"])
            if any(c.get("status") == "DA_GUI" for c in ds.get("items", [])):
                continue
            than = {"status": "DA_GUI", "value": k[8], "confidence": k[9], "channel": "WEB",
                    "confirmLargeChange": True,
                    "highlights": "Cập nhật tiến độ tuần này theo số liệu đã đối chiếu với người xác nhận."}
            if k[9] == "THAP":
                than["blockerNote"] = "Cần lãnh đạo hỗ trợ đôn đốc các đơn vị phối hợp."
            goi(tok, "POST", "/okr/key-results/%s/checkins" % kr["id"], than)
            print("  Đã check-in: %s = %s" % (k[0], k[8]))

    # 4. Nhiệm vụ ----------------------------------------------------------------------------------------------
    def nap_nhiem_vu(self, n):
        (giao, chu, ten, nhom, uu_tien, sp, sl, dvt, cl, mc, can_cu, so_ngay, kr, phoi_hop, tien_do, ghi_chu) = n
        t_giao, t_chu = self.tok(giao), self.tok(chu)
        q = urllib.parse.urlencode({"period": self.ky["id"], "unit": self.u(self.cb[giao]["dv"]), "limit": 200})
        ds, _ = goi(t_giao, "GET", "/tasks?" + q)
        nv = next((v for v in ds["items"] if v["title"] == ten), None)
        if not nv:
            hom = dt.date.today()
            cuoi_ky = dt.datetime.strptime(self.ky["endDate"][:10], "%Y-%m-%d").date()
            han = min(hom + dt.timedelta(days=so_ngay), cuoi_ky)
            moc = min(hom + dt.timedelta(days=max(1, so_ngay // 2)), han)
            than = {
                "source": "DOT_XUAT", "status": "NHAP", "periodId": self.ky["id"], "unitName": "",
                "assignerId": self.id_nguoi(giao), "assignerName": self.cb[giao]["ten"], "title": ten,
                "taskGroup": nhom, "priority": uu_tien, "productName": sp, "quantity": sl, "unit": dvt,
                "evidenceRequired": mc, "qualityCriteria": cl, "basis": can_cu, "leadPersonId": self.id_nguoi(chu),
                "coAssignees": [{"kind": "PERSON", "id": self.id_nguoi(p), "name": self.cb[p]["ten"]} for p in phoi_hop],
                "dueDate": han.isoformat(),
                "milestones": [{"date": moc.isoformat(), "content": "Báo cáo kết quả bước đầu với người giao việc"}],
            }
            if kr:
                khoa, so = kr.split("#")
                than["keyResultId"] = self.kr(khoa, int(so))["id"]
            nv, _ = goi(t_giao, "POST", "/tasks", than)
            print("  Đã soạn nhiệm vụ: %s" % ten)
        if nv["status"] == "NHAP":
            ct, e = goi(t_giao, "GET", "/tasks/%s" % nv["id"])
            nv, _ = goi(t_giao, "POST", "/tasks/%s/assign" % nv["id"],
                        {"similarReason": "Nhiệm vụ riêng của đơn vị, không trùng việc đã giao"}, if_match=etag(ct, e))
            print("  Đã giao nhiệm vụ: %s" % ten)
        if tien_do is None:
            return
        ct, e = goi(t_chu, "GET", "/tasks/%s" % nv["id"])
        if ct["status"] == "DA_GIAO":
            ct, e = goi(t_chu, "POST", "/tasks/%s/accept" % nv["id"], None, if_match=etag(ct, e))
            print("  Đã nhận việc: %s" % ten)
        if (ct.get("progress") or 0) < tien_do:
            ct, e = goi(t_chu, "GET", "/tasks/%s" % nv["id"])
            goi(t_chu, "POST", "/tasks/%s/progress" % nv["id"], {"percent": tien_do, "note": ghi_chu, "channel": "WEB"},
                if_match=etag(ct, e))
            print("  Đã cập nhật tiến độ %d%%: %s" % (tien_do, ten))

    def chay(self):
        self.nap_ky()
        for o in D.OKR[self.t["ma"]]:
            self.nap_muc_tieu(o)
        for o in D.OKR[self.t["ma"]]:
            self.nap_check_in(o)
        for n in D.NHIEM_VU[self.t["ma"]]:
            self.nap_nhiem_vu(n)


def main():
    env = _base.nap_env()
    chon = sys.argv[1:] or [t["ma"] for t in D.TENANT]
    phien = Phien(env)
    loi = 0
    for t in D.TENANT:
        if t["ma"] not in chon:
            continue
        print("==> %s" % t["ten"])
        try:
            NapTenant(t, phien).chay()
        except (LoiApi, RuntimeError, KeyError, StopIteration) as e:
            loi += 1
            print("  LỖI: %s" % e, file=sys.stderr)
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
