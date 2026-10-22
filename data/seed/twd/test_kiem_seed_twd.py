"""Kiểm thử cho kiem_seed_twd.py: dữ liệu thật đạt, và mỗi loại sai cố ý đều bị bắt.
Cách chạy (từ gốc repo):  python -I -m unittest discover -s data/seed/twd -p 'test_*.py'"""
import os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kiem_seed_twd as k

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.."))


class KiemSeedTwd(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        for rel in ("data/seed/twd", "data/policy-pack/twd", "data/golden/twd"):
            shutil.copytree(os.path.join(GOC, rel), os.path.join(self.tmp, rel))

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def sua(self, rel, cu, moi):
        p = os.path.join(self.tmp, rel)
        s = open(p, encoding="utf-8").read()
        self.assertIn(cu, s)
        open(p, "w", encoding="utf-8").write(s.replace(cu, moi, 1))

    def loi(self):
        return k.kiem(self.tmp)[0]

    def test_du_lieu_hien_tai_dat(self):
        self.assertEqual(k.kiem(GOC)[0], [])

    def test_bat_bien_cel_chua_khai_bao(self):
        self.sua("data/seed/twd/diem-nghen.yaml", "muc.viec_truoc_qua_han &&", "muc.viec_truoc_qua_han_x &&")
        self.assertTrue(any("viec_truoc_qua_han_x" in m for m in self.loi()))

    def test_bat_tham_so_chua_khai_bao(self):
        self.sua("data/seed/twd/diem-nghen.yaml", ">= tham_so.q2.moc_2", ">= tham_so.q2.moc_3")
        self.assertTrue(any("tham_so.q2.moc_3" in m for m in self.loi()))

    def test_bat_muc_do_ngoai_hop_dong(self):
        self.sua("data/seed/twd/diem-nghen.yaml", "? 'CAO' : 'TRUNG_BINH'", "? 'RAT_CAO' : 'TRUNG_BINH'")
        self.assertTrue(any("RAT_CAO" in m for m in self.loi()))

    def test_bat_han_buoc_sai(self):
        self.sua("data/seed/twd/twd-tham-quyen.yaml", "THAM_DINH: 1, QUYET_DINH: 1", "THAM_DINH: 2, QUYET_DINH: 1")
        self.assertTrue(any("2/2/1/1" in m for m in self.loi()))

    def test_bat_lech_phien_ban_bo_quy_tac(self):
        self.sua("data/policy-pack/twd/ban-to-chuc-ueb/ho-so.yaml", 'twd/diem-nghen, version: "0.1.0"', 'twd/diem-nghen, version: "0.2.0"')
        self.assertTrue(any("bộ quy tắc cảnh báo" in m for m in self.loi()))

    def test_bat_diem_chuan_ngoai_mien(self):
        self.sua("data/seed/twd/danh-muc/catalog_item.csv", ",2,2.000,", ",2,120.000,")
        self.assertTrue(any("điểm chuẩn ngoài" in m for m in self.loi()))

    def test_bat_ca_ueb_bi_bo_sot(self):
        self.sua("data/golden/twd/ca-nghiem-thu.yaml", "  - {ma: CTB28,", "  - {ma: CTB28X,")
        self.assertTrue(any("CTB28 chưa có" in m for m in self.loi()))


if __name__ == "__main__":
    unittest.main()
