"""Dòng lệnh: python -m anonymize kiem-luat | chay.

  python -m anonymize kiem-luat                       # mọi cột C2/C3/pii/_enc/_bidx đều có luật
  ANON_PG_DSN="host=... dbname=... user=..." PGPASSWORD=... \\
  ANON_NGUON_ADDR=... ANON_NGUON_TOKEN_FILE=... ANON_DICH_ADDR=... ANON_DICH_TOKEN_FILE=... \\
    python -m anonymize chay --bien-ban bien-ban.json

Mã thoát: 0 khi biên bản DAT; 2 khi bước quét còn trúng (CHAN, không được chuyển sang staging);
3 khi thiếu luật. Mật khẩu, token chỉ lấy từ biến môi trường hoặc tệp, không nhận qua tham số.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .chay import ThieuLuat, chay
from .danh_muc import quet_migration
from .khoa import OpenBaoTransit
from .luat import TEP_LUAT, cot_thieu_luat, doc_luat


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="anonymize", description="Ẩn danh hoá bản sao CSDL (DL-15)")
    sub = p.add_subparsers(dest="lenh", required=True)
    k = sub.add_parser("kiem-luat")
    k.add_argument("--luat", type=Path, default=TEP_LUAT)
    c = sub.add_parser("chay")
    c.add_argument("--luat", type=Path, default=TEP_LUAT)
    c.add_argument("--bien-ban", type=Path, required=True)
    a = p.parse_args(argv)
    if a.lenh == "kiem-luat":
        thieu = cot_thieu_luat(doc_luat(a.luat), quet_migration())
        for t in thieu:
            print(f"thiếu luật: {t}", file=sys.stderr)
        return 3 if thieu else 0
    import psycopg

    with psycopg.connect(os.environ["ANON_PG_DSN"]) as kn:
        try:
            bb = chay(kn, OpenBaoTransit.tu_moi_truong("ANON_NGUON"), OpenBaoTransit.tu_moi_truong("ANON_DICH"),
                      tep_luat=a.luat, tep_bien_ban=a.bien_ban)
        except ThieuLuat as e:
            print(e, file=sys.stderr)
            return 3
    print(json.dumps({"ket_luan": bb["ket_luan"], "so_trung": len(bb["quet"])}, ensure_ascii=False))
    return 0 if bb["ket_luan"] == "DAT" else 2


if __name__ == "__main__":
    sys.exit(main())
