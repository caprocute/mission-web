#!/usr/bin/env python3
"""Khẳng định riêng từng mô hình phân phối trên bản render vt-platform (INF-02, TL-04 mục 2.1).

Dùng: kiem-mo-hinh.py <saas|private|airgap> <bản render.yaml>; trả mã 1 khi có khẳng định sai.
"""
import sys, yaml

mo_hinh, tep = sys.argv[1], sys.argv[2]
docs = [d for d in yaml.safe_load_all(open(tep)) if isinstance(d, dict) and d.get("kind")]
wl = [d for d in docs if d["kind"] in ("Deployment", "Rollout", "StatefulSet")]
sai = []


def env_cua(w):
    out = {}
    for c in w["spec"]["template"]["spec"]["containers"]:
        for e in c.get("env", []) or []:
            if "value" in e:
                out[e["name"]] = e["value"]
    return out


# Mọi workload dựng bằng vt-service mang VT_DEPLOYMENT_MODEL đúng mô hình và bộ mật mã đúng bảng mục 2.1.
mat_ma = {"saas": "standard", "private": "standard", "airgap": "bcy"}[mo_hinh]
co_vt = [w for w in wl if "VT_DEPLOYMENT_MODEL" in env_cua(w)]
if len(co_vt) < 15:
    sai.append(f"chỉ {len(co_vt)} workload mang VT_DEPLOYMENT_MODEL")
for w in co_vt:
    e = env_cua(w); ten = w["metadata"]["name"]
    if e["VT_DEPLOYMENT_MODEL"] != mo_hinh:
        sai.append(f"{ten}: VT_DEPLOYMENT_MODEL={e['VT_DEPLOYMENT_MODEL']}")
    if e.get("VT_CRYPTO_PROVIDER") != mat_ma:
        sai.append(f"{ten}: VT_CRYPTO_PROVIDER={e.get('VT_CRYPTO_PROVIDER')}, cần {mat_ma}")
    ff = e.get("VT_FF_PUBLIC_MOBILE_APP")
    if ff != ("false" if mo_hinh == "airgap" else "true"):
        sai.append(f"{ten}: VT_FF_PUBLIC_MOBILE_APP={ff}")

# Luật egress tới egress gateway (ra Internet).
ra_ngoai = []
for d in docs:
    if d["kind"] != "NetworkPolicy":
        continue
    for r in d["spec"].get("egress", []) or []:
        for to in r.get("to", []) or []:
            ns = (to.get("namespaceSelector") or {}).get("matchLabels", {}).get("kubernetes.io/metadata.name")
            if ns == "egress":
                ra_ngoai.append(d["metadata"]["name"])
cm = {d["metadata"]["name"]: d.get("data", {}) for d in docs if d["kind"] == "ConfigMap"}
ig = cm.get("integration-gateway", {})
ten_wl = {w["metadata"]["name"] for w in wl}

if mo_hinh == "airgap":
    if ra_ngoai:
        sai.append(f"bản cách ly còn egress ra egress gateway: {sorted(set(ra_ngoai))}")
    for k in ("VT_KET_NOI_VNEID", "VT_KET_NOI_MYSIGN", "VT_KET_NOI_LGSP"):
        if ig.get(k) != "false":
            sai.append(f"bản cách ly chưa tắt {k}")
    if "vllm" not in ten_wl:
        sai.append("bản cách ly cần mô hình AI tại chỗ (vllm)")
else:
    if not ra_ngoai:
        sai.append("không còn luật egress nào qua egress gateway")
    for k in ("VT_KET_NOI_VNEID", "VT_KET_NOI_MYSIGN", "VT_KET_NOI_LGSP"):
        if ig.get(k) != "true":
            sai.append(f"{mo_hinh} cần bật {k}")
    if mo_hinh == "saas" and "vllm" not in ten_wl:
        sai.append("SaaS cần vLLM trên GPU của cell")
    if mo_hinh == "private" and "vllm" in ten_wl:
        sai.append("đám mây riêng mặc định không bật vLLM")

for s in sai:
    print("SAI:", s)
print(f"{len(co_vt)} workload mang cấu hình mô hình {mo_hinh}, mật mã {mat_ma}, {len(set(ra_ngoai))} chính sách ra ngoài"
      + ("" if not sai else f", {len(sai)} khẳng định sai"))
sys.exit(1 if sai else 0)
