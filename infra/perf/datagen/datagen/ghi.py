"""Ghi dòng ra tệp COPY dạng văn bản của PostgreSQL, tách theo bảng và period_bucket."""

from __future__ import annotations

import json
import uuid
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path


def _o(v: object) -> str:
    if v is None:
        return r"\N"
    if isinstance(v, bool):
        return "t" if v else "f"
    if isinstance(v, (bytes, bytearray)):
        return "\\\\x" + bytes(v).hex()
    if isinstance(v, (uuid.UUID, int, Decimal)):
        return str(v)
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, (list, tuple)):
        return "{" + ",".join(str(x) for x in v) + "}"
    if isinstance(v, dict):
        v = json.dumps(v, ensure_ascii=False)
    s = str(v)
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


class BoGhiCopy:
    """Mỗi bảng một thư mục <schema>.<bảng>/, mỗi period_bucket một tệp p<bucket>.tsv."""

    def __init__(self, thu_muc: Path, cot_theo_bang: dict[str, tuple[str, ...]]) -> None:
        self.thu_muc = thu_muc
        self.cot = cot_theo_bang
        self._tep: dict[tuple[str, str], object] = {}
        self.dem: dict[str, int] = {}

    def ghi(self, bang: str, dong: dict, bucket: int | None = None) -> None:
        khoa = (bang, f"p{bucket}" if bucket is not None else "tat-ca")
        f = self._tep.get(khoa)
        if f is None:
            d = self.thu_muc / bang
            d.mkdir(parents=True, exist_ok=True)
            f = (d / f"{khoa[1]}.tsv").open("w", encoding="utf-8", newline="\n")
            self._tep[khoa] = f
        f.write("\t".join(_o(dong.get(c)) for c in self.cot[bang]) + "\n")
        self.dem[bang] = self.dem.get(bang, 0) + 1

    def dong(self) -> None:
        for f in self._tep.values():
            f.close()
        self._tep.clear()
