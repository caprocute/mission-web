"""Lọc tài liệu YAML từ stdin theo Kind hoặc Kind/tên (đối số), bỏ trường namespace rỗng; in ra stdout."""
import sys

import yaml

muon = sys.argv[1:]
ra = []
for d in yaml.safe_load_all(sys.stdin):
    if not d:
        continue
    k, n = d["kind"], d["metadata"]["name"]
    if k in muon or f"{k}/{n}" in muon:
        ra.append(d)
yaml.safe_dump_all(ra, sys.stdout, allow_unicode=True, sort_keys=False)
