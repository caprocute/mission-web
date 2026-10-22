"""Sinh câu trả lời JSON tất định theo lược đồ cho mô hình giả lập (YC-PT-13): ca dùng nêu lược đồ trong lời nhắc
sau dấu SCHEMA_MARK, bản giả lập đọc lại lược đồ đó và dựng một thể hiện hợp lệ tối thiểu. Chuỗi được trích
nguồn [#1] khi lời nhắc có tài liệu tham khảo, để đi qua được rào chắn đầu ra như một mô hình thật."""

from __future__ import annotations

import json
from typing import Any

SCHEMA_MARK = "Lược đồ JSON của câu trả lời:"


def schema_instruction(schema: dict[str, Any]) -> str:
    return SCHEMA_MARK + "\n" + json.dumps(schema, ensure_ascii=False)


def find_schema(prompt: str) -> dict[str, Any] | None:
    i = prompt.find(SCHEMA_MARK)
    if i < 0:
        return None
    raw = prompt[i + len(SCHEMA_MARK):].strip()
    try:
        doc, _ = json.JSONDecoder().raw_decode(raw)
    except ValueError:
        return None
    return doc if isinstance(doc, dict) else None


def instance(schema: dict[str, Any], seed: str, cite: bool, key: str = "") -> Any:
    if "const" in schema:
        return schema["const"]
    if "enum" in schema:
        return schema["enum"][0]
    t = schema.get("type")
    if isinstance(t, list):
        t = next((x for x in t if x != "null"), "null")
    if t == "object":
        props: dict[str, Any] = schema.get("properties", {})
        return {k: instance(v, seed, cite, k) for k, v in props.items()}
    if t == "array":
        n = max(int(schema.get("minItems", 1)), 1)
        n = min(n, int(schema.get("maxItems", n)))
        return [instance(schema.get("items", {"type": "string"}), f"{seed}{i}", cite, key) for i in range(n)]
    if t == "integer":
        return int(schema.get("minimum", 1))
    if t == "number":
        lo, hi = float(schema.get("minimum", 0)), float(schema.get("maximum", 100))
        return hi if key in ("targetValue", "confidence") else lo
    if t == "boolean":
        return False
    if schema.get("format") == "date":
        return "2027-03-31"
    if t == "null":
        return None
    if key in ("ref", "source", "sources", "citations"):
        return "[#1]" if cite else f"mẫu {seed}"
    text = f"Nội dung gợi ý mẫu {seed}"
    return text + (" [#1]" if cite else "")


def fake_reply(prompt: str, digest: str) -> str:
    schema = find_schema(prompt)
    if schema is None:
        return '{"answer":"Nội dung gợi ý mẫu ' + digest + '"}'
    return json.dumps(instance(schema, digest, "[#1]" in prompt), ensure_ascii=False)
