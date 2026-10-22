#!/usr/bin/env python3
"""Nạp UI Pack mặc định cho tenant mới qua API (DL-06, QT-18), không ghi thẳng CSDL.

Trình tự: `listUiPacks` lấy UI Pack của tenant → `createUiPackVersion` tạo bản nháp từ bộ mặc định
của `@vt/design-system` → `updateUiPackVersion` (kèm `If-Match`) ghi token và bố cục của tệp seed →
`submitUiPackVersion` gửi duyệt. Bước `publishUiPackVersion` cần người thứ hai (bốn mắt, BR-18-08)
nên chỉ chạy khi có `MISSION_TOKEN_DUYET` của một tài khoản quản trị khác.

Biến môi trường (không ghi giá trị thật vào repo):
    MISSION_API          gốc API, ví dụ http://localhost:4200/api/v1
    MISSION_TOKEN        access token của quản trị tenant (tenant_admin)
    MISSION_TOKEN_DUYET  (tuỳ chọn) access token của quản trị thứ hai để phát hành

Cách dùng:
    python3 nap_ui_pack.py [mac-dinh-1.0.0.json] [--chi-kiem]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent


class LoiApi(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status


def goi(method: str, url: str, token: str, body: dict | None = None, headers: dict | None = None):
    """Gọi API JSON; trả (mã, header, nội dung đã giải JSON hoặc None)."""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json, application/problem+json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if method != "GET":
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return r.status, r.headers, json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        raise LoiApi(e.code, e.read().decode("utf-8", "replace")) from None


def than_cap_nhat(seed: dict) -> dict:
    """Thân `UiPackVersionUpdate` từ tệp seed (bỏ trường `version`)."""
    return {k: seed[k] for k in ("tokens", "layouts", "componentRegistryVersion", "elderMode", "fewSteps")}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tep", nargs="?", type=Path, default=HERE / "mac-dinh-1.0.0.json")
    ap.add_argument("--chi-kiem", action="store_true", help="chỉ chạy kiểm tra tĩnh, không gọi API")
    a = ap.parse_args(argv)

    sys.path.insert(0, str(HERE))
    from kiem_ui_pack import kiem  # noqa: E402  cùng thư mục

    seed = json.loads(a.tep.read_text(encoding="utf-8"))
    loi, _ = kiem(seed)
    if loi:
        for e in loi:
            print(f"LỖI {e}", file=sys.stderr)
        return 1
    if a.chi_kiem:
        print(f"{a.tep.name}: đạt kiểm tra tĩnh, không gọi API.")
        return 0

    api = os.environ.get("MISSION_API", "").rstrip("/")
    token = os.environ.get("MISSION_TOKEN", "")
    if not api or not token:
        print("Thiếu MISSION_API hoặc MISSION_TOKEN.", file=sys.stderr)
        return 2
    try:
        _, _, page = goi("GET", f"{api}/ui/packs?limit=1", token)
        if not page or not page.get("items"):
            print("Tenant chưa có UI Pack; bản ghi UI Pack được tạo khi khởi tạo tenant.", file=sys.stderr)
            return 1
        pack = page["items"][0]
        base = f"{api}/ui/packs/{pack['id']}/versions"
        ver = seed["version"]
        _, h, _ = goi("POST", base, token, {"version": ver, "cloneFromVersion": None})
        etag = h.get("ETag")
        _, h, _ = goi("PUT", f"{base}/{ver}", token, than_cap_nhat(seed), {"If-Match": etag})
        etag = h.get("ETag")
        st, _, _ = goi("POST", f"{base}/{ver}/submit", token, None, {"If-Match": etag})
        print(f"Đã gửi duyệt UI Pack {pack['code']} phiên bản {ver} (HTTP {st}).")
        duyet = os.environ.get("MISSION_TOKEN_DUYET", "")
        if duyet:
            _, h, _ = goi("GET", f"{base}/{ver}", duyet)
            st, _, _ = goi("POST", f"{base}/{ver}/publish", duyet, {}, {"If-Match": h.get("ETag")})
            print(f"Đã gửi phát hành bởi người duyệt thứ hai (HTTP {st}).")
        else:
            print("Chưa phát hành: cần người duyệt khác người gửi (đặt MISSION_TOKEN_DUYET).")
    except LoiApi as e:
        print(f"Lỗi API: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
