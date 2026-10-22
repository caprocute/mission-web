"""Dòng lệnh: python -m datagen sinh|nap|kiem.

  python -m datagen sinh --ho-so cell-l --he-so 0.001 --ra out/l-0001
  python -m datagen nap --vao out/l-0001 [--thay-the]
  python -m datagen kiem --ho-so cell-l --he-so 0.001 --vao out/l-0001
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .ho_so import doc_ho_so
from .kiem import quet_du_lieu_ca_nhan, so_sanh_phan_phoi
from .nap import KetNoi, nap
from .sinh import BoSinh

TEP_ENV = Path(__file__).resolve().parents[1] / ".datagen-pg.env"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="datagen", description="Bộ sinh dữ liệu thử tải DL-08")
    sub = p.add_subparsers(dest="lenh", required=True)
    s = sub.add_parser("sinh")
    s.add_argument("--ho-so", default="cell-l")
    s.add_argument("--he-so", type=float, default=0.001)
    s.add_argument("--hat-giong", type=int)
    s.add_argument("--ra", type=Path, required=True)
    n = sub.add_parser("nap")
    n.add_argument("--vao", type=Path, required=True)
    n.add_argument("--thay-the", action="store_true")
    k = sub.add_parser("kiem")
    k.add_argument("--ho-so", default="cell-l")
    k.add_argument("--he-so", type=float, default=0.001)
    k.add_argument("--vao", type=Path, required=True)
    a = p.parse_args(argv)

    if a.lenh == "sinh":
        hs = doc_ho_so(a.ho_so, a.he_so, a.hat_giong)
        tk = BoSinh(hs, a.ra).sinh_tat_ca()
        print(json.dumps({t: v["dem"] | {"giay_sinh": v["giay_sinh"]} for t, v in tk["tenant"].items()},
                         ensure_ascii=False, indent=2))
        return 0
    if a.lenh == "nap":
        print(json.dumps(nap(KetNoi.tu_moi_truong(TEP_ENV), a.vao, a.thay_the), ensure_ascii=False, indent=2))
        return 0
    hs = doc_ho_so(a.ho_so, a.he_so)
    tk = json.loads((a.vao / "thong-ke.json").read_text(encoding="utf-8"))
    loi = 0
    for ten, (thuc, kv, d) in so_sanh_phan_phoi(hs, tk).items():
        dat = d <= 0.05
        loi += not dat
        print(f"{'ĐẠT ' if dat else 'LỆCH'} {ten}: thực tế {thuc:.4g}, hồ sơ {kv:.4g}, lệch {d:.1%}")
    vp = quet_du_lieu_ca_nhan(a.vao)
    print(f"Quét mẫu dữ liệu cá nhân thật: {len(vp)} vi phạm")
    for v in vp[:20]:
        print("  " + v)
    return 1 if loi or vp else 0


if __name__ == "__main__":
    sys.exit(main())
