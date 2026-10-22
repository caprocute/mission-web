"""Kiểm thử đơn vị sinh_cua_so.py với kỳ giả lập theo lược đồ Period của listPeriods (INF-16)."""
import datetime as dt
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.parse

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(GOC, "deploy", "tools", "prescale"))
import sinh_cua_so as s  # noqa: E402

with open(s.MUC_MAC_DINH, encoding="utf-8") as f:
    MUC = yaml.safe_load(f)
HOM_NAY = dt.date(2027, 3, 1)


def ky(ma, loai, ket_thuc, han_nop=None, han_mc=None, trang_thai="MO"):
    """Một phần tử Period đủ trường bắt buộc của lược đồ (id, code, name, kind, flow, ngày, status, rowVersion)."""
    return {"id": f"0192f6e7-0000-7f60-8b72-{abs(hash(ma)) % 10**12:012d}", "code": ma, "name": ma, "kind": loai,
            "flow": "CA_HAI", "startDate": "2027-01-01", "endDate": ket_thuc, "status": trang_thai, "rowVersion": 1,
            "submissionDeadline": han_nop, "milestones": {"evidenceDeadline": han_mc} if han_mc else {}}


class SinhCuaSo(unittest.TestCase):
    def test_moc_chuan_nam_tron_trong_deadline_default(self):
        # Quý I nộp minh chứng 31/3, hạn nộp 10/4: trùng hai cửa sổ cố định nên không sinh thêm.
        ds = s.sinh([ky("2027-Q1", "QUY", "2027-03-31", "2027-04-10", "2027-03-31")], MUC, HOM_NAY)
        self.assertEqual(ds, [])

    def test_han_nop_rieng_cua_tenant_sinh_cua_so_va_tach_thang(self):
        # Tenant đặt hạn nộp quý II là 05/7 nhưng minh chứng hạn 15/7: cửa sổ hanNop 26/6–5/7 vắt qua hai tháng.
        ds = s.sinh([ky("2027-Q2", "QUY", "2027-06-30", "2027-07-05", "2027-06-30")], MUC, HOM_NAY)
        self.assertEqual(len(ds), 1)
        w = ds[0]
        self.assertEqual((w["tu"], w["den"]), ("2027-06-26", "2027-07-05"))
        self.assertEqual(w["lich"], [{"ngay": "26-30", "thang": 6}, {"ngay": "1-5", "thang": 7}])
        self.assertEqual(w["soPod"]["mission-core"], 16)
        self.assertEqual(w["soPod"]["workflow-api"], 6)
        self.assertTrue(w["ten"].startswith("ky-quy-hannop-20270626"))

    def test_gop_cua_so_chong_nhau_giua_cac_tenant(self):
        ds = s.sinh([ky("A", "QUY", "2027-06-30", "2027-07-14", "2027-06-30"),
                     ky("B", "QUY", "2027-06-30", "2027-07-17", "2027-06-30")], MUC, HOM_NAY)
        self.assertEqual([(w["tu"], w["den"]) for w in ds], [("2027-07-05", "2027-07-17")])

    def test_ho_so_nam_va_nam_hoc(self):
        ds = s.sinh([ky("2026", "NAM", "2026-12-31", "2027-02-01"),            # trùng 20/1–1/2: bỏ
                     ky("2026-2027", "NAM_HOC", "2027-05-31", "2027-06-15")], MUC, dt.date(2027, 1, 5))
        self.assertEqual(len(ds), 1)
        self.assertEqual(ds[0]["tu"], "2027-06-03")
        self.assertEqual(ds[0]["soPod"]["gotenberg"], 12)
        self.assertEqual(ds[0]["lich"], [{"ngay": "3-15", "thang": 6}])

    def test_bo_ky_da_khoa_ky_thang_va_cua_so_da_qua(self):
        ds = s.sinh([ky("2026-Q4", "QUY", "2026-12-31", "2027-01-20", trang_thai="DA_KHOA"),
                     ky("2027-02", "THANG", "2027-02-28", "2027-03-15"),
                     ky("2026-Q3", "QUY", "2026-09-30", "2026-10-20", "2026-09-30")], MUC, HOM_NAY)
        self.assertEqual(ds, [])

    def test_mot_ngay_va_ten_trigger_hop_le(self):
        self.assertEqual(s.lich(dt.date(2027, 2, 1), dt.date(2027, 2, 1)), [{"ngay": "1", "thang": 2}])
        ds = s.sinh([ky("2027-Q2", "QUY", "2027-06-30", "2027-07-20", "2027-06-30")], MUC, HOM_NAY)
        for w in ds:
            self.assertRegex(w["ten"], r"^[a-z0-9-]{1,50}$")

    def test_doc_listperiods_theo_con_tro_va_ghi_tep(self):
        trang = [{"items": [ky("2027-Q2", "QUY", "2027-06-30", "2027-07-05", "2027-06-30")], "nextCursor": "c2"},
                 {"items": [ky("2027-Q3", "QUY", "2027-09-30", "2027-10-20", "2027-09-30")], "nextCursor": None}]
        goi = []

        def mo(yc, timeout):
            goi.append(urllib.parse.urlparse(yc.full_url))
            return io.BytesIO(json.dumps(trang[len(goi) - 1]).encode())

        ky_ds = s.doc_ky("https://cell.vi-du/api/v1", 2027, "t", mo)
        q = urllib.parse.parse_qs(goi[1].query)
        self.assertEqual((goi[0].path, q["cursor"], q["limit"]), ("/api/v1/periods", ["c2"], ["200"]))
        ds = s.sinh(ky_ds, MUC, HOM_NAY)
        self.assertEqual(len(ds), 2)
        noi_dung = s.ghi(ds, "g4", HOM_NAY)
        d = yaml.safe_load(noi_dung)
        self.assertEqual(len(d["global"]["cuaSoTangTruoc"]), 2)
        self.assertIn("không sửa tay", noi_dung)

    def test_chay_dong_lenh_tu_tep(self):
        with tempfile.TemporaryDirectory() as t:
            vao, ra = os.path.join(t, "ky.json"), os.path.join(t, "pw.yaml")
            with open(vao, "w", encoding="utf-8") as f:
                json.dump({"items": [ky("2027-Q2", "QUY", "2027-06-30", "2027-07-05", "2027-06-30")],
                           "nextCursor": None}, f)
            kq = subprocess.run([sys.executable, os.path.join(GOC, "deploy/tools/prescale/sinh_cua_so.py"), "--cell",
                                 "g4", "--tu-tep", vao, "--hom-nay", "2027-03-01", "--ra", ra],
                                capture_output=True, text=True, check=True)
            self.assertIn("1 cửa sổ", kq.stdout)
            with open(ra, encoding="utf-8") as f:
                self.assertEqual(yaml.safe_load(f)["global"]["cuaSoTangTruoc"][0]["den"], "2027-07-05")


if __name__ == "__main__":
    unittest.main()
