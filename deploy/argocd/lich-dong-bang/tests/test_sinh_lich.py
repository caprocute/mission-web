"""Kiểm thử đơn vị sinh_lich.py với kỳ giả lập theo lược đồ PeriodPage của listPeriods (INF-04, OPS-06)."""
import datetime as dt
import io
import json
import os
import sys
import unittest
import urllib.parse

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(GOC, "deploy", "argocd", "lich-dong-bang"))
import sinh_lich  # noqa: E402


def ky(ma, loai, ket_thuc, han_nop=None, khoa=None, trang_thai="MO", han_mc=None):
    k = {
        "id": f"0192f6e7-0000-7f60-8b72-{abs(hash(ma)) % 10**12:012d}",
        "code": ma, "name": ma, "kind": loai, "flow": "CA_HAI", "status": trang_thai,
        "startDate": "2027-01-01", "endDate": ket_thuc, "rowVersion": 1,
        "submissionDeadline": han_nop, "milestones": {},
    }
    if khoa:
        k["milestones"]["plannedLockDate"] = khoa
    if han_mc:
        k["milestones"]["evidenceDeadline"] = han_mc
    return k


TENANT_A = [
    ky("2027-Q1", "QUY", "2027-03-31", "2027-04-08", "2027-04-10"),
    ky("2027-Q2", "QUY", "2027-06-30", "2027-06-28", "2027-07-05"),
    ky("2027-Q3", "QUY", "2027-09-30", "2027-10-03", "2027-10-05"),  # nằm trọn trong cửa sổ cố định quý III
    ky("2027-05", "THANG", "2027-05-31", "2027-06-05"),  # kỳ tháng: không đóng băng
    ky("2026-Q4", "QUY", "2026-12-31", "2027-01-08", "2027-01-04", trang_thai="DA_KHOA"),
]
TENANT_B = [
    ky("2027-Q1", "QUY", "2027-03-31", "2027-04-06", "2027-04-09"),  # chồng với tenant A, phải gộp
    ky("2026-2027", "NAM_HOC", "2027-05-31", None, None, han_mc="2027-05-20"),  # chỉ có hạn minh chứng
    ky("2027", "NAM", "2027-12-31", "2028-01-15", "2028-01-18"),  # thuộc năm sau
]


def nap_luoc_do():
    """PeriodPage của contracts/openapi/modules/config.yaml với $ref tới common.yaml đã được thay thế."""
    thu_muc = os.path.join(GOC, "contracts", "openapi")
    tep = {}
    for ten, duong in (("config", "modules/config.yaml"), ("common", "common/common.yaml")):
        with open(os.path.join(thu_muc, duong), encoding="utf-8") as f:
            tep[ten] = yaml.safe_load(f)

    def giai(nut, nguon):
        if isinstance(nut, dict):
            if "$ref" in nut:
                duong, _, con_tro = nut["$ref"].partition("#")
                moi = "common" if "common.yaml" in duong else nguon
                dich = tep[moi]
                for phan in con_tro.strip("/").split("/"):
                    dich = dich[phan]
                return giai(dich, moi)
            return {k: giai(v, nguon) for k, v in nut.items() if k not in ("examples", "example")}
        if isinstance(nut, list):
            return [giai(x, nguon) for x in nut]
        return nut

    return giai(tep["config"]["components"]["schemas"]["PeriodPage"], "config")


class KiemThuSinhLich(unittest.TestCase):
    def test_du_lieu_gia_lap_dung_luoc_do_listPeriods(self):
        import jsonschema
        luoc_do = nap_luoc_do()
        for ds in (TENANT_A, TENANT_B):
            jsonschema.validate({"items": ds, "nextCursor": None}, luoc_do)

    def test_sinh_va_gop_cua_so(self):
        ds = sinh_lich.sinh({"a": TENANT_A, "b": TENANT_B}, 2027)
        khoang = [(w["_tu"], w["_den"]) for w in ds]
        self.assertEqual(khoang, [
            ("2027-03-30", "2027-04-13"),  # Q1 của A (01/04–13/04) gộp với Q1 của B (30/03–12/04)
            ("2027-05-13", "2027-05-23"),  # năm học: 7 ngày trước hạn minh chứng, 3 ngày sau
            ("2027-06-21", "2027-07-08"),  # Q2: bắt đầu trước cửa sổ cố định 24/6 nên vẫn giữ
        ])
        dau = ds[0]
        self.assertEqual(dau["schedule"], "0 0 30 3 *")
        self.assertEqual(dau["duration"], "360h")  # 15 ngày
        self.assertTrue(dau["manualSync"])
        self.assertEqual(dau["timeZone"], "Asia/Ho_Chi_Minh")

    def test_khoang_nam_tron_trong_cua_so_co_dinh_bi_bo(self):
        ds = sinh_lich.sinh({"a": [TENANT_A[2]]}, 2027)
        self.assertEqual(ds, [])

    def test_tham_so_truoc_sau(self):
        kq = sinh_lich.khoang_cua_ky(TENANT_A[0], truoc=2, sau=0)
        self.assertEqual(kq, (dt.date(2027, 4, 6), dt.date(2027, 4, 10)))

    def test_ghi_values_dung_khoa_va_dang_cua_argocd(self):
        ds = sinh_lich.sinh({"a": TENANT_A}, 2027)
        noi_dung = yaml.safe_load(sinh_lich.ghi_values(ds, 2027, ["a"]))
        self.assertEqual(set(noi_dung), {"syncWindowsBoSung"})
        for w in noi_dung["syncWindowsBoSung"]:
            self.assertEqual(set(w), {"kind", "schedule", "duration", "applications", "manualSync", "timeZone"})
        rong = yaml.safe_load(sinh_lich.ghi_values([], 2027, []))
        self.assertEqual(rong, {"syncWindowsBoSung": []})

    def test_doc_ky_theo_con_tro(self):
        trang = {
            None: {"items": TENANT_A[:2], "nextCursor": "c2"},
            "c2": {"items": TENANT_A[2:], "nextCursor": None},
        }
        da_goi = []

        class TraLoi(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def mo(yc, timeout):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(yc.full_url).query)
            da_goi.append((q, yc.get_header("Authorization")))
            return TraLoi(json.dumps(trang[q.get("cursor", [None])[0]]).encode())

        kq = sinh_lich.doc_ky("https://cell.vi-du/api/v1/", 2027, "tk", mo=mo)
        self.assertEqual(len(kq), len(TENANT_A))
        self.assertEqual(da_goi[0][0]["year"], ["2027"])
        self.assertEqual(da_goi[0][0]["limit"], ["200"])
        self.assertEqual(da_goi[1][0]["cursor"], ["c2"])
        self.assertEqual(da_goi[0][1], "Bearer tk")

    def test_mo_yeu_cau_khong_tu_gop(self):
        lenh = []
        os.environ.pop("GITLAB_API", None)
        nhanh = sinh_lich.mo_yeu_cau("values-dong-bang.yaml", 2027, chay=lambda c, check: lenh.append(c))
        self.assertTrue(nhanh.startswith("dong-bang/2027-"))
        self.assertIn(["git", "push", "-u", "origin", nhanh], lenh)
        self.assertTrue(any(c[:3] == ["git", "commit", "-S"] for c in lenh))
        self.assertFalse(any("merge" in " ".join(c) for c in lenh))


if __name__ == "__main__":
    unittest.main()
