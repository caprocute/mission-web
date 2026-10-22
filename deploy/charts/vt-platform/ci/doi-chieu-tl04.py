#!/usr/bin/env python3
"""Đối chiếu bản render vt-platform (values hạng L) với bảng TL-04 mục 5.2 (INF-02).

Dùng: doi-chieu-tl04.py <bản render.yaml> [--bang ci/bang-tl04-5-2.yaml] [--lech ci/lech-da-ghi.yaml] [--md <tệp ra>]
In bảng Markdown; trả mã 1 khi có ô lệch chưa được ghi trong lech-da-ghi.yaml.
"""
import argparse, os, sys, yaml

D = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("render")
ap.add_argument("--bang", default=os.path.join(D, "bang-tl04-5-2.yaml"))
ap.add_argument("--lech", default=os.path.join(D, "lech-da-ghi.yaml"))
ap.add_argument("--md")
a = ap.parse_args()

docs = [d for d in yaml.safe_load_all(open(a.render)) if isinstance(d, dict) and d.get("kind")]
workloads = {d["metadata"]["name"]: d for d in docs if d["kind"] in ("Deployment", "Rollout", "StatefulSet")}
scalers = {}
for d in docs:
    if d["kind"] == "HorizontalPodAutoscaler":
        s = d["spec"]; scalers[s["scaleTargetRef"]["name"]] = (s.get("minReplicas", 1), s["maxReplicas"])
    elif d["kind"] == "ScaledObject":
        s = d["spec"]; scalers[s["scaleTargetRef"]["name"]] = (s.get("minReplicaCount", 0), s["maxReplicaCount"])
pdbs = [d for d in docs if d["kind"] == "PodDisruptionBudget"]


def probe_str(p, cong=None):
    if not p:
        return None
    if "tcpSocket" in p:
        port = p['tcpSocket']['port']
        return f"TCP {(cong or {}).get(port, port)}"
    path = p.get("httpGet", {}).get("path")
    return "actuator" if path == "/actuator/health/readiness" else path


def thuc_te(w):
    spec = w["spec"]; tpl = spec["template"]
    c = next(x for x in tpl["spec"]["containers"] if x["name"] not in ("opa",))
    r = c.get("resources", {})
    cong = {x["name"]: x["containerPort"] for x in c.get("ports", []) if "name" in x}
    ten = w["metadata"]["name"]
    mn, mx = scalers.get(ten, (spec.get("replicas", 1), spec.get("replicas", 1)))
    sp = c.get("startupProbe")
    # Ba cách đọc startupProbe: số giây ngân sách, đường dẫn HTTP, hoặc "TCP <cổng>"; so theo kiểu giá trị TL ghi.
    startup = None if sp is None else {
        "giay": sp.get("periodSeconds", 10) * sp.get("failureThreshold", 3),
        "duong": probe_str(sp, cong)}
    nhan = tpl["metadata"].get("labels", {})
    pdb = None
    for p in pdbs:
        ml = p["spec"].get("selector", {}).get("matchLabels", {})
        if ml and all(nhan.get(k) == v for k, v in ml.items()) and p["metadata"].get("namespace") == w["metadata"].get("namespace"):
            s = p["spec"]
            pdb = f"minAvailable {s['minAvailable']}" if "minAvailable" in s else f"maxUnavailable {s['maxUnavailable']}"
    if w["kind"] == "Rollout":
        ph = "Rollout"
    else:
        ph = spec.get("strategy", {}).get("type", "RollingUpdate")
    return {"min": mn, "max": mx, "cpu": [str(r.get("requests", {}).get("cpu")), str(r.get("limits", {}).get("cpu"))],
            "mem": str(r.get("limits", {}).get("memory")), "memReq": str(r.get("requests", {}).get("memory")),
            "gpu": r.get("limits", {}).get("nvidia.com/gpu"),
            "startup": startup, "readiness": probe_str(c.get("readinessProbe"), cong), "pdb": pdb, "phatHanh": ph}


bang = yaml.safe_load(open(a.bang))
lech_ghi = yaml.safe_load(open(a.lech)) if os.path.exists(a.lech) else {}
lech_ghi = lech_ghi or {}
COT = ["min", "max", "cpu", "mem", "startup", "readiness", "pdb", "phatHanh"]
dong = ["| Service | Workload | " + " | ".join(COT) + " |", "|" + "---|" * (len(COT) + 2)]
chua_ghi = 0; da_ghi = 0
for e in bang:
    ten = e["ten"]
    w = workloads.get(ten)
    if w is None:
        ly_do = (lech_ghi.get(ten) or {}).get("*")
        dong.append(f"| {e['svc']} | {ten} | " + " | ".join(["—"] * len(COT)) + f" |")
        if ly_do: da_ghi += 1
        else: chua_ghi += 1; print(f"LỆCH CHƯA GHI: {ten} không có trong bản render", file=sys.stderr)
        continue
    t = thuc_te(w)
    o = []
    for k in COT:
        mong = e.get(k); co = t[k]
        if k == "mem" and t["memReq"] != t["mem"]:
            co = f"{t['memReq']}/{t['mem']}"
        if k == "startup":
            if co is not None:
                co = co["giay"] if isinstance(mong, int) else co["duong"]
            if mong is None:
                o.append(f"{co} (TL không ghi)"); continue
        if k == "mem" and e.get("gpu"):
            mong = f"{mong}+{e['gpu']}GPU"; co = f"{co}+{t['gpu']}GPU" if t["gpu"] else co
        khop = (mong == co) or (str(mong) == str(co))
        if khop:
            o.append(str(co))
        else:
            ly_do = (lech_ghi.get(ten) or {}).get(k)
            if ly_do:
                da_ghi += 1; o.append(f"**{co}** (TL: {mong}; đã ghi)")
            else:
                chua_ghi += 1; o.append(f"**{co}** (TL: {mong}; CHƯA GHI)")
                print(f"LỆCH CHƯA GHI: {ten}.{k}: render {co}, TL-04 {mong}", file=sys.stderr)
    dong.append(f"| {e['svc']} | {ten} | " + " | ".join(o) + " |")
md = "\n".join(dong) + f"\n\nTổng: {len(bang)} workload, {da_ghi} ô lệch đã ghi lý do, {chua_ghi} ô lệch chưa ghi.\n"
print(md)
if a.md:
    open(a.md, "w").write(md)
sys.exit(1 if chua_ghi else 0)
