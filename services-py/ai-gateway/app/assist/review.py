"""Luật soát KR của trợ lý (BR-02-05 KR mơ hồ, BR-02-06 KR giống nhiệm vụ, BR-17-05) dùng chung cho soát KR và soạn
OKR. Từ mơ hồ, động từ nhiệm vụ và đơn vị đo lấy từ bộ quy tắc của Policy Pack (`policy` trong ngữ cảnh soạn
`GET /okr/compose-context`); khi không đọc được thì dùng bộ mặc định dưới đây (suy luận từ ví dụ hợp đồng)."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from app.guard.text import fold

VAGUE = ("tăng cường", "đẩy mạnh", "nâng cao", "tiếp tục", "chú trọng", "quan tâm", "từng bước", "góp phần")
TASK_VERBS = ("tổ chức", "ban hành", "triển khai", "xây dựng", "thực hiện", "hoàn thành", "tham mưu")
TASK_UNITS = ("lần", "văn bản", "mốc", "hội nghị", "kế hoạch")
_NUM = re.compile(r"\d")


@dataclass(frozen=True)
class Policy:
    vague: tuple[str, ...] = VAGUE
    task_verbs: tuple[str, ...] = TASK_VERBS
    task_units: tuple[str, ...] = TASK_UNITS

    @staticmethod
    def from_context(ctx: Any) -> Policy:
        pol = ctx.get("policy") if isinstance(ctx, dict) else None
        if not isinstance(pol, dict):
            return Policy()

        def words(key: str, default: tuple[str, ...]) -> tuple[str, ...]:
            v = pol.get(key)
            return tuple(x for x in v if isinstance(x, str) and x.strip()) if isinstance(v, list) and v else default

        return Policy(words("vagueWords", VAGUE), words("taskVerbs", TASK_VERBS), TASK_UNITS)


@dataclass
class Finding:
    kr_code: str
    severity: str
    rule: str
    msg: str
    message: str
    word: str = ""
    rewrites: list[str] = field(default_factory=list)


def _has_word(folded: str, word: str) -> bool:
    return re.search(rf"(?<![a-z0-9]){re.escape(fold(word))}(?![a-z0-9])", folded) is not None


def measurable(kr: dict[str, Any]) -> bool:
    title = str(kr.get("title") or "")
    has_target = kr.get("targetValue") is not None and bool(kr.get("unit"))
    return has_target or _NUM.search(title) is not None


def review(code: str, kr: dict[str, Any], policy: Policy) -> list[Finding]:
    title = str(kr.get("title") or "").strip()
    f = fold(title)
    out: list[Finding] = []
    if not measurable(kr):
        word = next((w for w in policy.vague if _has_word(f, w)), "")
        msg = (f'KPI mơ hồ: "{word}" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.' if word else
               "KR chưa có chỉ tiêu đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.")
        out.append(Finding(code, "LOI", "BR-02-05", "MSG-02-05", msg, word))
    verb = next((v for v in policy.task_verbs if f.startswith(fold(v) + " ") or f == fold(v)), "")
    unit = fold(str(kr.get("unit") or ""))
    target = kr.get("targetValue")
    if verb and (unit in {fold(u) for u in policy.task_units} or (isinstance(target, int | float) and target <= 1)
                 or not measurable(kr)):
        msg = f"{code} giống một nhiệm vụ, không phải kết quả đo được. Cân nhắc chuyển sang Kế hoạch quý."
        out.append(Finding(code, "CANH_BAO", "BR-02-06", "MSG-02-06", msg))
    return out


def acceptable_rewrite(text: str, policy: Policy) -> bool:
    """Câu viết lại phải có chỉ tiêu bằng số và không còn từ mơ hồ."""
    f = fold(text)
    return bool(_NUM.search(text)) and not any(_has_word(f, w) for w in policy.vague) and len(text) <= 300


def words(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]+", fold(text)) if len(w) > 1}


def similarity(a: str, b: str) -> float:
    wa, wb = words(a), words(b)
    return len(wa & wb) / len(wa | wb) if wa and wb else 0.0


def normalize_weights(krs: list[dict[str, Any]]) -> None:
    """Trọng số KR gợi ý cộng đúng 100 (số nguyên, phần dư lớn nhất), giữ tỉ lệ mô hình đưa ra nếu có."""
    if not krs:
        return
    raw = [float(k["weight"]) if isinstance(k.get("weight"), int | float) and k["weight"] > 0 else 0.0 for k in krs]
    if sum(raw) <= 0:
        raw = [1.0] * len(krs)
    total = sum(raw)
    exact = [r * 100 / total for r in raw]
    base = [int(x) for x in exact]
    rest = 100 - sum(base)
    for i in sorted(range(len(krs)), key=lambda i: exact[i] - base[i], reverse=True)[:rest]:
        base[i] += 1
    for k, w in zip(krs, base, strict=True):
        k["weight"] = w
