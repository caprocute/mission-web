from datetime import date

from anonymize import gia
from anonymize.che import chu_so
from anonymize.quet import SO, THU


def test_anh_xa_nhat_quan_duy_nhat_va_khong_trung_goc():
    ax = gia.BoAnhXa()
    a = ax.anh_xa("so", "001089012345", gia.so_the_dang)
    assert ax.anh_xa("so", "001089012345", gia.so_the_dang) == a
    khac = {ax.anh_xa("so", f"{i:012d}", lambda r: f"{r.randrange(50):02d}") for i in range(50)}
    assert len(khac) == 50  # buộc duy nhất dù không gian ứng viên nhỏ
    assert ax.anh_xa("t", "x", lambda r: r.choice(["x", "y"])) == "y"


def test_hai_lan_chay_khoa_khac_nhau_cho_ket_qua_khac_va_huy_khoa():
    a, b = gia.BoAnhXa(), gia.BoAnhXa()
    assert [a.anh_xa("so", str(i), gia.so_the_dang) for i in range(5)] != [b.anh_xa("so", str(i), gia.so_the_dang) for i in range(5)]
    a.huy()
    assert a.da_huy


def test_ngay_sinh_giu_nhom_tuoi():
    ax, moc = gia.BoAnhXa(), date(2026, 1, 1)
    for goc in (date(1966, 1, 2), date(1970, 6, 30), date(1980, 2, 29), date(2000, 12, 31), date(1990, 1, 1)):
        moi = gia.ngay_sinh_cung_nhom(ax.rng("ns", goc.isoformat()), goc, moc, 5)
        assert gia.nhom_tuoi(moi, moc, 5) == gia.nhom_tuoi(goc, moc, 5)


def test_dinh_dang_gia_tri_gia():
    r = gia.BoAnhXa().rng("x", "y")
    s = gia.so_dinh_danh(r, "NU", 1985)
    assert len(s) == 12 and s.startswith("9991") and s[4:6] == "85"
    assert gia.dien_thoai(r).startswith(gia.DAU_SO_DIEN_THOAI) and len(gia.dien_thoai(r)) == 10
    assert gia.thu_dien_tu(r).endswith(".invalid")
    assert gia.ten_tep(r, "Báo cáo quý.pdf").endswith(".pdf")
    for n in (0, 1, 7, 45, 300):
        assert len(gia.van_ban_cung_do_dai(r, n)) == n
    assert gia.thuoc_kho_ho_ten_gia(gia.ho_ten(r, "NU"))
    assert not gia.thuoc_kho_ho_ten_gia("Khổng Minh Châu")


def test_bo_nhan_dien_so_va_thu():
    assert chu_so("+84 912 345 678") == "0912345678"
    assert [chu_so(x) for x in SO.findall("CCCD 001089012345, đt 0912.345.678")] == ["001089012345", "0912345678"]
    assert THU.findall("gửi a.b@co-quan.gov.vn nhé") == ["a.b@co-quan.gov.vn"]
