import pytest

from datagen.ho_so import THU_MUC_HO_SO, doc_ho_so


@pytest.mark.parametrize("ten", ["cell-l", "cell-m", "cell-s", "cell-compact"])
def test_moi_ho_so_hop_le_va_cung_ty_le_cell_hcm(ten):
    hs = doc_ho_so(ten)
    assert hs.don_vi * 100 == pytest.approx(hs.tai_khoan, rel=0.01)
    assert hs.nhiem_vu == 30 * hs.tai_khoan
    assert hs.ho_so_danh_gia == hs.tai_khoan
    assert sum(hs.ty_le_cap) == pytest.approx(1.0)
    assert 0 < hs.xac_suat_co_minh_chung <= 1


def test_he_so_quy_mo_nhan_dung():
    hs = doc_ho_so("cell-l", 0.001)
    assert (hs.tai_khoan, hs.don_vi, hs.nhiem_vu, hs.phien_ban_minh_chung) == (1200, 12, 36000, 10000)
    with pytest.raises(ValueError):
        doc_ho_so("cell-l", 0)


def test_phan_phoi_sai_tong_bi_tu_choi(tmp_path):
    loi = (THU_MUC_HO_SO / "cell-compact.yaml").read_text(encoding="utf-8").replace("HUY: 0.01", "HUY: 0.5")
    (tmp_path / "loi.yaml").write_text(loi, encoding="utf-8")
    with pytest.raises(ValueError, match="tổng 1"):
        doc_ho_so(str(tmp_path / "loi.yaml"))
