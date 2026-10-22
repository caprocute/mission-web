"""Chuẩn hoá văn bản cho rào chắn: bỏ dấu tiếng Việt, ký tự vô hình và ký tự giả dạng thường gặp, gộp khoảng
trắng. Luật so khớp viết trên dạng không dấu để bắt cả câu viết thiếu dấu hoặc lẫn ký tự lạ."""

from __future__ import annotations

import re
import unicodedata

_INVISIBLE = re.compile("[­​-‏‪-‮⁠-⁤﻿]")
_SPACE = re.compile(r"\s+")
# Chữ Kirin và Hy Lạp trông như chữ Latinh, hay dùng để lách luật.
_HOMOGLYPH = str.maketrans({"а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "х": "x", "у": "y", "і": "i",
                            "ο": "o", "α": "a", "ε": "e", "ι": "i", "ν": "v", "τ": "t", "κ": "k", "м": "m"})


def fold(text: str) -> str:
    t = unicodedata.normalize("NFKC", text)
    t = _INVISIBLE.sub("", t).translate(_HOMOGLYPH).lower()
    t = t.replace("đ", "d")
    t = "".join(c for c in unicodedata.normalize("NFD", t) if unicodedata.category(c) != "Mn")
    return _SPACE.sub(" ", t).strip()
