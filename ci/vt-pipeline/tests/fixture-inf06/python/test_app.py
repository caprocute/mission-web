import pytest

from app import xep_loai


@pytest.mark.parametrize("diem,mong", [(95, "HTXSNV"), (75, "HTTNV"), (55, "HTNV"), (10, "KHTNV")])
def test_xep_loai(diem, mong):
    assert xep_loai(diem) == mong
