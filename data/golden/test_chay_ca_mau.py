"""Kiểm thử cho chay_ca_mau.py. Chạy:  python -I -m unittest discover -s data/golden -p 'test_*.py'"""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from fractions import Fraction
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
sys.path.insert(0, str(THU_MUC))
import chay_ca_mau as c  # noqa: E402

GOC_REPO = THU_MUC.parent / "policy-pack"
DANG = "khoi-dang/quy-dinh-moi"


class ChayCaMauTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.goc = Path(self.tmp.name) / "policy-pack"
        shutil.copytree(GOC_REPO, self.goc)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def chay(self, ghi_bang: bool = False) -> tuple[int, str]:
        dong: list[str] = []
        so_loi = c.chay(self.goc, ghi_bang, Path(self.tmp.name) / "golden", dong.append)
        return so_loi, "\n".join(dong)

    def sua(self, tep: str, cu: str, moi: str) -> None:
        p = self.goc / tep
        noi_dung = p.read_text(encoding="utf-8")
        self.assertIn(cu, noi_dung)
        p.write_text(noi_dung.replace(cu, moi, 1), encoding="utf-8")

    def test_bo_ca_trong_repo_dat(self) -> None:
        so_loi, ra = self.chay()
        self.assertEqual(so_loi, 0, ra)

    def test_doi_nguong_dmn_lam_ca_bien_khong_dat(self) -> None:
        # YC-PT-14: đổi ngưỡng trong bảng quyết định là thấy ngay ca nào đổi kết quả, không sửa mã.
        self.sua(f"{DANG}/dmn/xep-loai-dang.dmn", "&gt;= 90", "&gt;= 91")
        so_loi, ra = self.chay()
        self.assertEqual(so_loi, 1)
        self.assertIn("KHÔNG ĐẠT Q-015", ra)

    def test_doi_tham_so_mac_dinh(self) -> None:
        self.sua(f"{DANG}/policy.yaml", "htxs.nguong_ty_le_vuot: {type: double, mac_dinh: 0.30",
                 "htxs.nguong_ty_le_vuot: {type: double, mac_dinh: 0.50")
        _, ra = self.chay()
        self.assertIn("KHÔNG ĐẠT Q-023", ra)

    def test_bo_dong_dmn_bao_thieu_phu(self) -> None:
        # Bỏ hết ca chạm dòng 3 (loại trừ) của bảng quý thì ma trận phủ báo thiếu.
        p = self.goc / DANG / "tests" / "bo-ca-mau-gia-lap.yaml"
        bo = c.yaml.safe_load(p.read_text(encoding="utf-8"))
        bo["ca"] = [x for x in bo["ca"] if x["id"] != "Q-007"]
        p.write_text(c.yaml.safe_dump(bo, allow_unicode=True), encoding="utf-8")
        so_loi, ra = self.chay()
        self.assertGreaterEqual(so_loi, 1)
        self.assertIn("xep-loai-dang-dong-3 của xep-loai-dang.dmn chưa có ca", ra)

    def test_ghi_bang_doi_chieu(self) -> None:
        self.chay(ghi_bang=True)
        bang = (Path(self.tmp.name) / "golden" / DANG / "bang-doi-chieu.csv").read_text(encoding="utf-8")
        self.assertTrue(bang.startswith("ma_ca,loai,mo_ta,can_cu,mong_doi,ket_qua_tinh,dat,nguoi_ky"))
        self.assertIn("Q-001,QUY", bang)

    def test_feel_rut_gon(self) -> None:
        self.assertTrue(c.khop("[0.9..1)", 0.9))
        self.assertFalse(c.khop("[0.9..1)", 1))
        self.assertTrue(c.khop('"A","B"', "B"))
        self.assertTrue(c.khop("-", None))
        self.assertTrue(c.khop("false", False))
        self.assertFalse(c.khop("false", True))
        self.assertTrue(c.khop("0", 0))
        with self.assertRaises(ValueError):
            c.khop("not(1)", 1)

    def test_lam_tron_nua_len(self) -> None:
        self.assertEqual(c.lam_tron(Fraction("89.95"), 1), Fraction("90.0"))
        self.assertEqual(c.lam_tron(Fraction(2098, 30), 1), Fraction("69.9"))


if __name__ == "__main__":
    unittest.main()
