"""Kiểm thử bộ chấm quý VH-09: `pytest docs/danh-gia-quy`."""
import sys
from collections import Counter
from pathlib import Path

import pytest
import yaml

sys.dont_write_bytecode = True
THU_MUC = Path(__file__).resolve().parent
sys.path.insert(0, str(THU_MUC))
import cham  # noqa: E402

SO_TIEU_CHI = dict(A=8, L=12, B=8, C=10, D=12, E=8, F=11, G=7, H=9, I=8, J=9, K=8)


@pytest.fixture(scope="module")
def dm():
    return cham.nap_danh_muc()


def test_du_110_tieu_chi_khong_trung_ma(dm):
    ma = [t["ma"] for t in dm["tieu_chi"]]
    assert len(ma) == 110
    assert not [m for m, n in Counter(ma).items() if n > 1]


def test_so_tieu_chi_va_trong_so_tung_truc(dm):
    dem = Counter(t["ma"][0] for t in dm["tieu_chi"])
    assert dict(dem) == SO_TIEU_CHI
    assert sum(t["trong_so"] for t in dm["truc"]) == 120
    assert {t["ma"] for t in dm["truc"]} == set(SO_TIEU_CHI)


def test_moi_tieu_chi_du_truong(dm):
    for t in dm["tieu_chi"]:
        for k in ("ten", "giai_phap", "tl01", "bang_chung_nghiem_thu", "loai_bang_chung", "task", "co_so"):
            assert t.get(k), f"{t['ma']} thiếu {k}"
        assert t["loai_bang_chung"] in cham.TEN_LOAI
        assert t["co_so"] in cham.GIA_TRI_KQ


def test_task_dap_ung_co_trong_danh_muc(dm):
    tsv = (THU_MUC.parent / "tasks" / "danh-muc-he-thong.tsv").read_text(encoding="utf-8")
    co = {d.split("\t")[0] for d in tsv.splitlines() if d and not d.startswith("#")}
    co |= {p.name.split("-")[0] + "-" + p.name.split("-")[1] for p in (THU_MUC.parent / "tasks").glob("WEB-*.md")}
    thieu = sorted({x for t in dm["tieu_chi"] for x in t["task"] if x not in co})
    assert not thieu, f"task không có trong danh mục: {thieu}"


def test_ban_co_so_ra_17_4(dm):
    ban = cham.cham(THU_MUC / "co-so-2026-10.yaml", dm)
    assert round(ban.tong, 1) == 17.4
    assert not ban.canh_bao
    # Điểm trục khớp bản đánh giá (làm tròn phần trăm).
    mong = dict(A=19, L=21, B=25, C=5, D=17, E=12, F=18, G=14, H=31, I=6, J=28, K=12)
    assert {a: round(v * 100) for a, v in ban.diem_truc.items()} == mong
    dat = sum(1 for k in ban.ket_qua.values() if k.nhan == "Đạt")
    mot_phan = sum(1 for k in ban.ket_qua.values() if k.nhan == "Một phần")
    # Bản đánh giá ghi "2 đạt, 36 một phần" tính cả nhãn của trục H (7 tiêu chí H "Một phần").
    mot_phan_h = sum(1 for m, t in dm["_theo_ma"].items() if m[0] == "H" and t["co_so"] == "Một phần")
    assert (dat, mot_phan + mot_phan_h) == (2, 36)
    assert all(ban.ket_qua[m].nhan == t["co_so"] for m, t in dm["_theo_ma"].items() if m[0] != "H")


def test_ban_cham_quy_chay_va_khong_canh_bao(dm):
    for p in sorted(THU_MUC.glob("20[0-9][0-9]-Q[1-4].yaml")):
        ban = cham.cham(p, dm)
        assert 0 <= ban.tong <= 100
        assert not ban.canh_bao, ban.canh_bao
        md = cham.bao_cao(ban, dm, cham.cham(cham.tim_ban_truoc(ban), dm) if ban.meta.get("so_sanh_voi") else None)
        assert "Điểm đáp ứng tổng hợp" in md and "Bảng chỉ số chuyển đổi" in md


def test_thieu_tieu_chi_bi_tu_choi(tmp_path, dm):
    bc = yaml.safe_load((THU_MUC / "co-so-2026-10.yaml").read_text(encoding="utf-8"))
    del bc["ket_qua"]["K8"]
    p = tmp_path / "2099-Q1.yaml"
    p.write_text(yaml.safe_dump(bc, allow_unicode=True), encoding="utf-8")
    with pytest.raises(cham.LoiBanCham, match="K8"):
        cham.cham(p, dm)


def test_gia_tri_ngoai_thang_bi_tu_choi(tmp_path, dm):
    bc = yaml.safe_load((THU_MUC / "co-so-2026-10.yaml").read_text(encoding="utf-8"))
    bc["ket_qua"]["A1"]["kq"] = "Gần đạt"
    bc["ket_qua"]["H1"]["muc"] = 5
    p = tmp_path / "2099-Q1.yaml"
    p.write_text(yaml.safe_dump(bc, allow_unicode=True), encoding="utf-8")
    with pytest.raises(cham.LoiBanCham):
        cham.cham(p, dm)


def test_dat_khi_chua_do_bi_canh_bao(tmp_path, dm):
    bc = yaml.safe_load((THU_MUC / "co-so-2026-10.yaml").read_text(encoding="utf-8"))
    bc["giai_doan"] = "phat-trien"
    bc["ket_qua"]["E7"] = {"kq": "Đạt", "bang_chung": ["docs/danh-gia-quy/cham.py"]}
    bc["ket_qua"]["A1"] = {"kq": "Đạt", "bang_chung": ["khong/co/tep-nay.md"]}
    p = tmp_path / "2099-Q1.yaml"
    p.write_text(yaml.safe_dump(bc, allow_unicode=True), encoding="utf-8")
    ban = cham.cham(p, dm)
    assert any(c.startswith("E7:") and "chưa đo" in c for c in ban.canh_bao)
    assert any(c.startswith("A1:") and "không tồn tại" in c for c in ban.canh_bao)
    assert cham.main([str(p), "--nghiem", "--ra", str(tmp_path / "bc.md")]) == 2


def test_diem_tran_phat_trien(dm):
    assert round(cham.diem_tran_phat_trien(dm), 1) == 67.5
