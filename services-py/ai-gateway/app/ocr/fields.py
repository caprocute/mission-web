"""Trích số ký hiệu, ngày ban hành, trích yếu từ văn bản OCR theo thể thức NĐ 30/2020 (luật), và chọn nhiệm vụ
đang mở khớp nhất với minh chứng (độ khớp theo từ; dưới 0,5 thì không gợi ý, MSG-07-08)."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from datetime import date
from typing import Any

from app.assist.review import similarity

MATCH_AT = 0.5
TYPES = ("BÁO CÁO", "KẾ HOẠCH", "QUYẾT ĐỊNH", "CHỈ THỊ", "CÔNG VĂN", "NGHỊ QUYẾT", "THÔNG BÁO", "TỜ TRÌNH",
         "BIÊN BẢN", "HƯỚNG DẪN", "CHƯƠNG TRÌNH", "KẾT LUẬN", "QUY ĐỊNH", "ĐỀ ÁN", "THÔNG TRI")
_CODE = r"[0-9A-ZĐÀ-Ỹ]"
_NUMBER = re.compile(rf"\bSố\s*[:.]?\s*(\d{{1,5}}(?:[/-]{_CODE}{{1,15}}){{1,4}})", re.IGNORECASE)
_NUMBER_BARE = re.compile(rf"\b(\d{{1,5}}[-/][A-ZĐÀ-Ỹ]{{1,10}}/{_CODE}{{1,15}}(?:-{_CODE}{{1,10}})?)\b")
_DATE_WORDS = re.compile(r"ngày\s+(\d{1,2})\s+tháng\s+(\d{1,2})\s+năm\s+(\d{4})", re.IGNORECASE)
_DATE_NUM = re.compile(r"\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b")
_STOP = re.compile(r"^(Kính gửi|Căn cứ|Thực hiện|Phần|I\.|1\.)", re.IGNORECASE)


@dataclass(frozen=True)
class Fields:
    doc_number: str | None
    doc_date: str | None
    doc_summary: str | None


def _date(d: str, m: str, y: str) -> str | None:
    try:
        return date(int(y), int(m), int(d)).isoformat()
    except ValueError:
        return None


def extract(text: str) -> Fields:
    t = unicodedata.normalize("NFC", text)
    m = _NUMBER.search(t) or _NUMBER_BARE.search(t)
    number = m.group(1).upper()[:64] if m else None
    when = None
    for rx in (_DATE_WORDS, _DATE_NUM):
        for dm in rx.finditer(t):
            when = _date(*dm.groups())
            if when:
                break
        if when:
            break
    lines = [x.strip() for x in t.splitlines()]
    summary = None
    for i, line in enumerate(lines):
        kind = next((k for k in TYPES if line.upper() == k), None)
        if kind is None:
            continue
        rest = []
        for nxt in lines[i + 1:i + 4]:
            if not nxt or _STOP.match(nxt) or nxt.startswith("-"):
                break
            rest.append(nxt)
        if rest:
            body = " ".join(rest)
            summary = (kind.capitalize() + " " + body[0].lower() + body[1:])[:500]
        break
    return Fields(number, when, summary)


def match(subject: str, tasks: list[dict[str, Any]]) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    scored = []
    for x in tasks:
        title = str(x.get("title") or "")
        product = str(x.get("productName") or "")
        s = max(similarity(subject, title), similarity(subject, f"{title} {product}"))
        scored.append(({"id": str(x["id"]), "code": str(x.get("code") or ""), "title": title,
                        "confidence": round(min(s, 1.0), 4)}, s))
    scored.sort(key=lambda z: z[1], reverse=True)
    best = scored[0][0] if scored and scored[0][1] >= MATCH_AT else None
    rest = [z[0] for z in scored[1 if best else 0:] if z[1] >= 0.2][:5]
    return best, rest
