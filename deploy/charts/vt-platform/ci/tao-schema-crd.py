#!/usr/bin/env python3
"""Sinh JSON schema cho kubeconform từ tệp CRD (INF-11), cho các nhóm API mà kho datreeio/CRDs-catalog chưa có.

Dùng: tao-schema-crd.py <thư mục ra> <tệp CRD>...; ghi <ra>/<group>/<kind thường>_<version>.json, đúng mẫu
-schema-location '<ra>/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'. Mọi object không khai
x-kubernetes-preserve-unknown-fields bị đặt additionalProperties: false để -strict bắt khoá gõ sai.
"""
import json, os, sys, yaml


def chat(s):
    if isinstance(s, dict):
        if s.get("type") == "object" and "properties" in s and not s.get("x-kubernetes-preserve-unknown-fields"):
            s.setdefault("additionalProperties", False)
        if s.get("x-kubernetes-int-or-string"):
            s.pop("type", None); s["oneOf"] = [{"type": "string"}, {"type": "integer"}]
        for v in list(s.values()):
            chat(v)
    elif isinstance(s, list):
        for v in s:
            chat(v)
    return s


ra = sys.argv[1]
for f in sys.argv[2:]:
    for d in yaml.safe_load_all(open(f)):
        if not d or d.get("kind") != "CustomResourceDefinition":
            continue
        g = d["spec"]["group"]; k = d["spec"]["names"]["kind"].lower()
        for ver in d["spec"]["versions"]:
            s = chat(ver["schema"]["openAPIV3Schema"])
            s.setdefault("properties", {}).update({"apiVersion": {"type": "string"}, "kind": {"type": "string"},
                                                   "metadata": {"type": "object"}})
            os.makedirs(os.path.join(ra, g), exist_ok=True)
            json.dump(s, open(os.path.join(ra, g, f"{k}_{ver['name']}.json"), "w"), ensure_ascii=False)
            print(f"{g}/{k}_{ver['name']}.json")
