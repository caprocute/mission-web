"""Kiểm thử cho kiem_mau.py. Chạy:  python -I -m unittest discover -s data/seed/docgen -p 'test_*.py'"""
from __future__ import annotations

import copy
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
sys.path.insert(0, str(THU_MUC))
import kiem_mau  # noqa: E402
import sinh_mau_gia_lap  # noqa: E402

GOC_REPO = THU_MUC.parent.parent / "policy-pack"
MAU_GOC = GOC_REPO / "khoi-dang" / "quy-dinh-moi" / "templates"
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def tao_docx(tep: Path, tags: list[str]) -> None:
    sdt = "".join(
        f'<w:sdt><w:sdtPr><w:tag w:val="{t}"/></w:sdtPr><w:sdtContent><w:p/></w:sdtContent></w:sdt>' for t in tags
    )
    with zipfile.ZipFile(tep, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        z.writestr("word/document.xml", f'<?xml version="1.0"?><w:document xmlns:w="{W}"><w:body>{sdt}</w:body></w:document>')


def tat_ca_tag(ds: list) -> list[str]:
    ra: list[str] = []
    for t in ds:
        ra.append(t["tag"])
        ra.extend(tat_ca_tag(t.get("cot", [])))
    return ra


class KiemMauTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.goc = Path(self.tmp.name)
        self.mau = {}
        for tep in sorted(MAU_GOC.glob("*/manifest.json")):
            shutil.copytree(tep.parent, self.goc / "goi" / "templates" / tep.parent.name)
            self.mau[tep.parent.name] = json.loads(tep.read_text(encoding="utf-8"))

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def ghi(self, ten: str, m: dict) -> None:
        (self.goc / "goi" / "templates" / ten / "manifest.json").write_text(json.dumps(m, ensure_ascii=False), encoding="utf-8")

    def test_bo_mau_trong_repo_khong_loi(self) -> None:
        kq = kiem_mau.chay(GOC_REPO)
        self.assertEqual(kq.loi, [])

    def test_mau_gia_lap_thieu_docx_la_loi(self) -> None:
        for d in (self.goc / "goi" / "templates").glob("*/*.docx"):
            d.unlink()
        kq = kiem_mau.chay(self.goc)
        self.assertEqual(len([x for x in kq.loi if "chưa có tệp mẫu DOCX" in x]), 7)

    def test_khung_thieu_docx_chi_canh_bao_tru_che_do_nghiem_ngat(self) -> None:
        m = copy.deepcopy(self.mau["mau-2"])
        m["trang_thai"] = "KHUNG_CHO_BAN_MAU"
        self.ghi("mau-2", m)
        (self.goc / "goi" / "templates" / "mau-2" / m["tep_mau"]).unlink()
        self.assertEqual(kiem_mau.chay(self.goc).loi, [])
        self.assertEqual(len(kiem_mau.chay(self.goc).canh_bao), 1)
        self.assertEqual(len(kiem_mau.chay(self.goc, nghiem_ngat=True).loi), 1)

    def test_bo_mau_gia_lap_trung_khit_tag(self) -> None:
        kq = kiem_mau.chay(self.goc, nghiem_ngat=True)
        self.assertEqual(kq.loi, [])

    def test_mau_gia_lap_phai_co_dong_gia_lap(self) -> None:
        m = self.mau["mau-2"]
        tao_docx(self.goc / "goi" / "templates" / "mau-2" / m["tep_mau"], tat_ca_tag(m["truong"]))
        self.assertIn("MẪU GIẢ LẬP", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_doi_ke_khai_chi_can_sinh_lai_mau(self) -> None:
        # YC-PT-14: thêm trường vào kê khai rồi sinh lại mẫu là đủ, không sửa mã.
        m = copy.deepcopy(self.mau["mau-2"])
        m["truong"].insert(4, dict(m["truong"][0], tag="ma_ho_so_can_bo", nhan="Mã hồ sơ", nguon="org_replica.person_v.cbccvc_code"))
        self.ghi("mau-2", m)
        self.assertIn("ma_ho_so_can_bo", "\n".join(kiem_mau.chay(self.goc).loi))
        sinh_mau_gia_lap.main([str(self.goc)])
        self.assertEqual(kiem_mau.chay(self.goc, nghiem_ngat=True).loi, [])

    def test_docx_thieu_va_thua_tag(self) -> None:
        m = self.mau["mau-2"]
        tags = tat_ca_tag(m["truong"])[1:] + ["vung_la"]
        tao_docx(self.goc / "goi" / "templates" / "mau-2" / m["tep_mau"], tags)
        loi = "\n".join(kiem_mau.chay(self.goc).loi)
        self.assertIn("DOCX thiếu vùng trộn", loi)
        self.assertIn("vung_la", loi)

    def test_docx_hong(self) -> None:
        m = self.mau["mau-3"]
        (self.goc / "goi" / "templates" / "mau-3" / m["tep_mau"]).write_bytes(b"khong phai zip")
        self.assertIn("không phải OOXML", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_cot_khong_co_trong_tl03(self) -> None:
        m = copy.deepcopy(self.mau["mau-2"])
        m["truong"][0]["nguon"] = "org_replica.org_unit_v.ten_bi_dat"
        self.ghi("mau-2", m)
        self.assertIn("không có trong bảng", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_so_lieu_khong_khoa(self) -> None:
        m = copy.deepcopy(self.mau["bang-cham-diem"])
        for t in m["truong"]:
            if t["tag"] == "tong_diem":
                t["khoa_so_lieu"] = False
        self.ghi("bang-cham-diem", m)
        self.assertIn("BR-11-02", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_khong_in_du_lieu_c3(self) -> None:
        m = copy.deepcopy(self.mau["mau-1b"])
        m["truong"][0]["muc_du_lieu"] = "C3"
        self.ghi("mau-1b", m)
        self.assertIn("không được in", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_thieu_tem_phien_ban(self) -> None:
        m = copy.deepcopy(self.mau["mau-1c"])
        m["truong"] = [t for t in m["truong"] if t.get("vung") != "chan_trang"]
        self.ghi("mau-1c", m)
        self.assertIn("BR-11-03", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_tag_trung_va_sai_dang(self) -> None:
        m = copy.deepcopy(self.mau["mau-1a"])
        m["truong"][1]["tag"] = m["truong"][0]["tag"]
        m["truong"].append(dict(m["truong"][0], tag="TenSai"))
        self.ghi("mau-1a", m)
        loi = "\n".join(kiem_mau.chay(self.goc).loi)
        self.assertIn("tag bị trùng", loi)
        self.assertIn("snake_case", loi)

    def test_thieu_mau_trong_danh_muc(self) -> None:
        (self.goc / "goi" / "templates" / "mau-1d" / "manifest.json").unlink()
        self.assertIn("MAU_1D", "\n".join(kiem_mau.chay(self.goc).loi))

    def test_hieu_luc_phai_co_so_hieu(self) -> None:
        m = copy.deepcopy(self.mau["mau-3"])
        m["trang_thai"] = "HIEU_LUC"
        self.ghi("mau-3", m)
        self.assertIn("số hiệu", "\n".join(kiem_mau.chay(self.goc).loi))


if __name__ == "__main__":
    unittest.main()
