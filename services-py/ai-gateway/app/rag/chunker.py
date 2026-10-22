"""Chia đoạn văn bản tiếng Việt cho RAG (TL-01 "Kiến trúc cổng mô hình"): 500 đến 800 từ, có chồng lấp, giữ cấu
trúc chương, mục, điều, khoản; không cắt giữa một khoản trừ khi riêng khoản đó dài hơn 800 từ (khi đó cắt theo
câu). Mỗi đoạn mang tiêu đề điều đang chứa nó để mô hình hiểu ngữ cảnh."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

MIN_WORDS = 500
MAX_WORDS = 800
OVERLAP_WORDS = 120

_ARTICLE = re.compile(r"^(?:Điều|ĐIỀU)\s+\d+[a-z]?\b", re.M)
_HEADING = re.compile(r"^(?:Chương|CHƯƠNG|Mục|MỤC|Phần|PHẦN)\s+[IVXLCDM\d]+\b", re.M)
_CLAUSE = re.compile(r"^(?:\d{1,2}\.|[a-zđ]\))\s", re.M)
_SENTENCE = re.compile(r"(?<=[.!?;:])\s+")


@dataclass(frozen=True)
class Chunk:
    no: int
    text: str
    location: str
    words: int


@dataclass
class _Seg:
    text: str
    article: str  # dòng tiêu đề điều chứa đoạn, rỗng nếu ngoài điều
    words: int


def _wc(s: str) -> int:
    return len(s.split())


def _segments(text: str) -> list[_Seg]:
    """Tách thành khoản (hoặc đoạn văn ngoài điều), mỗi khoản nhớ tiêu đề điều của nó."""
    t = unicodedata.normalize("NFC", text).replace("\r\n", "\n")
    starts = sorted({0, *(m.start() for m in _ARTICLE.finditer(t)), *(m.start() for m in _HEADING.finditer(t))})
    segs: list[_Seg] = []
    for i, s in enumerate(starts):
        block = t[s: starts[i + 1] if i + 1 < len(starts) else len(t)].strip()
        if not block:
            continue
        first, _, rest = block.partition("\n")
        article = first.strip() if _ARTICLE.match(first) else ""
        if _HEADING.match(first):
            segs.append(_Seg(first.strip(), "", _wc(first)))
            first, _, rest = rest.partition("\n")
            article = first.strip() if _ARTICLE.match(first) else ""
            block = (first + "\n" + rest).strip()
        body = rest if article else block
        cuts = sorted({0, *(m.start() for m in _CLAUSE.finditer(body))})
        parts = [body[c: cuts[j + 1] if j + 1 < len(cuts) else len(body)].strip() for j, c in enumerate(cuts)]
        parts = [p for p in parts if p]
        if article and not parts:
            parts = [article]
        for p in parts:
            for piece in _split_long(p):
                segs.append(_Seg(piece, article, _wc(piece)))
    return segs


def _split_long(text: str) -> list[str]:
    if _wc(text) <= MAX_WORDS:
        return [text]
    out: list[str] = []
    cur: list[str] = []
    n = 0
    for sent in _SENTENCE.split(text):
        w = _wc(sent)
        if cur and n + w > MAX_WORDS - 60:
            out.append(" ".join(cur))
            cur, n = [], 0
        if w > MAX_WORDS - 60:  # câu cực dài: cắt theo từ
            words = sent.split()
            for k in range(0, len(words), MAX_WORDS - 60):
                out.append(" ".join(words[k: k + MAX_WORDS - 60]))
            continue
        cur.append(sent)
        n += w
    if cur:
        out.append(" ".join(cur))
    return out


def _location(seg: _Seg) -> str:
    head = seg.article.split(".")[0] if seg.article else ""
    m = re.match(r"^(\d{1,2})\.", seg.text)
    if head and m:
        return f"{head}, khoản {m.group(1)}"
    return head


def chunk_text(text: str) -> list[Chunk]:
    segs = _segments(text)
    chunks: list[Chunk] = []
    cur: list[_Seg] = []

    def words(xs: list[_Seg]) -> int:
        return sum(s.words for s in xs)

    def emit() -> None:
        lines: list[str] = []
        last_article = None
        for s in cur:
            if s.article and s.article != last_article and not s.text.startswith(s.article):
                lines.append(s.article)
            last_article = s.article
            lines.append(s.text)
        body = "\n".join(lines)
        chunks.append(Chunk(len(chunks), body, _location(cur[0]), _wc(body)))

    for seg in segs:
        if cur and words(cur) + seg.words > MAX_WORDS and words(cur) >= 1:
            emit()
            # Chồng lấp: giữ các khoản cuối (nguyên khoản) tổng không quá OVERLAP_WORDS.
            keep: list[_Seg] = []
            for s in reversed(cur):
                if words(keep) + s.words > OVERLAP_WORDS:
                    break
                keep.insert(0, s)
            cur = keep if words(keep) + seg.words <= MAX_WORDS else []
        cur.append(seg)
    if cur:
        if chunks and words(cur) < MIN_WORDS // 2 and chunks[-1].words + words(cur) <= MAX_WORDS:
            # Phần đuôi quá ngắn: gộp vào đoạn trước thay vì tạo đoạn lẻ.
            last = chunks.pop()
            tail = "\n".join(s.text for s in cur if s.text not in last.text)
            body = (last.text + "\n" + tail).strip()
            chunks.append(Chunk(last.no, body, last.location, _wc(body)))
        else:
            emit()
    return chunks
