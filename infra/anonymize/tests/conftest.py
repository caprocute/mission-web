import os
import sys
from pathlib import Path

import pytest

GOC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(GOC))
DATAGEN = GOC.parents[0] / "perf" / "datagen"
sys.path.insert(0, str(DATAGEN))

# Dùng chung CSDL thử của DL-08 (đã áp migration thật); có thể trỏ tệp khác bằng ANON_TEST_ENV.
TEP_ENV = Path(os.environ.get("ANON_TEST_ENV", DATAGEN / ".datagen-pg.env"))


@pytest.fixture(scope="session")
def ket_noi_thu():
    if not TEP_ENV.exists():
        pytest.skip("chưa dựng CSDL thử: chạy infra/perf/datagen/scripts/chuan-bi-csdl.sh")
    from datagen.nap import KetNoi

    return KetNoi.tu_moi_truong(TEP_ENV)
