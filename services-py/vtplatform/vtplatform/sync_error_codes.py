"""Synchronize canonical error-codes.json from backend/libs/vt-kernel (NEN-009) into vtplatform."""

from __future__ import annotations

import json
import pathlib
import shutil
import sys


def sync_error_codes() -> pathlib.Path:
    here = pathlib.Path(__file__).resolve().parent
    repo_root = here.parent.parent.parent
    src = repo_root / "backend" / "libs" / "vt-kernel" / "src" / "main" / "resources" / "error-codes.json"
    dst = here / "error-codes.json"
    if not src.is_file():
        raise FileNotFoundError(f"Canonical error catalog not found at {src}")
    raw = src.read_bytes()
    parsed = json.loads(raw.decode("utf-8"))
    if not isinstance(parsed, dict) or "codes" not in parsed or not isinstance(parsed["codes"], list):
        raise ValueError("Invalid canonical error-codes.json structure: missing 'codes' list")
    shutil.copyfile(src, dst)
    return dst


if __name__ == "__main__":
    target = sync_error_codes()
    print(f"Synced error-codes.json -> {target}", file=sys.stdout)
