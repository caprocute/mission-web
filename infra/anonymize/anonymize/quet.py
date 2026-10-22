"""Bước kiểm sau che: không còn giá trị nguồn nào trong bản sẽ chuyển sang staging (SEC-52).

Quét (1) mọi cột văn bản, JSON của mọi bảng và (2) bản rõ của mọi cột _enc giải bằng khoá staging mới,
đối chiếu với tập giá trị nguồn giữ trong bộ nhớ: chuỗi chữ số 9 đến 12 ký tự (số định danh, số thẻ
đảng, điện thoại), địa chỉ thư, cụm 2 đến 5 từ (họ tên), và văn bản tự do trùng nguyên giá trị. Kết
quả chỉ ghi bảng, cột, loại mẫu và số lần trúng, không ghi giá trị.
"""

from __future__ import annotations

import re
from collections import Counter

from psycopg import sql

from . import c2
from .che import BoChe, chu_so, chuan_ten

SO = re.compile(r"(?<!\d)(?:\+?84|0)?[\d .-]{8,14}\d(?!\d)")
THU = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
KIEU_VAN_BAN = ("text", "character varying", "character", "json", "jsonb")


def _trung(gia_tri: str, che: BoChe) -> list[str]:
    m = che.mau
    loai = []
    if m.so and any(chu_so(x) in m.so for x in SO.findall(gia_tri)):
        loai.append("so")
    if m.thu and any(x.lower() in m.thu for x in THU.findall(gia_tri)):
        loai.append("thu_dien_tu")
    if m.ho_ten:
        tu = chuan_ten(gia_tri).split()
        if any(" ".join(tu[i:i + n]) in m.ho_ten for n in range(2, 6) for i in range(len(tu) - n + 1)):
            loai.append("ho_ten")
    if m.van_ban and gia_tri in m.van_ban:
        loai.append("van_ban")
    return loai


def quet(che: BoChe) -> list[dict]:
    kn = che.kn
    trung: Counter = Counter()
    cot = kn.execute(
        "SELECT c.table_schema, c.table_name, c.column_name FROM information_schema.columns c "
        "JOIN pg_class k ON k.relname = c.table_name JOIN pg_namespace n ON n.oid = k.relnamespace "
        "AND n.nspname = c.table_schema WHERE k.relkind IN ('r','p') AND NOT k.relispartition "
        "AND c.table_schema NOT IN ('pg_catalog','information_schema') AND c.data_type = ANY(%s) "
        "ORDER BY 1, 2, 3", (list(KIEU_VAN_BAN),)).fetchall()
    theo_bang: dict[str, list[str]] = {}
    for s, t, c in cot:
        theo_bang.setdefault(f"{s}.{t}", []).append(c)
    with kn.transaction():
        for bang, ds in theo_bang.items():
            s, t = bang.split(".", 1)
            with kn.cursor(name="quet_van_ban") as cur:
                cur.itersize = 5000
                cur.execute(sql.SQL("SELECT {} FROM {}").format(
                    sql.SQL(", ").join(sql.SQL("{}::text").format(sql.Identifier(c)) for c in ds), sql.Identifier(s, t)))
                for hang in cur:
                    for c, gt in zip(ds, hang):
                        if gt:
                            for l in _trung(gt, che):
                                trung[(bang, c, l)] += 1
        for lb in che._bang.values():
            ma = [c for c in lb.cot if c.endswith("_enc")]
            if not ma or lb.ten in che.bang_vang:
                continue
            s, t = lb.ten.split(".", 1)
            with kn.cursor(name="quet_ban_ma") as cur:
                cur.itersize = 5000
                cur.execute(sql.SQL("SELECT tenant_id, {}, {}, {} FROM {}").format(
                    sql.Identifier(lb.aad_dong), sql.Identifier(lb.cot_dek),
                    sql.SQL(", ").join(map(sql.Identifier, ma)), sql.Identifier(s, t)))
                for tenant, row_id, dek, *bm in cur:
                    khoa = che.khoa.get(tenant)
                    for c, b in zip(ma, bm):
                        if b is None:
                            continue
                        try:
                            ro = c2.giai_ma(khoa.moi[dek], bytes(b), c2.aad(str(tenant), lb.toa_do(c), str(row_id))).decode()
                        except Exception:
                            trung[(lb.ten, c, "khong_giai_duoc_bang_khoa_staging")] += 1
                            continue
                        for l in _trung(ro, che):
                            trung[(lb.ten, c, l)] += 1
    return [{"bang": b, "cot": c, "loai": l, "so_lan": n} for (b, c, l), n in sorted(trung.items())]
