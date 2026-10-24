"""Kiểm thử bộ dữ liệu thử đa tenant: nguồn nhất quán, tệp sinh khớp nguồn, không đụng tài khoản thử cũ."""

import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("sinh_seed", str(HERE / "sinh_seed.py"))
S = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S)


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

    def test_khong_co_bi_mat(self):
        noi_dung = S.KC.read_text(encoding="utf-8")
        self.assertIn("${KC_TEST_USER_PASSWORD}", noi_dung)
        self.assertIn("${KC_TEST_OTP_SECRET}", noi_dung)


if __name__ == "__main__":
    unittest.main()
