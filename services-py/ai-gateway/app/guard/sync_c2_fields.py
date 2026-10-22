"""Sinh app/guard/c2_fields.json: tên các thuộc tính mang `x-classification: C2` trong contracts/openapi.
Chạy lại khi hợp đồng đổi: `python -m app.guard.sync_c2_fields`; kiểm thử báo lệch nếu quên."""

from __future__ import annotations

import json
import pathlib
from typing import Any

import yaml

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "c2_fields.json"


def contracts_dir() -> pathlib.Path:
    return HERE.parents[3] / "contracts" / "openapi"


def collect(root: pathlib.Path | None = None) -> list[str]:
    names: set[str] = set()

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            props = node.get("properties")
            if isinstance(props, dict):
                for k, v in props.items():
                    if isinstance(v, dict) and v.get("x-classification") in ("C2", "C3"):
                        names.add(k)
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    for f in sorted((root or contracts_dir()).rglob("*.yaml")):
        walk(yaml.safe_load(f.read_text(encoding="utf-8")))
    return sorted(names)


if __name__ == "__main__":
    OUT.write_text(json.dumps(collect(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"đã ghi {OUT}")
