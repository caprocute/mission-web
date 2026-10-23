#!/usr/bin/env python3
"""Mô phỏng bộ sinh của ApplicationSet và kiểm cấu trúc kho GitOps (INF-04), thay cho "argocd appset generate"
khi không có máy chủ Argo CD. Đọc bản render của chart deploy/argocd (đối số 1) và các tệp trong deploy/.

Kiểm:
  1. Tệp cell.yaml đủ trường, đợt phát hành đúng OPS-02, cellsExclude khớp các cell do Argo CD khác quản lý.
  2. Danh sách services phủ đúng dependency của vt-platform, project tồn tại.
  3. Số Application = số cell × số TTDL × số service, tên không trùng, nhãn wave thuộc một bước RollingSync.
  4. digests.yaml của môi trường có mục cho mọi service nhận digest.
  5. Biểu thức goTemplate của từng ApplicationSet dịch được với tham số của mọi cell (missingkey=error).
In ra danh sách tham số Helm cho bước render thử (đối số --xuat).
"""
import glob
import json
import os
import re
import sys

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DEPLOY = os.path.join(GOC, "deploy")
# OPS-02 (TL-04 chương 11): đợt 0 CELL-NB, 1 CELL-G4, 2 CELL-G1..G3 và CELL-SH, 3 CELL-HN và CELL-DTW, 4 CELL-HCM.
DOT_OPS02 = {"nb": "0", "g4": "1", "g1": "2", "g2": "2", "g3": "2", "sh": "2", "hn": "3", "dtw": "3", "hcm": "4"}
TRUONG_CELL = {"name", "displayName", "sizing", "tenancy", "primarySite", "standbySite", "releaseWave", "argocd", "silos"}

loi = []


def sai(msg):
    loi.append(msg)


def dich(mau: str, ts: dict) -> str:
    """Dịch tập con goTemplate mà chart dùng: .a.b, lower, index, if eq ... else ... end."""
    def gia_tri(bt):
        bt = bt.strip()
        m = re.fullmatch(r'\(index \.(\w+) "([^"]+)"\)\.(\w+)', bt)
        if m:
            return ts[m.group(1)][m.group(2)][m.group(3)]
        m = re.fullmatch(r"lower (.+)", bt)
        if m:
            return str(gia_tri(m.group(1))).lower()
        if bt.startswith("."):
            v = ts
            for p in bt[1:].split("."):
                if not isinstance(v, dict) or p not in v:
                    raise KeyError(f"thiếu khoá {bt}")
                v = v[p]
            return v
        raise ValueError(f"biểu thức chưa hỗ trợ: {bt}")

    def if_eq(m):
        a, b, dung, sai_ = m.groups()
        return dung if gia_tri(a) == gia_tri(b) else sai_

    mau = re.sub(r"\{\{ if eq (\S+) (\S+) \}\}(.*?)\{\{ else \}\}(.*?)\{\{ end \}\}", if_eq, mau)
    return re.sub(r"\{\{(.+?)\}\}", lambda m: str(gia_tri(m.group(1))), mau)


def dich_cay(nut, ts):
    if isinstance(nut, dict):
        return {dich(k, ts): dich_cay(v, ts) for k, v in nut.items()}
    if isinstance(nut, list):
        return [dich_cay(x, ts) for x in nut]
    return dich(nut, ts) if isinstance(nut, str) else nut


