"""Kiểm thử bộ công cụ định cỡ (INF-03): số mẫu TL-04 mục 4.7, ca vượt ngân sách, bảng TL-05 hồ sơ M."""
import os
import subprocess
import sys

import pytest

THU_MUC = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, THU_MUC)

import dinh_co as dc  # noqa: E402
import kiem_ngan_sach_ket_noi as ns  # noqa: E402
import tinh_dinh_co as td  # noqa: E402

GD = dc.gia_dinh()
CA_40 = os.path.join(THU_MUC, "tests", "values-mission-core-40.yaml")


def test_so_mau_pg_core_cell_hcm():
    # TL-04 mục 4.7: 24 × 24 = 576 + 20 + 10 = 606 ≤ 3 × 1.000; 3 × 40 + 20 + 30 = 170 ≤ 400.
    t1, p1, d1, t2, p2, d2 = ns.bat_dang_thuc(24 * 24, 20, 10, 3, 1000, 40, 20 + 30, 400)
    assert (t1, p1, d1) == (606, 3000, True)
    assert (t2, p2, d2) == (170, 400, True)


def test_bat_dang_thuc_vi_pham():
    assert ns.bat_dang_thuc(3000, 20, 10, 3, 1000, 40, 50, 400)[2] is False
    assert ns.bat_dang_thuc(576, 20, 10, 3, 1000, 120, 50, 400)[5] is False


@pytest.mark.parametrize("ten", dc.cac_cell())
def test_moi_cell_dat_ngan_sach(ten):
    _, v = dc.values_cell(ten)
    assert all(r["dat"] is not False for r in ns.kiem(v, GD)), ten


def test_hcm_dung_so_tl04():
    _, v = dc.values_cell("hcm")
    kq = {(r["cum"], r["kiem"][:3]): r for r in ns.kiem(v, GD)}
    assert kq[("pg-core", "(1)")]["trai"] == 606
    assert kq[("pg-core", "(3)")]["trai"] == 24 * 600


def test_mission_core_40_bi_tu_choi():
    _, v = dc.values_cell("g4", them=[CA_40])
    vp = [r for r in ns.kiem(v, GD) if r["dat"] is False]
    assert [(r["cum"], r["kiem"]) for r in vp] == [("pg-core", "(3) CPU primary")]
    r = subprocess.run([sys.executable, os.path.join(THU_MUC, "kiem_ngan_sach_ket_noi.py"), "--cell", "g4", "-f", CA_40],
                       capture_output=True, text=True)
    assert r.returncode == 1 and "VI PHẠM" in r.stdout


def test_compact_gop_flowable_vao_pg_core():
    _, v = dc.values_cell("nb")
    assert {r["cum"] for r in ns.kiem(v, GD)} == {"pg-core"}


def test_tai_dau_vao_ho_so_m_khop_tl05():
    t = td.tai(GD["gd"], 800000)
    assert (t["CCU"], t["RPS đỉnh"], t["Request ghi đỉnh/giây"], t["Bản ghi CDC đỉnh/giây"]) == (40000, 4000, 800, 4800)
    assert round(t["User task hoàn thành đỉnh/giây"]) == 89 and round(t["Đăng nhập đỉnh/giây"]) == 67


def test_ho_so_m_khop_bang_tl05():
    g, ct = GD["gd"], GD["congThuc"]
    can = td.pod_can(g, ct, 800000, 4000)
    assert can["mission-core"] == (11, 17)
    assert can["gotenberg"][0] == 7
    rep = dc.so_replica(dc.values("M"))
    assert rep["mission-core"] == (6, 17)
    n = td.node(g, rep)
    # TL-05: app-general 64 / 136 vCPU, 12 node; app-worker 25 / 118 vCPU, 12 node. org-service chưa có HPA nên
    # max lấy replicaCount 3 thay vì 4 của TL-05, lệch 1 vCPU (ghi trong báo cáo INF-03).
    assert round(n["app-general"][0]) == 64 and abs(n["app-general"][1] - 136) <= 1.5 and n["app-general"][2] == 12
    assert round(n["app-worker"][0]) == 25 and round(n["app-worker"][1]) == 118 and n["app-worker"][2] == 12


def test_ho_so_l_khop_tl04():
    g = GD["gd"]
    n = td.node(g, dc.so_replica(dc.values("L")))
    assert n["app-general"][2] == 16 and n["app-worker"][2] == 16


def test_gia_dinh_du_26_ma():
    assert sorted(GD["gd"]) == [f"GD-{i:02d}" for i in range(1, 27)]
    for ma, g in GD["gd"].items():
        assert {"ten", "giaTri", "donVi", "nguon", "loai", "ngayDo", "kichBan"} <= set(g), ma
