#!/usr/bin/env python3
"""Đối chiếu kết quả mong đợi của UAT (du-lieu-mau/ket-qua-mong-doi.yaml) với bộ tính tham chiếu.

Bộ tính tham chiếu là data/golden/chay_ca_mau.py (DL-03), độc lập với scoring-service. Đạt khi mọi ca
khớp; dùng trước buổi UAT để chắc bảng tính tay không sai, và sau khi khoá kỳ để so với điểm trên hệ thống.

Dùng: python3 -I docs/uat/so-thu-nghiem/kiem_ket_qua_mong_doi.py   (mã thoát 1 nếu có ca không khớp)
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CA = HERE / "du-lieu-mau" / "ket-qua-mong-doi.yaml"


def main() -> int:
    sys.dont_write_bytecode = True  # không để lại __pycache__ trong data/golden
    spec = importlib.util.spec_from_file_location("chay_ca_mau", ROOT / "data" / "golden" / "chay_ca_mau.py")
    ref = importlib.util.module_from_spec(spec)
    sys.modules["chay_ca_mau"] = ref  # dataclass cần module đã đăng ký
    spec.loader.exec_module(ref)
    goi = ref.nap_goi(ROOT / "data" / "policy-pack" / "khoi-dang" / "quy-dinh-moi")
    bo = ref.chay_bo(goi, CA)
    sai = 0
    for k in bo.ket_qua:
        print(f"{'ĐẠT ' if k.dat else 'SAI '} {k.id}: " + ", ".join(f"{a}={ref.hien(k.thuc_te.get(a))}" for a in k.mong_doi))
        for s in k.sai:
            print(f"     - {s}")
        sai += 0 if k.dat else 1
    print(f"Khớp {len(bo.ket_qua) - sai}/{len(bo.ket_qua)} ca với bộ tính tham chiếu.")
    return 1 if sai else 0


if __name__ == "__main__":
    sys.exit(main())
