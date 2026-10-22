"""Nạp vào PostgreSQL 17 đã áp migration thật (scripts/chuan-bi-csdl.sh); bỏ qua khi chưa dựng."""

import pytest

from datagen.__main__ import TEP_ENV
from datagen.nap import KetNoi, nap

pytestmark = pytest.mark.csdl


@pytest.fixture(scope="module")
def ket_noi():
    if not TEP_ENV.exists():
        pytest.skip("chưa dựng CSDL thử: chạy scripts/chuan-bi-csdl.sh")
    return KetNoi.tu_moi_truong(TEP_ENV)


def test_nap_khong_vi_pham_rang_buoc_rls_va_nap_lai_khong_trung_khoa(ket_noi, ban_sinh):
    goc, tk = ban_sinh
    lan1 = nap(ket_noi, goc, thay_the=True)
    for tenant, kq in lan1.items():
        assert kq["rls"] == []
        sinh = tk["tenant"][tenant]["dem"]
        assert {b: n for b, n in kq["dem"].items()} == {b: sinh[b] for b in kq["dem"]}
    with pytest.raises(RuntimeError, match="--thay-the"):
        nap(ket_noi, goc)
    lan2 = nap(ket_noi, goc, thay_the=True)
    assert {t: v["dem"] for t, v in lan2.items()} == {t: v["dem"] for t, v in lan1.items()}
