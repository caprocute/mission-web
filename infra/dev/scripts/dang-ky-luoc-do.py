#!/usr/bin/env python3
"""Đăng ký lược đồ Avro của contracts/events vào Apicurio Registry dev (nhóm `default`, `<topic>-value`).

Serde của producer (ApicurioPayloadSerializer của outbox, lệnh org-republish) chạy với AUTO_REGISTER_ARTIFACT=false
và tra phiên bản theo nội dung đã chuẩn hoá, nên registry trống thì mọi lần phát sự kiện báo "Could not resolve
artifact reference by content". Nội dung serde gửi là Schema.toString() của EventContracts.registrySchema(lớp sinh):
kiểu string thường (bỏ `avro.java.string`), kiểu có tên tham chiếu được gộp nguyên định nghĩa ở lần dùng đầu, thứ
tự khoá như Avro in. Chuẩn hoá của Apicurio không gộp tham chiếu, nên artifact chỉ có tệp .avsc thì lược đồ có tham
chiếu (org.bootstrap.v1) không bao giờ khớp. Bộ đăng ký đọc cặp `schema`, `artifactId` trong
contracts/events/catalog.yaml và đăng ký qua API v3 hai dạng: nguyên tệp .avsc, rồi dạng serde (thành bản mới nhất
khi mới thêm). Artifact chưa có thì tạo, nội dung chưa có thì thêm phiên bản (registry kiểm tương thích
BACKWARD_TRANSITIVE), trùng thì bỏ qua. Sau cùng tra lại theo nội dung đúng như serde; không thấy là lỗi và mã thoát
khác 0. Chạy lại an toàn. Chỉ dùng thư viện chuẩn, chạy được trên Python 3.6.

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
from typing import List, Optional, Tuple

EVENTS = Path(__file__).resolve().parents[3] / "contracts" / "events"
REGISTRY = os.environ.get("REGISTRY_URL", "http://127.0.0.1:35081/apis/registry/v3").rstrip("/")
GROUP = "default"


def goi(method: str, path: str, body: Optional[bytes] = None, ctype: str = "application/json"):
    req = urllib.request.Request(REGISTRY + path, data=body, method=method)
    req.add_header("Content-Type", ctype)
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:400].decode("utf-8", "replace")


def cap_luoc_do() -> List[Tuple[str, str]]:
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


def dang_serde(d, ten):
    """Lược đồ đúng như serde gửi khi tra theo nội dung: Schema.toString() của Avro 1.12 cho
    EventContracts.registrySchema(lớp sinh), tức không có avro.java.string, kiểu có tên gộp nguyên
    định nghĩa ở lần dùng đầu, thứ tự khoá như Avro in, namespace chỉ in khi khác namespace bao ngoài."""
    da_in = set()

    def ten_day_du(n, ns):
        return n if "." in n else (f"{ns}.{n}" if ns else n)

    def props(t, bo):
        return {k: v for k, v in t.items() if k not in bo}

    def tf(t, ns):
        if isinstance(t, str):
            if t in PRIMITIVE:
                return t
            n = ten_day_du(t, ns)
            if n in da_in or n not in ten:
                return n if n.rsplit(".", 1)[0] != ns else n.rsplit(".", 1)[-1]
            return tf(ten[n], ns)
        if isinstance(t, list):
            return [tf(x, ns) for x in t]
        kieu = t["type"]
        if kieu in ("record", "error", "enum", "fixed"):
            full = ten_day_du(t["name"], t.get("namespace", ns))
            if full in da_in:
                return full.rsplit(".", 1)[-1] if full.rsplit(".", 1)[0] == ns else full
            da_in.add(full)
            ns2, ten_ngan = (full.rsplit(".", 1) if "." in full else ("", full))
            o = {"type": kieu, "name": ten_ngan}
            if ns2 != ns:
                o["namespace"] = ns2
            if "doc" in t:
                o["doc"] = t["doc"]
            if kieu in ("record", "error"):
                o["fields"] = []
                for f in t["fields"]:
                    of = {"name": f["name"], "type": tf(f["type"], ns2)}
                    for k in ("doc", "default"):
                        if k in f:
                            of[k] = f[k]
                    if f.get("order", "ascending") != "ascending":
                        of["order"] = f["order"]
                    if "aliases" in f:
                        of["aliases"] = f["aliases"]
                    of.update(props(f, {"name", "type", "doc", "default", "order", "aliases"}))
                    o["fields"].append(of)
            elif kieu == "enum":
                o["symbols"] = t["symbols"]
                if "default" in t:
                    o["default"] = t["default"]
            else:
                o["size"] = t["size"]
            if "aliases" in t:
                o["aliases"] = t["aliases"]
            o.update(props(t, {"type", "name", "namespace", "doc", "fields", "symbols", "default", "size", "aliases", "avro.java.string"}))
            return o
        if kieu == "array":
            o = {"type": "array", "items": tf(t["items"], ns)}
            o.update(props(t, {"type", "items", "avro.java.string"}))
            return o
        if kieu == "map":
            o = {"type": "map", "values": tf(t["values"], ns)}
            o.update(props(t, {"type", "values", "avro.java.string"}))
            return o
        o = {"type": kieu}
        o.update(props(t, {"type", "avro.java.string"}))
        return o if len(o) > 1 else kieu

    return tf(d, "")


def tim_theo_noi_dung(artifact: str, noi_dung: str) -> bool:
    """Cùng lời gọi DefaultSchemaResolver của serde dùng khi tra theo nội dung (canonical=true)."""
    q = urllib.parse.urlencode({"groupId": GROUP, "artifactId": artifact, "canonical": "true", "artifactType": "AVRO"})
    st, kq = goi("POST", f"/search/versions?{q}", noi_dung.encode("utf-8"))
    return st == 200 and isinstance(kq, dict) and kq.get("count", 0) > 0


def dang_ky(artifact: str, noi_dung: str) -> str:
    """Trả "đã có", "tạo mới", "thêm bản" hoặc chuỗi lỗi bắt đầu bằng "LỖI"."""
    if tim_theo_noi_dung(artifact, noi_dung):
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
        serde = json.dumps(dang_serde(json.loads(goc), ten), ensure_ascii=False, separators=(",", ":"))
        kq = [dang_ky(artifact, goc), dang_ky(artifact, serde)]
        hong = [k for k in kq if k.startswith("LỖI")]
        if not hong and not tim_theo_noi_dung(artifact, serde):
            hong.append("LỖI: đã đăng ký nhưng tra theo nội dung như serde không thấy")
        if hong:
            loi += 1
            print(f"  {artifact}: {'; '.join(hong)}", file=sys.stderr)
        else:
            print(f"  {artifact}: tệp .avsc {kq[0]}, dạng serde {kq[1]}, tra theo nội dung đạt")
    print(f"Xong {len(cap)} lược đồ, {loi} lỗi")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
