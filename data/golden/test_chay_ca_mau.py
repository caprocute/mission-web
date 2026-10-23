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
TWD = "twd/diem-cong-viec"


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

    # ------------------------------------------------------------------ cách tính theo điểm công việc (DL-16)
    def ts_twd(self, ghi_de: dict | None = None) -> dict:
        return c.nap_goi(self.goc / TWD).tham_so(ghi_de)

    def test_doi_he_so_nhom_lam_ca_cong_viec_khong_dat(self) -> None:
        self.sua(f"{TWD}/policy.yaml", "{THEN_CHOT: 1.3, THUONG_XUYEN: 1.0}", "{THEN_CHOT: 1.2, THUONG_XUYEN: 1.0}")
        so_loi, ra = self.chay()
        self.assertGreaterEqual(so_loi, 1)
        self.assertIn("KHÔNG ĐẠT VIEC-9.Q4.KR1.NV1.K1.C1", ra)
        self.assertIn("KHÔNG ĐẠT CTT1", ra)

    def test_diem_cong_viec_theo_trang_thai(self) -> None:
        ts = self.ts_twd()
        v = {"diem_chuan": 10, "nhom": "THEN_CHOT", "trang_thai": "HOAN_THANH", "muc_chat_luong": 0.75, "ngay_cham_chu_quan": 2}
        kq = c.chay_viec(v, ts)
        self.assertEqual((kq["giao"], kq["chat_luong"], kq["tien_do"]), (Fraction(13), Fraction("9.75"), Fraction("11.7")))
        self.assertEqual(c.chay_viec(v | {"trang_thai": "CHUYEN_KY"}, ts)["giao"], 0)
        kq = c.chay_viec(v | {"trang_thai": "KHONG_DUOC_CHAP_NHAN"}, ts)
        self.assertEqual((kq["giao"], kq["diem_dat"]), (Fraction(13), 0))
        self.assertEqual(c.chay_viec(v | {"cha_hoan_thanh": False}, ts)["so_luong"], 0)
        self.assertEqual(c.chay_viec(v | {"ngay_cham_chu_quan": 30}, ts)["tien_do"], 0)
        with self.assertRaises(ValueError):
            c.chay_viec(v | {"muc_chat_luong": 0.6}, ts)

    def test_goi_diem_mot_nhom_lay_100(self) -> None:
        ts = self.ts_twd({"thang_100.che_do": "GOI_DIEM"})
        m = {"nhom": "THUONG_XUYEN", "giao": 10, "so_luong": 10, "chat_luong": 5, "tien_do": 10}
        kq = c.chay_ca_nhan({"doi_tuong": "LANH_DAO", "muc": [m]}, ts)
        self.assertEqual((kq["diem_toi_da_tx"], kq["tong_diem"], kq["tt_kpi"]), (100, Fraction("83.33"), None))
        kq = c.chay_ca_nhan({"doi_tuong": "LANH_DAO", "muc": [m, m | {"nhom": "THEN_CHOT", "truc": "TR1"}]}, ts)
        self.assertEqual(kq["tong_diem"], Fraction("83.33"))
        with self.assertRaises(ValueError):          # trục có việc trọng tâm mà chưa có tỷ trọng
            c.chay_ca_nhan({"doi_tuong": "LANH_DAO", "muc": [m | {"nhom": "THEN_CHOT", "truc": "TR2"}]},
                           self.ts_twd({"thang_100.che_do": "GOI_DIEM", "ty_trong_truc": {"TR1": 1}}))
        with self.assertRaises(ValueError):          # hai gói phải cộng lại bằng 100
            c.chay_ca_nhan({"doi_tuong": "LANH_DAO", "muc": [m, m | {"nhom": "THEN_CHOT"}]},
                           self.ts_twd({"thang_100.che_do": "GOI_DIEM", "goi_diem.theo_doi_tuong": {"LANH_DAO": {"THEN_CHOT": 80, "THUONG_XUYEN": 25}}}))

    def test_chuoi_xep_loai_neu_dieu_kien_thieu(self) -> None:
        goi = c.nap_goi(self.goc / TWD)
        dv = {"diem_xep_loai": 92, "ty_le_dap_ung_tieu_chi_chung": 1, "co_viec_trong_tam": False, "hoan_thanh_100_trong_tam": True,
              "ty_le_vuot": 0.1, "giai_phap_dot_pha": False, "truot_trong_tam_chu_quan": False, "co_quy_khong_hoan_thanh": False,
              "loai_tru": False, "rang_buoc_tap_the_thap_nhat": False, "rang_buoc_tap_the_ha_xuat_sac": False}
        kq = c.chay_chuoi(goi, dv, goi.tham_so(None))
        self.assertEqual(kq["muc_goi_y"], "HTTNV")
        self.assertEqual(kq["dieu_kien_thieu_xuat_sac"], ["co_viec_trong_tam", "dat_ty_le_vuot", "giai_phap_dot_pha"])
        self.assertEqual(len(kq["cac_dong"]), len(goi.policy["classification"]["chuoi"]))

    def test_ca_lo_dem_lai_khi_doi_tham_so_mac_dinh(self) -> None:
        # Tắt tham số chặn Xuất sắc khi không có việc trọng tâm: ca lô tình huống 2 vẫn đạt, ca chặn thì không.
        self.sua(f"{TWD}/policy.yaml", "xep_loai.chan_xuat_sac_khi_khong_co_trong_tam: {type: bool, mac_dinh: true",
                 "xep_loai.chan_xuat_sac_khi_khong_co_trong_tam: {type: bool, mac_dinh: false")
        _, ra = self.chay()
        self.assertIn("KHÔNG ĐẠT XL-DR09-BAT", ra)
        self.assertNotIn("KHÔNG ĐẠT LO-TH2", ra)


if __name__ == "__main__":
    unittest.main()