def main():
    render = [d for d in yaml.safe_load_all(open(sys.argv[1], encoding="utf-8")) if d]
    xuat = sys.argv[3] if len(sys.argv) > 3 and sys.argv[2] == "--xuat" else None
    values = yaml.safe_load(open(os.path.join(DEPLOY, "argocd", "values.yaml"), encoding="utf-8"))

    # 1. cell.yaml
    cells = {}
    for f in sorted(glob.glob(os.path.join(DEPLOY, "cells", "*", "cell.yaml"))):
        c = yaml.safe_load(open(f, encoding="utf-8"))["cell"]
        ten = os.path.basename(os.path.dirname(f))
        if set(c) != TRUONG_CELL:
            sai(f"{f}: trường {sorted(set(c) ^ TRUONG_CELL)}")
        if c["name"] != ten:
            sai(f"{f}: name {c['name']} khác thư mục")
        if DOT_OPS02.get(ten) != c["releaseWave"]:
            sai(f"{ten}: đợt {c['releaseWave']} khác OPS-02 ({DOT_OPS02.get(ten)})")
        cells[ten] = c
    if set(cells) != set(DOT_OPS02):
        sai(f"cell thiếu hoặc thừa: {sorted(set(cells) ^ set(DOT_OPS02))}")
    ngoai = sorted(t for t, c in cells.items() if c["argocd"] != values["instance"])
    if ngoai != sorted(values["cellsExclude"]):
        sai(f"cellsExclude {values['cellsExclude']} khác các cell của Argo CD khác: {ngoai}")
    cell_dung = {t: c for t, c in cells.items() if t not in values["cellsExclude"]}

    # 2. services và vt-platform
    chart = yaml.safe_load(open(os.path.join(DEPLOY, "charts", "vt-platform", "Chart.yaml"), encoding="utf-8"))
    deps = {d["name"] for d in chart["dependencies"]}
    svc = [s["name"] for s in values["services"]]
    # Service có khoá chart dùng chart riêng ngoài vt-platform (security-cell, INF-19), không trùng dependency.
    trong = [s["name"] for s in values["services"] if not s.get("chart")]
    if set(trong) != deps or len(trong) != len(deps):
        sai(f"services khác dependency vt-platform: {sorted(set(trong) ^ deps)}")
    for s in values["services"]:
        if s.get("chart") and (s["name"] in deps or not os.path.isfile(os.path.join(DEPLOY, s["chart"], "Chart.yaml"))):
            sai(f"{s['name']}: chart riêng {s['chart']} thiếu Chart.yaml hoặc trùng dependency vt-platform")
        if s["namespace"] not in values["projects"][s["project"]]["namespaces"]:
            sai(f"{s['name']}: namespace {s['namespace']} không thuộc project {s['project']}")
    for s in values["services"]:
        if s["project"] not in values["projects"]:
            sai(f"{s['name']}: project {s['project']} chưa khai")

    # 4. digests
    digests = yaml.safe_load(open(os.path.join(DEPLOY, "environments", values["environment"], "digests.yaml"),
                                  encoding="utf-8"))
    for s in values["services"]:
        if s.get("image") and s["name"] not in digests["images"]:
            sai(f"digests.yaml của {values['environment']} thiếu {s['name']}")

    # 3, 5. mô phỏng sinh Application
    buoc = []
    for d in render:
        if d["kind"] == "ApplicationSet":
            buoc = [st["matchExpressions"][0]["values"] for st in d["spec"]["strategy"]["rollingSync"]["steps"]]
            break
    dot_co_buoc = {w for b in buoc for w in b}
    apps, tham_so = [], []
    for d in render:
        if d["kind"] != "ApplicationSet":
            continue
        gen = d["spec"]["generators"][0]["matrix"]["generators"]
        sites = [e["site"] for e in gen[1]["matrix"]["generators"][0]["list"]["elements"]]
        for t, c in sorted(cell_dung.items()):
            for site in sites:
                ts = {"cell": c, "site": site, "images": digests["images"]}
                try:
                    a = dich_cay(d["spec"]["template"], ts)
                except (KeyError, ValueError, TypeError) as e:
                    sai(f"{d['metadata']['name']} × {t}-{site}: {e}")
                    continue
                apps.append(a)
                if a["metadata"]["labels"]["wave"] not in dot_co_buoc:
                    sai(f"{a['metadata']['name']}: wave {a['metadata']['labels']['wave']} không thuộc bước RollingSync")
                if site == sites[0] and t == "g4":
                    tham_so.append({"ten": a["metadata"]["name"], "helm": a["spec"]["source"]["helm"],
                                    "path": a["spec"]["source"]["path"]})
    mong = len(cell_dung) * len(values["sites"]) * len(svc)
    ten = [a["metadata"]["name"] for a in apps]
    if len(apps) != mong:
        sai(f"sinh {len(apps)} Application, mong {mong}")
    if len(set(ten)) != len(ten):
        sai("tên Application trùng")
    if xuat:
        json.dump(tham_so, open(xuat, "w", encoding="utf-8"), ensure_ascii=False)
    for m in loi:
        print("  LỖI:", m)
    print(f"{len(cell_dung)} cell × {len(values['sites'])} TTDL × {len(svc)} service = {len(apps)} Application"
          f" (mong {mong}); đợt RollingSync {[b[0] for b in buoc]}")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
