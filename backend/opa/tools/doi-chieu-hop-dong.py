#!/usr/bin/env python3
"""Đối chiếu `x-mission.roles/scope` của hợp đồng OpenAPI với chính sách okrkpi.authz (NEN-040).

Với mỗi thao tác trong contracts/openapi/modules/*.yaml và mỗi vai trò ở `x-mission.roles`, công cụ hỏi chính
sách thật (opa) hai câu:
  1. API: vai trò có được gọi phương thức đó ở module đó không (`input.request`).
  2. Phạm vi: trong các chức năng của ma trận thuộc `x-mission.scr`, có chức năng nào cho vai trò đó thao tác
     trên một đối tượng nằm đúng phạm vi `x-mission.scope` không (`input.action` + tài nguyên dựng theo phạm vi).
Chỗ lệch được liệt kê để người phụ trách quyết định; công cụ không sửa hợp đồng hay ma trận.

Dùng:
  python3 backend/opa/tools/doi-chieu-hop-dong.py [--opa /duong-dan/opa] [--json kết-quả.json] [--strict]
Cần Python 3, PyYAML và opa 1.x. Mặc định luôn trả 0 (chỉ báo cáo); --strict trả 1 khi có lệch.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
OPA_DIR = HERE.parent
ROOT = OPA_DIR.parent.parent
CONTRACTS = ROOT / "contracts" / "openapi" / "modules"
DATA = OPA_DIR / "okrkpi" / "generated" / "data.json"
METHODS = ("get", "post", "put", "patch", "delete")

# Ký hiệu phạm vi của hợp đồng khác ma trận: README hợp đồng dùng ĐvD, ma trận dùng Cd (cùng nghĩa
# "đơn vị và cây cấp dưới"); AC-11 trong hợp đồng là `service_client`, ma trận ghi "(client credentials)".
SCOPE_ALIAS = {"ĐvD": "Cd"}
ROLE_ALIAS = {"service_client": "(client credentials)"}
SCOPE_RE = re.compile(r"(ĐvD|Đv|Gn|Cd|Tn|B|N)(?:\s*\(([^)]*)\))?")

ORG = {"t1": {"subtree": {"u1": ["u1", "u11", "u12"], "u11": ["u11"], "u2": ["u2"]}}}
RESOURCE_AT = {
    "B": {"owner_unit_id": "u1", "owner_ids": ["nv1"]},
    "Gn": {"owner_unit_id": "u2", "designated_ids": ["nv1"]},
    "Đv": {"owner_unit_id": "u1"},
    "Cd": {"owner_unit_id": "u11"},
    "Tn": {"owner_unit_id": "u2"},
    "N": {"owner_unit_id": "u2"},
}


def subject(role):
    if role == "(client credentials)":
        return {"kind": "client", "client_id": "doi-chieu", "tenant_id": "t1"}
    return {"user_id": "nv1", "tenant_id": "t1", "unit_id": "u1", "roles": [role], "acr": "aal2",
            "auth_time": 1000, "flows": ["DANG", "NHA_NUOC"]}


def parse_scope(raw):
    """'Cd (appraiser), Tn (inspector)' -> ({'appraiser': ['Cd'], 'inspector': ['Tn']}, []);
    'B; Gn' -> ({}, ['B', 'Gn'])."""
    per_role, common = defaultdict(list), []
    for code, roles in SCOPE_RE.findall(str(raw)):
        code = SCOPE_ALIAS.get(code, code)
        if roles.strip():
            for r in (x.strip() for x in roles.split(",")):
                per_role[r].append(code)
        else:
            common.append(code)
    return per_role, common


def operations():
    for f in sorted(CONTRACTS.glob("*.yaml")):
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        for path, ops in sorted((doc.get("paths") or {}).items()):
            for method in METHODS:
                op = ops.get(method)
                if isinstance(op, dict) and "x-mission" in op:
                    yield f.name, path, method.upper(), op.get("operationId", ""), op["x-mission"]


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--opa", default="opa")
    ap.add_argument("--json")
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args(argv)

    gen = json.loads(DATA.read_text(encoding="utf-8"))
    by_scr = defaultdict(list)
    for key, f in gen["functions"].items():
        by_scr[f["scr"]].append(key)

    cases, checks, issues = [], [], []
    for fname, path, method, op_id, x in operations():
        module = path.strip("/").split("/")[0]
        raw_scope = x.get("scope")
        per_role, common = parse_scope(raw_scope)
        where = f"{fname} {method} {path}"
        if raw_scope is not None and not (per_role or common):
            issues.append({"loai": "phạm vi không chuẩn", "thao_tac": where, "chi_tiet": f"scope = {raw_scope!r}"})
        if any(c in str(raw_scope) for c in SCOPE_ALIAS):
            issues.append({"loai": "ký hiệu phạm vi khác ma trận", "thao_tac": where,
                           "chi_tiet": f"scope = {raw_scope!r}; ma trận dùng Cd thay cho ĐvD"})
        for role_raw in x.get("roles") or []:
            role = ROLE_ALIAS.get(role_raw, role_raw)
            if role_raw in ROLE_ALIAS:
                issues.append({"loai": "tên vai trò khác ma trận", "thao_tac": where,
                               "chi_tiet": f"{role_raw} (ma trận: {role})"})
            if role not in gen["roles"]:
                issues.append({"loai": "vai trò không có trong ma trận", "thao_tac": where, "chi_tiet": role_raw})
                continue
            scopes = per_role.get(role_raw) or common
            if not scopes and per_role:
                issues.append({"loai": "phạm vi không nêu cho vai trò", "thao_tac": where,
                               "chi_tiet": f"{role_raw}; scope = {raw_scope!r}"})
            cid = len(checks)
            keys = sorted(k for s in x.get("scr") or [] for k in by_scr.get(s, [])
                          if role in gen["functions"][k]["grants"])
            checks.append({"thao_tac": where, "operationId": op_id, "module": module, "method": method,
                           "role": role_raw, "scopes": scopes, "functions": keys,
                           "scr": ", ".join(x.get("scr") or [])})
            cases.append({"id": f"{cid}:api", "input": {"subject": subject(role),
                          "request": {"module": module, "method": method},
                          "resource": {"tenant_id": "t1"}, "context": {"now": 1000}}})
            for s in scopes:
                for k in keys:
                    cases.append({"id": f"{cid}:fn:{s}:{k}", "input": {
                        "subject": subject(role), "action": k,
                        "resource": dict(RESOURCE_AT[s], tenant_id="t1", classification="C1"),
                        "context": {"now": 1000}}})

    with tempfile.TemporaryDirectory() as tmp:
        # Dữ liệu sinh ra được đặt lại đúng đường dẫn data.okrkpi.generated như trong bundle
        gdir = Path(tmp, "okrkpi", "generated")
        gdir.mkdir(parents=True)
        (gdir / "data.json").write_text(DATA.read_text(encoding="utf-8"), encoding="utf-8")
        Path(tmp, "data.json").write_text(json.dumps({"org": ORG, "doi_chieu": {"cases": cases}}), encoding="utf-8")
        # `with input as` đặt trong hàm của một module; đặt thẳng trong truy vấn eval thì OPA 1.4 trả rỗng
        Path(tmp, "doi_chieu.rego").write_text(
            "package doi_chieu_hop_dong\n\nimport rego.v1\n\n"
            "decide(i) := d if {\n\td := data.okrkpi.authz.decision with input as i\n}\n\n"
            "results := {c.id: decide(c.input) | some c in data.doi_chieu.cases}\n", encoding="utf-8")
        out = subprocess.run([a.opa, "eval", "--format", "json", "-d", str(OPA_DIR / "okrkpi" / "authz.rego"),
                              "-d", tmp, "data.doi_chieu_hop_dong.results"],
                             capture_output=True, text=True, check=False)
    if out.returncode != 0:
        print(out.stderr, file=sys.stderr)
        return 2
    res = json.loads(out.stdout)["result"][0]["expressions"][0]["value"]

    for cid, c in enumerate(checks):
        api = res[f"{cid}:api"]
        if not api["allow"]:
            issues.append({"loai": "API: ma trận không cho phương thức", "thao_tac": c["thao_tac"],
                           "chi_tiet": f"{c['role']} {c['method']} /{c['module']} -> {sorted(api['deny'])}"})
        if not c["functions"]:
            issues.append({"loai": "không có chức năng trong SCR cấp quyền cho vai trò", "thao_tac": c["thao_tac"],
                           "chi_tiet": f"{c['role']}; scr = {c['scr'] or '(trống)'}"})
            continue
        ok = [s for s in c["scopes"] if any(res[f"{cid}:fn:{s}:{k}"]["allow"] for k in c["functions"])]
        if c["scopes"] and not ok:
            grant_scopes = sorted({gen["functions"][k]["grants"][ROLE_ALIAS.get(c["role"], c["role"])]["scope"] or
                                   gen["functions"][k]["grants"][ROLE_ALIAS.get(c["role"], c["role"])]["level"]
                                   for k in c["functions"]})
            issues.append({"loai": "phạm vi hợp đồng không được ma trận cho", "thao_tac": c["thao_tac"],
                           "chi_tiet": f"{c['role']}: hợp đồng {'/'.join(c['scopes'])}, ma trận "
                                       f"{'/'.join(grant_scopes)} ({', '.join(c['functions'])})"})

    seen, uniq = set(), []
    for i in issues:
        k = (i["loai"], i["thao_tac"], i["chi_tiet"])
        if k not in seen:
            seen.add(k)
            uniq.append(i)
    print(f"Đã đối chiếu {len({c['thao_tac'] for c in checks})} thao tác, {len(checks)} cặp thao tác x vai trò, "
          f"{len(cases)} lần hỏi chính sách.")
    for loai, n in Counter(i["loai"] for i in uniq).most_common():
        print(f"  {loai}: {n}")
    for i in uniq:
        print(f"- [{i['loai']}] {i['thao_tac']}: {i['chi_tiet']}")
    if a.json:
        Path(a.json).write_text(json.dumps(uniq, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if a.strict and uniq else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
