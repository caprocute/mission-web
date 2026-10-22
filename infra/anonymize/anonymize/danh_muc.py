"""Danh mục cột cần che, suy từ migration thật (TL-03 phân lớp cột, NEN-015 mã hoá trường).

Một cột phải có luật che khi thoả một trong các điều kiện (suy luận, xem README):
  - chú thích cột `COMMENT ON COLUMN ... IS '{"cls": "C2" | "C3", ...}'`;
  - chú thích cột có `"pii": true` (dữ liệu cá nhân cơ bản mức C1, ví dụ họ tên);
  - tên cột kết thúc bằng `_enc` (bản mã phong bì C2 của NEN-015) hoặc `_bidx` (chỉ mục mù).
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

GOC_REPO = Path(__file__).resolve().parents[3]
THU_MUC_MIGRATION = GOC_REPO / "backend"

_COMMENT = re.compile(
    r"COMMENT\s+ON\s+COLUMN\s+([\w${}:.]+)\.(\w+)\.(\w+)\s+IS\s+'((?:[^']|'')*)'", re.I)
_CREATE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w${}:.]+)\.(\w+)\s*\((.*?)\n\)", re.I | re.S)
_ALTER_ADD = re.compile(r"ALTER\s+TABLE\s+(?:ONLY\s+)?([\w${}:.]+)\.(\w+)\s+(.*?);", re.I | re.S)
_ADD_COL = re.compile(r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.I)
_COL_DEF = re.compile(r"^\s*(\w+)\s+(bytea|text|varchar|character|date|jsonb|uuid|int|integer|bigint|smallint|numeric|boolean|timestamptz|timestamp|char)\b", re.I | re.M)


@dataclass(frozen=True)
class CotNhayCam:
    bang: str  # schema.bảng
    cot: str
    ly_do: str  # C2, C3, pii, _enc, _bidx

    @property
    def ten(self) -> str:
        return f"{self.bang}.{self.cot}"


def _schema(raw: str, thu_muc_schema: str) -> str:
    return thu_muc_schema if "flyway:defaultSchema" in raw else raw


def cac_tep_migration(goc: Path = THU_MUC_MIGRATION) -> list[Path]:
    return sorted(p for p in goc.glob("**/src/main/resources/db/migration/*/*.sql") if "/target/" not in str(p))


def quet_migration(tep: list[Path] | None = None) -> dict[str, CotNhayCam]:
    """Trả {schema.bảng.cột: CotNhayCam} cho mọi cột cần luật che."""
    kq: dict[str, CotNhayCam] = {}
    for p in tep if tep is not None else cac_tep_migration():
        sql = p.read_text(encoding="utf-8")
        thu_muc_schema = p.parent.name
        for m in _COMMENT.finditer(sql):
            bang = f"{_schema(m.group(1), thu_muc_schema)}.{m.group(2)}"
            try:
                meta = json.loads(m.group(4).replace("''", "'"))
            except json.JSONDecodeError:
                continue
            if not isinstance(meta, dict):
                continue
            if meta.get("cls") in ("C2", "C3"):
                kq.setdefault(f"{bang}.{m.group(3)}", CotNhayCam(bang, m.group(3), meta["cls"]))
            elif meta.get("pii") is True:
                kq.setdefault(f"{bang}.{m.group(3)}", CotNhayCam(bang, m.group(3), "pii"))
        cot_moi: list[tuple[str, str]] = []
        for m in _CREATE.finditer(sql):
            bang = f"{_schema(m.group(1), thu_muc_schema)}.{m.group(2)}"
            cot_moi += [(bang, c.group(1)) for c in _COL_DEF.finditer(m.group(3))]
        for m in _ALTER_ADD.finditer(sql):
            bang = f"{_schema(m.group(1), thu_muc_schema)}.{m.group(2)}"
            cot_moi += [(bang, c.group(1)) for c in _ADD_COL.finditer(m.group(3))]
        for bang, cot in cot_moi:
            for duoi in ("_enc", "_bidx"):
                if cot.endswith(duoi):
                    kq.setdefault(f"{bang}.{cot}", CotNhayCam(bang, cot, duoi))
    return kq
