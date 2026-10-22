#!/usr/bin/env python3
"""Chuẩn bị bản render vt-platform cho Kyverno CLI với bộ chính sách thật ở deploy/policies (INF-02, INF-07).

1. Đổi ảnh bên thứ ba sang project proxy-cache của Harbor (harbor.vt.local/<project>/...), đúng như cụm thật kéo
   ảnh qua Harbor (deploy/clusters/mgmt/harbor); ảnh đã ở harbor.vt.local giữ nguyên.
2. Sinh tệp values cho kyverno apply: nhãn vt.io/security-zone của 13 namespace lấy từ cell-base, và giá trị
   giả lập cho biến apiCall (soPdb, soTrung) vì CLI không có cụm để hỏi.

Dùng: chuan-bi-kyverno.py <render.yaml> <render-harbor.yaml> <values-kyverno.yaml>
"""
import os, sys, yaml

PROXY = {"docker.io": "dockerhub", "quay.io": "quay", "ghcr.io": "ghcr", "registry.k8s.io": "k8s-registry",
         "cr.fluentbit.io": "fluentbit", "gcr.io": "gcr"}


def doi_anh(img):
    if img.startswith("harbor.vt.local/"):
        return img
    dau = img.split("/")[0]
    if "." in dau or ":" in dau:
        host, phan_con = dau, img[len(dau) + 1:]
    else:
        host, phan_con = "docker.io", img
    return f"harbor.vt.local/{PROXY.get(host, host)}/{phan_con}"


def duyet(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == "image" and isinstance(v, str):
                o[k] = doi_anh(v)
            else:
                duyet(v)
    elif isinstance(o, list):
        for x in o:
            duyet(x)


vao, ra, gia_tri = sys.argv[1:4]
docs = [d for d in yaml.safe_load_all(open(vao)) if isinstance(d, dict)]
for d in docs:
    duyet(d)
yaml.safe_dump_all(docs, open(ra, "w"))
ns = yaml.safe_load(open(os.path.join(os.path.dirname(__file__), "../../cell-base/values.yaml")))["namespaces"]
yaml.safe_dump({"apiVersion": "cli.kyverno.io/v1alpha1", "kind": "Value", "metadata": {"name": "vt-platform-ci"},
                "namespaceSelector": [{"name": n, "labels": {"vt.io/security-zone": c["zone"]}} for n, c in ns.items()],
                "globalValues": {"request.operation": "CREATE", "soPdb": 1, "soTrung": 0}}, open(gia_tri, "w"))
