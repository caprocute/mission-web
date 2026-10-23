#!/usr/bin/env python3
"""Kiểm mọi tuyến của cổng đều có xác thực hoặc nằm trong danh sách công khai tường minh (DV-01-03).

Dựng chart api-gateway bằng `helm template` (hoặc đọc --rendered), rồi với mỗi mục http của mọi ApisixRoute:
  * hoặc tham chiếu plugin_config protected-api (hoặc protected-api-p<lớp> của lớp ưu tiên, DV-01-02) cùng
    namespace, và plugin_config đó có plugin kiểm token
    (openid-connect, hoặc vt-claims với verify: true);
  * hoặc khớp chính xác đúng một dòng của public-routes.yaml: một đường dẫn không ký tự đại diện, một phương thức.
Tuyến không thoả bị báo kèm tên. Kiểm thêm public-routes.yaml khớp hợp đồng (sinh-tuyen-cong-khai.py --check) và
bảng tuyến khớp hợp đồng, thao tác chỉ dành cho client nội bộ không ra cổng ngoài (tests/doi-chieu-hop-dong.py).

  python3 scripts/kiem-tuyen-cong-khai.py                      # chart với tests/values-ci.yaml
  python3 scripts/kiem-tuyen-cong-khai.py --rendered x.yaml    # manifest có sẵn (fixture âm tính)
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

import yaml

sys.dont_write_bytecode = True

REPO = Path(__file__).resolve().parent.parent
CHART = REPO / "deploy/charts/api-gateway"


def has_auth(plugins) -> bool:
    for p in plugins or []:
        if p.get("enable") is False:
            continue
        if p.get("name") == "openid-connect":
            return True
        if p.get("name") == "vt-claims" and (p.get("config") or {}).get("verify") is True:
            return True
    return False


def check(docs: list[dict], public: list[dict]) -> tuple[list[str], int]:
    configs = {}
    for d in docs:
        if d.get("kind") == "ApisixPluginConfig":
            configs[(d["metadata"]["namespace"], d["metadata"]["name"])] = has_auth(d["spec"].get("plugins"))
    allowed = {(p["path"], p["method"].upper()) for p in public}
    errors, n = [], 0
    for d in docs:
        if d.get("kind") != "ApisixRoute":
            continue
        ns, rname = d["metadata"]["namespace"], d["metadata"]["name"]
        for h in d["spec"].get("http") or []:
            n += 1
            name = f"{ns}/{rname}#{h.get('name')}"
            pc = h.get("plugin_config_name")
            if pc:
                if not re.fullmatch(r"protected-api(-p[13])?", pc):
                    errors.append(f"{name}: plugin_config {pc} không phải protected-api")
                elif not configs.get((ns, pc)):
                    errors.append(f"{name}: {pc} ở namespace {ns} thiếu hoặc không có plugin kiểm token")
                continue
            m = h.get("match") or {}
            paths, methods = m.get("paths") or [], [x.upper() for x in m.get("methods") or []]
            if (
                len(paths) == 1 and len(methods) == 1 and not any(c in paths[0] for c in "*{}?")
                and not m.get("exprs") and (paths[0], methods[0]) in allowed
            ):
                continue
            errors.append(f"{name}: không có xác thực và không khớp chính xác dòng nào của public-routes.yaml "
                          f"(paths={paths}, methods={methods or 'mọi phương thức'})")
    return errors, n


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rendered")
    ap.add_argument("-f", "--values", action="append", default=[])
    a = ap.parse_args()
    if a.rendered:
        text = Path(a.rendered).read_text(encoding="utf-8")
    else:
        cmd = ["helm", "template", "g", str(CHART)]
        for v in a.values or [str(CHART / "tests/values-ci.yaml")]:
            cmd += ["-f", v]
        text = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    docs = [d for d in yaml.safe_load_all(text) if d]
    public = yaml.safe_load((CHART / "public-routes.yaml").read_text(encoding="utf-8")) or []
    errors, n = check(docs, public)
    for e in errors:
        print("  [LỖI]", e)
    rc = 1 if errors else 0
    if not a.rendered:
        for extra in ([sys.executable, str(REPO / "scripts/sinh-tuyen-cong-khai.py"), "--check"],
                      [sys.executable, str(CHART / "tests/doi-chieu-hop-dong.py"), "--rendered", "/dev/stdin"]):
            r = subprocess.run(extra, input=text, text=True, capture_output=True)
            print("  " + (r.stdout.strip().splitlines() or [""])[-1])
            if r.returncode:
                print(r.stdout)
                rc = 1
    print(f"Kiểm {n} tuyến: {len(errors)} tuyến không xác thực ngoài danh sách công khai")
    return rc


if __name__ == "__main__":
    sys.exit(main())
