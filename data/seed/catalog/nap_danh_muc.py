#!/usr/bin/env python3
"""Nạp danh mục công việc mẫu của một khối cho tenant mới qua `importCatalogExcel` (DL-06, QT-04).

Gửi tệp `danh-muc-mau-<khối>.xlsx` vào phiên bản danh mục đích bằng
`POST /catalog/{catalogId}/imports` (multipart, 202 + `Location`), rồi theo dõi
`/catalog/imports/{jobId}` tới khi xong và in số dòng hợp lệ, nhóm lỗi. Không ghi thẳng CSDL.

Biến môi trường (không ghi giá trị thật vào repo):
    MISSION_API    gốc API, ví dụ http://localhost:4200/api/v1
    MISSION_TOKEN  access token của người có quyền nhập danh mục (manager hoặc policy_author)

Cách dùng:
    python3 nap_danh_muc.py K3 [--danh-muc <id phiên bản danh mục>]

Không truyền `--danh-muc` thì dùng phiên bản `NHAP` đầu tiên mà `listWorkCatalogs` trả về.
Mã thoát 0 khi mọi dòng hợp lệ (tệp lỗi rỗng), 1 khi có lỗi.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


class LoiApi(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status


def da_phan(ten: str, du_lieu: bytes) -> tuple[bytes, str]:
    """Thân multipart/form-data một trường `file`."""
    ranh = f"----mission{uuid.uuid4().hex}"
    dau = (
        f"--{ranh}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{ten}"\r\n'
        f"Content-Type: {XLSX}\r\n\r\n"
    ).encode("utf-8")
    return dau + du_lieu + f"\r\n--{ranh}--\r\n".encode("utf-8"), f"multipart/form-data; boundary={ranh}"


def dia_chi_tac_vu(api: str, vi_tri: str) -> str:
    """URL đầy đủ của `Location`: tuyệt đối, theo gốc máy chủ (`/api/v1/...`) hoặc theo gốc API."""
    if vi_tri.startswith(("http://", "https://")):
        return vi_tri
    if vi_tri.startswith("/api/"):
        return urllib.parse.urljoin(api, vi_tri)
    return api + "/" + vi_tri.lstrip("/")


def goi(method: str, url: str, token: str, data: bytes | None = None, loai: str | None = None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json, application/problem+json")
    if loai:
        req.add_header("Content-Type", loai)
    if method != "GET":
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8")
            return r.status, r.headers, json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        raise LoiApi(e.code, e.read().decode("utf-8", "replace")) from None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("khoi", choices=[f"K{i}" for i in range(1, 10)])
    ap.add_argument("--danh-muc", help="id phiên bản danh mục đích")
    ap.add_argument("--cho", type=int, default=120, help="số giây chờ tác vụ nền tối đa")
    a = ap.parse_args(argv)

    tep = HERE / f"danh-muc-mau-{a.khoi}.xlsx"
    if not tep.is_file():
        print(f"Chưa có {tep.name}; chạy sinh_danh_muc_mau.py trước.", file=sys.stderr)
        return 1
    api = os.environ.get("MISSION_API", "").rstrip("/")
    token = os.environ.get("MISSION_TOKEN", "")
    if not api or not token:
        print("Thiếu MISSION_API hoặc MISSION_TOKEN.", file=sys.stderr)
        return 2
    try:
        dm = a.danh_muc
        if not dm:
            _, _, page = goi("GET", f"{api}/catalog?limit=50", token)
            nhap = [c for c in (page or {}).get("items", []) if c.get("status") == "NHAP"]
            if not nhap:
                print("Không có phiên bản danh mục ở trạng thái nháp; truyền --danh-muc.", file=sys.stderr)
                return 1
            dm = nhap[0]["id"]
        than, loai = da_phan(tep.name, tep.read_bytes())
        st, h, job = goi("POST", f"{api}/catalog/{urllib.parse.quote(dm)}/imports", token, than, loai)
        print(f"Đã gửi {tep.name} (HTTP {st}).")
        vi_tri = h.get("Location") or f"/catalog/imports/{job['id']}"
        url = dia_chi_tac_vu(api, vi_tri)
        het = time.monotonic() + a.cho
        while job and job.get("status") == "DANG_XU_LY" and time.monotonic() < het:
            time.sleep(2)
            _, _, job = goi("GET", url, token)
        if not job or job.get("status") != "XONG":
            print(f"Tác vụ nhập chưa xong hoặc lỗi: {job}", file=sys.stderr)
            return 1
        print(f"Hợp lệ {job['validRows']}/{job['totalRows']} dòng.")
        for g in job.get("errorGroups", []):
            print(f"  {g['count']} dòng: {g['reason']}", file=sys.stderr)
        return 0 if not job.get("errorGroups") and job["validRows"] == job["totalRows"] else 1
    except LoiApi as e:
        print(f"Lỗi API: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
