"""Sinh app/assist/schemas.json: lược đồ yêu cầu và phản hồi của các thao tác trợ lý lấy từ contracts/openapi, đã
gỡ hết $ref, để kiểm yêu cầu (422) và kiểm thử hợp đồng mà không cần thư mục contracts trong image.
Chạy lại khi hợp đồng đổi: `python -m app.assist.sync_schemas`; kiểm thử báo lệch nếu quên."""

from __future__ import annotations

import json
import pathlib
from typing import Any

import yaml

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "schemas.json"
NAMES = ("AiSuggestionRequest", "AiSuggestion", "DocExtraction", "OkrReviewRequest", "OkrReviewResult",
         "OkrAssistRequest", "OkrAssistResult")
COMMON = ("Job",)


def contracts_dir() -> pathlib.Path:
    return HERE.parents[3] / "contracts" / "openapi"


def build(root: pathlib.Path | None = None) -> dict[str, Any]:
    base = (root or contracts_dir()) / "modules"
    cache: dict[pathlib.Path, Any] = {}

    def load(p: pathlib.Path) -> Any:
        p = p.resolve()
        if p not in cache:
            cache[p] = yaml.safe_load(p.read_text(encoding="utf-8"))
        return cache[p]

    def resolve(node: Any, here: pathlib.Path) -> Any:
        if isinstance(node, dict):
            if "$ref" in node:
                file, _, ptr = node["$ref"].partition("#")
                target = (here.parent / file).resolve() if file else here
                doc = load(target)
                for part in ptr.strip("/").split("/"):
                    doc = doc[part]
                extra = {k: v for k, v in node.items() if k != "$ref"}
                return {**resolve(doc, target), **resolve(extra, here)}
            return {k: resolve(v, here) for k, v in node.items()
                    if k not in ("examples", "example") and not k.startswith("x-")}
        if isinstance(node, list):
            return [resolve(v, here) for v in node]
        return node

    platform = base / "platform.yaml"
    schemas = load(platform)["components"]["schemas"]
    out = {n: resolve(schemas[n], platform) for n in NAMES}
    common = (base.parent / "common" / "common.yaml").resolve()
    for n in COMMON:
        out[n] = resolve(load(common)["components"]["schemas"][n], common)
    return out


def main() -> None:
    OUT.write_text(json.dumps(build(), ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
