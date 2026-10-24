"""Kiểm thử bộ dữ liệu thử đa tenant: nguồn nhất quán, tệp sinh khớp nguồn, không đụng tài khoản thử cũ."""

import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("sinh_seed", str(HERE / "sinh_seed.py"))
S = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S)
_spec2 = importlib.util.spec_from_file_location("sinh_bang_doi_chieu", str(HERE / "sinh_bang_doi_chieu.py"))
B = importlib.util.module_from_spec(_spec2)
_spec2.loader.exec_module(B)


class DuLieuDaTenant(unittest.TestCase):
    def test_nguon_khong_loi(self):
        self.assertEqual(S.kiem_du_lieu(), [])

    def test_tep_sinh_khop_nguon(self):
        self.assertEqual(S.SQL.read_text(encoding="utf-8"), S.sinh_sql())
        self.assertEqual(S.KC.read_text(encoding="utf-8"), S.sinh_keycloak())
        self.assertEqual(S.MD.read_text(encoding="utf-8"), S.sinh_md())

    def test_khong_trung_realm_cu(self):
        cu = json.loads((S.ROOT / "infra/dev/keycloak/realm-mission.json").read_text(encoding="utf-8"))["users"]
        moi = json.loads(S.KC.read_text(encoding="utf-8"))["users"]
        self.assertFalse({u["username"] for u in cu} & {u["username"] for u in moi})
        self.assertFalse({u.get("id") for u in cu} & {u["id"] for u in moi})
        vai_tro = {r["name"] for r in json.loads((S.ROOT / "infra/dev/keycloak/realm-mission.json")
                                                 .read_text(encoding="utf-8"))["roles"]["realm"]}
        for u in moi:
            self.assertTrue(set(u["realmRoles"]) <= vai_tro, u["username"])

    def test_bang_doi_chieu_khop_nguon(self):
        self.assertEqual(B.DICH.read_text(encoding="utf-8"), B.sinh())

    def test_moi_chuc_nang_co_tinh_trang_va_ly_do(self):
        m = json.loads(B.MA_TRAN.read_text(encoding="utf-8"))
        for c in m["chuc_nang"]:
            tt, mo_ta = B.tinh_trang(c)
            self.assertIn(tt, (B.CO, B.TRUC_TIEP, B.TRONG, B.NGOAI), c["key"])
            self.assertTrue(mo_ta, c["key"])
        khoa_hop_le = {c["key"] for c in m["chuc_nang"]} | set(m["man_hinh"])
        self.assertFalse(set(B.TINH_TRANG) - khoa_hop_le)

    def test_nhiem_vu_du_bay_trang_thai(self):
        for t in S.D.TENANT:
            co = {S.D.dich_nhiem_vu(n) for n in S.D.NHIEM_VU[t["ma"]]}
            self.assertEqual(co, set(S.TRANG_THAI_NV), t["ma"])
            self.assertEqual({o.get("dich", "DA_DUYET") for o in S.D.OKR[t["ma"]]},
                             {"NHAP", "CHO_DUYET", "TRA_LAI", "DA_DUYET"}, t["ma"])

    def test_khong_co_bi_mat(self):
        noi_dung = S.KC.read_text(encoding="utf-8")
        self.assertIn("${KC_TEST_USER_PASSWORD}", noi_dung)
        self.assertIn("${KC_TEST_OTP_SECRET}", noi_dung)


if __name__ == "__main__":
    unittest.main()
