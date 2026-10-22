import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from datagen.ho_so import doc_ho_so  # noqa: E402
from datagen.sinh import BoSinh  # noqa: E402

HE_SO_CI = 0.01  # hồ sơ compact ở 1%: 500 tài khoản, 15.000 nhiệm vụ, chạy vài giây


@pytest.fixture(scope="session")
def ho_so_ci():
    return doc_ho_so("cell-compact", HE_SO_CI)


@pytest.fixture(scope="session")
def ban_sinh(tmp_path_factory, ho_so_ci):
    goc = tmp_path_factory.mktemp("sinh")
    thong_ke = BoSinh(ho_so_ci, goc).sinh_tat_ca()
    return goc, thong_ke
