#!/usr/bin/env python3
"""Đăng ký lược đồ Avro của contracts/events vào Apicurio Registry dev (nhóm `default`, `<topic>-value`).

Serde của producer (ApicurioPayloadSerializer của outbox, lệnh org-republish) chạy với AUTO_REGISTER_ARTIFACT=false
và tra phiên bản theo nội dung đã chuẩn hoá, nên registry trống thì mọi lần phát sự kiện báo "Could not resolve
artifact reference by content". Bộ đăng ký đọc cặp `schema`, `artifactId` trong contracts/events/catalog.yaml và
đăng ký qua API v3 hai dạng của mỗi lược đồ: nguyên tệp .avsc, và dạng mà lớp Avro sinh ra in lúc chạy (kiểu
string mang `"avro.java.string": "String"` do stringType=String, kiểu có tên tham chiếu được gộp nguyên định nghĩa
ở lần dùng đầu). Chỉ dạng thứ hai khớp nội dung serde gửi lên: chuẩn hoá của Apicurio giữ thuộc tính phụ, nên
artifact chỉ có tệp .avsc thì serde không tìm thấy. Artifact chưa có thì tạo, nội dung chưa có thì thêm phiên bản
(registry kiểm tương thích BACKWARD_TRANSITIVE), trùng thì bỏ qua. Chạy lại an toàn. Chỉ dùng thư viện chuẩn.

    python3 -I infra/dev/scripts/dang-ky-luoc-do.py            # REGISTRY_URL mặc định http://127.0.0.1:35081/apis/registry/v3
"""

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

EVENTS = Path(__file__).resolve().parents[3] / "contracts" / "events"
REGISTRY = os.environ.get("REGISTRY_URL", "http://127.0.0.1:35081/apis/registry/v3").rstrip("/")
GROUP = "default"


def goi(method: str, path: str, body: bytes | None = None, ctype: str = "application/json"):
    req = urllib.request.Request(REGISTRY + path, data=body, method=method)
    req.add_header("Content-Type", ctype)
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:400].decode("utf-8", "replace")


def cap_luoc_do() -> list[tuple[str, str]]:
    text = (EVENTS / "catalog.yaml").read_text(encoding="utf-8")
    return re.findall(r"^\s+schema:\s*(\S+)\s*\n\s+artifactId:\s*(\S+)", text, re.M)


PRIMITIVE = {"null", "boolean", "int", "long", "float", "double", "bytes", "string"}


def bang_ten() -> dict:
    """Mọi kiểu có tên trong contracts/events/avro theo tên đầy đủ, để gộp kiểu tham chiếu."""
    ten = {}

    def gom(t, ns):
        if isinstance(t, dict) and t.get("type") in ("record", "enum", "fixed"):
            n = t["name"] if "." in t["name"] else f"{t.get('namespace', ns)}.{t['name']}"
            ten.setdefault(n, t)
            for f in t.get("fields", []):
                gom(f["type"], t.get("namespace", ns))
        elif isinstance(t, dict):
            for k in ("items", "values"):
                if k in t:
                    gom(t[k], ns)
        elif isinstance(t, list):
            for x in t:
                gom(x, ns)

    for f in sorted((EVENTS / "avro").rglob("*.avsc")):
        d = json.loads(f.read_text(encoding="utf-8"))
        gom(d, d.get("namespace", ""))
    return ten


def dang_java(d: dict, ten: dict) -> dict:
    """Lược đồ như lớp sinh của avro-maven-plugin (stringType=String) in ra bằng Schema.toString()."""
    da_in = set()

    def tf(t, ns):
        if t == "string":
            return {"type": "string", "avro.java.string": "String"}
        if isinstance(t, str) and t not in PRIMITIVE:
            n = t if "." in t else f"{ns}.{t}"
            if n in da_in or n not in ten:
                return t
            return tf(ten[n], ns)
        if isinstance(t, list):
            return [tf(x, ns) for x in t]
        if isinstance(t, dict):
            d2 = dict(t)
            kieu = d2.get("type")
            if kieu == "string" and "logicalType" not in d2:
                d2["avro.java.string"] = "String"
            if kieu in ("record", "enum", "fixed"):
                ns2 = d2.get("namespace", ns)
                da_in.add(d2["name"] if "." in d2["name"] else f"{ns2}.{d2['name']}")
                if kieu == "record":
                    d2["fields"] = [dict(f, type=tf(f["type"], ns2)) for f in d2["fields"]]
            elif kieu == "array":
                d2["items"] = tf(d2["items"], ns)
            elif kieu == "map":
                d2["values"] = tf(d2["values"], ns)
                d2["avro.java.string"] = "String"
            return d2
        return t

    return tf(d, d.get("namespace", ""))


def dang_ky(artifact: str, noi_dung: str) -> str:
    """Trả "đã có", "tạo mới", "thêm bản" hoặc chuỗi lỗi bắt đầu bằng "LỖI"."""
    q = urllib.parse.urlencode({"groupId": GROUP, "artifactId": artifact, "canonical": "true", "artifactType": "AVRO"})
    st, kq = goi("POST", f"/search/versions?{q}", noi_dung.encode("utf-8"))
    if st == 200 and kq.get("count", 0) > 0:
        return "đã có"
    phien_ban = {"content": {"content": noi_dung, "contentType": "application/json"}}
    st, kq = goi("GET", f"/groups/{GROUP}/artifacts/{urllib.parse.quote(artifact)}")
    if st == 404:
        body = {"artifactId": artifact, "artifactType": "AVRO", "firstVersion": phien_ban}
        st, kq = goi("POST", f"/groups/{GROUP}/artifacts", json.dumps(body).encode("utf-8"))
        viec = "tạo mới"
    else:
        st, kq = goi("POST", f"/groups/{GROUP}/artifacts/{urllib.parse.quote(artifact)}/versions",
                     json.dumps(phien_ban).encode("utf-8"))
        viec = "thêm bản"
    return viec if st in (200, 201) else f"LỖI {st}: {kq}"


def main() -> int:
    loi = 0
    cap = cap_luoc_do()
    ten = bang_ten()
    for schema, artifact in cap:
        goc = (EVENTS / schema).read_text(encoding="utf-8")
        java = json.dumps(dang_java(json.loads(goc), ten), ensure_ascii=False)
        kq = [dang_ky(artifact, goc), dang_ky(artifact, java)]
        hong = [k for k in kq if k.startswith("LỖI")]
        if hong:
            loi += 1
            print(f"  {artifact}: {'; '.join(hong)}", file=sys.stderr)
        else:
            print(f"  {artifact}: tệp .avsc {kq[0]}, dạng lớp sinh {kq[1]}")
    print(f"Xong {len(cap)} lược đồ, {loi} lỗi")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
