#!/usr/bin/env python3
"""Sinh deploy/charts/api-gateway/public-routes.yaml (DV-01-03): danh sách tường minh các tuyến không cần token người dùng.

Nguồn là các thao tác có `security: []` trong contracts/openapi/modules/*.yaml cộng với tệp viết tay
deploy/charts/api-gateway/tuyen-cong-khai-ngoai-hop-dong.yaml (quy ước chuẩn như /.well-known/jwks.json).
Mỗi dòng là một cặp đường dẫn chính xác + phương thức; đường dẫn có tham số {..} hoặc ký tự đại diện bị từ chối,
vì tuyến công khai chỉ được khớp chính xác. Chart chỉ dựng tuyến công khai từ tệp sinh ra này.

  python3 scripts/sinh-tuyen-cong-khai.py          # ghi lại tệp
  python3 scripts/sinh-tuyen-cong-khai.py --check  # CI: thoát 1 nếu tệp lệch với nguồn
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

import yaml

sys.dont_write_bytecode = True

REPO = Path(__file__).resolve().parent.parent
CHART = REPO / "deploy/charts/api-gateway"
OUT = CHART / "public-routes.yaml"
EXTRA = CHART / "tuyen-cong-khai-ngoai-hop-dong.yaml"
HEADER = (
    "# TỆP SINH TỰ ĐỘNG bởi scripts/sinh-tuyen-cong-khai.py, không sửa tay (DV-01-03).\n"
    "# Tuyến không cần token người dùng, khớp chính xác đường dẫn và phương thức. Nguồn: thao tác security: []\n"
    "# của contracts/openapi và tuyen-cong-khai-ngoai-hop-dong.yaml. Quy ước thêm tuyến: wiki 07.\n"
)


def _doi_chieu():
    spec = importlib.util.spec_from_file_location("doi_chieu", CHART / "tests/doi-chieu-hop-dong.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build() -> list[dict]:
    dc = _doi_chieu()
    rows = []
    for op in dc.operations():
        if not op["public"]:
            continue
        rows.append({"path": op["template"], "method": op["method"], "service": dc.service_of(op["owner"]),
                     "source": f'{op["file"]} {op["id"]}'})
    for e in yaml.safe_load(EXTRA.read_text(encoding="utf-8")) or []:
        if not e.get("reason") or not e.get("approvedBy"):
            raise SystemExit(f"{EXTRA.name}: {e.get('path')} thiếu reason hoặc approvedBy")
        rows.append({"path": e["path"], "method": e["method"].upper(), "service": e["service"],
                     "source": f"{EXTRA.name}: {e['reason']}"})
    seen = set()
    for r in rows:
        if any(c in r["path"] for c in "{}*?") or not r["path"].startswith("/"):
            raise SystemExit(f"{r['source']}: tuyến công khai phải là đường dẫn chính xác, không tham số: {r['path']}")
        if not r["service"]:
            raise SystemExit(f"{r['source']}: không xác định được service")
        k = (r["path"], r["method"])
        if k in seen:
            raise SystemExit(f"tuyến công khai trùng: {r['method']} {r['path']}")
        seen.add(k)
    return sorted(rows, key=lambda r: (r["path"], r["method"]))


def render(rows: list[dict]) -> str:
    return HEADER + yaml.safe_dump(rows, allow_unicode=True, sort_keys=False, width=200)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="chỉ kiểm tệp có khớp nguồn không")
    a = ap.parse_args()
    text = render(build())
    if a.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.relative_to(REPO)} lệch với hợp đồng: chạy python3 scripts/sinh-tuyen-cong-khai.py")
            return 1
        print(f"{OUT.relative_to(REPO)} khớp nguồn")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"Đã ghi {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
