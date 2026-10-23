#!/usr/bin/env python3
"""Đối chiếu bảng tuyến của chart api-gateway với hợp đồng OpenAPI (DV-01-01, DV-01-03).

Kịch bản dựng ApisixRoute bằng `helm template` (hoặc đọc tệp đã dựng qua --rendered), rồi với mỗi thao tác
của contracts/openapi/modules/*.yaml chọn tuyến thắng theo cách APISIX khớp (tiền tố `*` hoặc chính xác,
phương thức, biểu thức Path RegexMatch, priority cao thắng) và kiểm:

  1. mọi thao tác khớp đúng một tuyến thắng, trừ tiền tố khai trong notInCell của values.yaml;
  2. tuyến thắng trỏ tới đúng service sở hữu theo x-mission.owner (MOD-* về mission-core);
  3. thao tác chỉ dành cho client nội bộ (security chỉ serviceClient) rơi vào tuyến chặn (deny, 404),
     còn thao tác của người dùng không bao giờ rơi vào tuyến chặn;
  4. hai tuyến cùng priority cùng khớp một thao tác là mơ hồ, báo lỗi.

Thoát mã 1 và in từng lỗi khi có sai lệch. Chạy được trong CI, không cần cụm.
"""
from __future__ import annotations

import argparse
import glob
import re
import subprocess
import sys
from pathlib import Path

import yaml

CHART = Path(__file__).resolve().parent.parent
REPO = CHART.parent.parent.parent
METHODS = ("get", "put", "post", "delete", "patch", "head", "options")

# x-mission.owner → Service Kubernetes (wiki 19, bảng namespace). MOD-* thuộc mission-core (SVC-05).
OWNER_SERVICE = {
    "SVC-02": "keycloak",
    "SVC-04": "org-service",
    "SVC-05": "mission-core",
    "SVC-06": "workflow-service",
    "SVC-07": "scoring-service",
    "SVC-08": "evidence-service",
    "SVC-09": "ledger-service",
    "SVC-10": "esign-service",
    "SVC-11": "document-service",
    "SVC-12": "notification-service",
    "SVC-14": "ai-gateway",
    "SVC-15": "analytics-api",
    "SVC-16": "search-indexer",
}


def service_of(owner: str) -> str | None:
    if owner and owner.startswith("MOD-"):
        return "mission-core"
    return OWNER_SERVICE.get(owner)


def render(values: list[str]) -> str:
    cmd = ["helm", "template", "g", str(CHART)]
    for v in values:
        cmd += ["-f", v]
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def routes_from(rendered: str) -> list[dict]:
    out = []
    for doc in yaml.safe_load_all(rendered):
        if not doc or doc.get("kind") != "ApisixRoute":
            continue
        for h in doc["spec"]["http"]:
            backends = h.get("backends") or []
            svc = backends[0]["serviceName"] if backends else None
            if svc and (svc.endswith("-stable") or svc.endswith("-canary")):
                svc = svc.rsplit("-", 1)[0]
            m = h["match"]
            out.append(
                {
                    "name": f'{doc["metadata"]["namespace"]}/{h["name"]}',
                    "paths": m.get("paths") or [],
                    "methods": [x.upper() for x in m.get("methods") or []],
                    "regex": [e["value"] for e in m.get("exprs") or [] if e.get("op") == "RegexMatch"],
                    "priority": int(h.get("priority") or 0),
                    "service": svc,
                    "deny": any(p.get("name") == "fault-injection" for p in h.get("plugins") or []),
                    "pluginConfig": h.get("plugin_config_name"),
                }
            )
    return out


def path_matches(pattern: str, uri: str) -> bool:
    if pattern.endswith("*"):
        return uri.startswith(pattern[:-1])
    return uri == pattern


def matches(route: dict, method: str, uri: str) -> bool:
    if route["methods"] and method not in route["methods"]:
        return False
    if not any(path_matches(p, uri) for p in route["paths"]):
        return False
    return all(re.search(rx, uri) for rx in route["regex"])


def operations() -> list[dict]:
    ops = []
    for f in sorted(glob.glob(str(REPO / "contracts/openapi/modules/*.yaml"))):
        d = yaml.safe_load(open(f, encoding="utf-8"))
        base = (d.get("servers") or [{"url": ""}])[0]["url"].rstrip("/")
        doc_sec = d.get("security") or []
        for path, item in (d.get("paths") or {}).items():
            for m, op in item.items():
                if m not in METHODS:
                    continue
                sec = op.get("security", doc_sec)
                schemes = {k for req in sec for k in req}
                ops.append(
                    {
                        "file": Path(f).name,
                        "id": op.get("operationId") or f"{m} {path}",
                        "method": m.upper(),
                        "template": base + path,
                        # Tham số đường dẫn thay bằng mẫu không chứa "/" để thử khớp như một yêu cầu thật.
                        "uri": base + re.sub(r"\{[^}]+\}", "x1", path),
                        "owner": (op.get("x-mission") or {}).get("owner"),
                        "internal": schemes == {"serviceClient"},
                        "public": sec == [] or sec == [{}],
                    }
                )
    return ops


def check(ops: list[dict], routes: list[dict], not_in_cell: list[str]) -> list[str]:
    errors = []
    for op in ops:
        where = f'{op["file"]} {op["method"]} {op["template"]} ({op["id"]})'
        if any(op["uri"] == p or op["uri"].startswith(p.rstrip("/") + "/") for p in not_in_cell):
            continue
        hit = [r for r in routes if matches(r, op["method"], op["uri"])]
        if not hit:
            errors.append(f"{where}: không khớp tuyến nào")
            continue
        top = max(r["priority"] for r in hit)
        winners = [r for r in hit if r["priority"] == top]
        if len(winners) > 1:
            errors.append(f'{where}: mơ hồ giữa {", ".join(r["name"] for r in winners)} (cùng priority {top})')
            continue
        w = winners[0]
        if op["internal"]:
            if not w["deny"]:
                errors.append(f'{where}: thao tác chỉ cho client nội bộ lộ ra cổng ngoài qua tuyến {w["name"]}')
            continue
        if w["deny"]:
            errors.append(f'{where}: thao tác của người dùng bị tuyến chặn {w["name"]} chặn nhầm')
            continue
        want = service_of(op["owner"])
        if want is None:
            errors.append(f'{where}: owner {op["owner"]} chưa có service trong OWNER_SERVICE')
        elif w["service"] != want:
            errors.append(f'{where}: tới {w["service"]} (tuyến {w["name"]}) nhưng owner {op["owner"]} là {want}')
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rendered", help="tệp manifest đã dựng; bỏ trống thì gọi helm template")
    ap.add_argument("-f", "--values", action="append", default=[], help="values thêm cho helm template")
    a = ap.parse_args()
    rendered = Path(a.rendered).read_text(encoding="utf-8") if a.rendered else render(a.values or [str(CHART / "tests/values-ci.yaml")])
    values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
    not_in_cell = [x["prefix"] for x in values.get("notInCell") or []]
    ops, routes = operations(), routes_from(rendered)
    errors = check(ops, routes, not_in_cell)
    for e in errors:
        print("  [LỆCH]", e)
    skipped = sum(1 for o in ops if any(o["uri"].startswith(p) for p in not_in_cell))
    print(f"Đối chiếu {len(ops)} thao tác với {len(routes)} tuyến: {len(errors)} lệch, {skipped} thao tác ở lớp toàn cục hoặc không ra cổng")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
