"""Kiểm thử kiem_ui_pack.py. Chạy:  python3 -I -m unittest discover -s data/seed/ui-pack -p 'test_*.py'"""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
sys.path.insert(0, str(THU_MUC))
import kiem_ui_pack as k  # noqa: E402

MAC_DINH = json.loads((THU_MUC / "mac-dinh-1.0.0.json").read_text(encoding="utf-8"))


def ban() -> dict:
    return copy.deepcopy(MAC_DINH)


class KiemUiPack(unittest.TestCase):
    def test_ui_pack_mac_dinh_dat_moi_kiem_tra(self):
        loi, kich_thuoc = k.kiem(ban())
        self.assertEqual(loi, [])
        self.assertLess(kich_thuoc, k.NGAN_SACH_BYTE)

    def test_tuong_phan_duoi_aa_bi_bao(self):
        p = ban()
        p["tokens"]["color"]["text-muted"]["$value"] = "#9CA3AF"
        loi, _ = k.kiem(p)
        self.assertTrue(any("text-muted" in l and "4.5" in l for l in loi), loi)

    def test_che_do_cao_tuoi_can_7_1_chu_17px_vung_cham_48px_vien_2px(self):
        p = ban()
        cao = p["tokens"]["$extensions"]["modes"]["elderly"]
        cao["color"]["text-muted"]["$value"] = "#475569"
        cao["font"]["size"]["body"]["$value"] = "16px"
        cao["flags"]["minTouchTarget"] = "44px"
        cao["border"]["width"]["$value"] = "1px"
        loi = "\n".join(k.kiem(p)[0])
        for y in ("7.0 : 1", "17 px", "48 px", "2 px"):
            self.assertIn(y, loi)

    def test_component_ngoai_registry_va_qua_12_widget_bi_bao(self):
        p = ban()
        con = p["layouts"]["home"]["children"]
        con.append({"component": "vt.widget.khongCo"})
        con.extend({"id": f"w{i}x", "component": "vt.widget.myTasksToday"} for i in range(10))
        loi = "\n".join(k.kiem(p)[0])
        self.assertIn("không có trong registry", loi)
        self.assertIn("tối đa 12", loi)

    def test_props_sai_luoc_do_bi_bao(self):
        p = ban()
        p["layouts"]["home"]["children"][0]["props"]["limit"] = 99
        self.assertTrue(any(".props" in l for l in k.kiem(p)[0]))

    def test_html_trong_chuoi_bi_luoc_do_chan(self):
        p = ban()
        p["layouts"]["home"]["children"][1]["visibleWhen"] = "<script>alert(1)</script>"
        self.assertTrue(any(l.startswith("lược đồ") for l in k.kiem(p)[0]))

    def test_cap_mau_theo_quy_uoc_ten(self):
        cap = k.cap_mau(["a.text", "a.surface", "a.on-primary", "a.primary", "b.text-muted", "b.background"])
        self.assertIn(("a.text", "a.surface"), cap)
        self.assertIn(("a.on-primary", "a.primary"), cap)
        self.assertIn(("b.text-muted", "b.background"), cap)
        self.assertAlmostEqual(k.ti_le_tuong_phan("#FFFFFF", "#EE0033"), 4.48, places=2)


if __name__ == "__main__":
    unittest.main()
