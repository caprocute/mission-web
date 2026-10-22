"""Lõi che dữ liệu tại chỗ trên CSDL bản sao ở cụm cách ly (DL-15, SEC-52).

Trình tự cho mỗi tenant, trong một giao dịch: mở bọc mọi DEK bằng KEK nguồn, sinh DEK mới và bọc bằng
KEK staging (giữ nguyên keyring.dek.id nên mọi cột dek_id và khoá ngoại không đổi), duyệt từng bảng có
luật bằng con trỏ phía máy chủ, giải mã cột _enc, áp luật, mã hoá lại bằng DEK mới, tính lại chỉ mục
mù bằng khoá gốc BLIND_INDEX mới, rồi ghi khối qua bảng tạm (chỉ chứa bản mã mới và giá trị giả) và
UPDATE ... FROM theo khoá chính. Khoá chính, khoá ngoại, period_bucket, trạng thái, mốc thời gian
không bị đụng tới, nên ràng buộc loại trừ và RLS giữ nguyên. Bản rõ chỉ ở bộ nhớ tiến trình.
"""

from __future__ import annotations

import os
import re
import unicodedata
import uuid
from dataclasses import dataclass, field
from datetime import date

import psycopg
from psycopg import sql

from . import c2, gia
from .danh_muc import CotNhayCam
from .khoa import BoKhoa
from .luat import BoLuat, LuatBang

KHOI = 2000
_NGAY = re.compile(r"\d{4}-\d{2}-\d{2}")
NEO_BLIND_INDEX = date(1970, 1, 1)
THU_TU_LUAT = {"chi_muc_mu": 2, "so_dinh_danh": 1}


@dataclass
class MauNguon:
    """Giá trị nguồn giữ trong bộ nhớ để bước quét đối chiếu; không bao giờ ghi ra biên bản."""

    so: set[str] = field(default_factory=set)  # số định danh, số thẻ đảng, số điện thoại (chỉ chữ số)
    thu: set[str] = field(default_factory=set)
    ho_ten: set[str] = field(default_factory=set)
    van_ban: set[str] = field(default_factory=set)
    ho_ten_trung_kho_gia: int = 0

    def xoa(self) -> None:
        for s in (self.so, self.thu, self.ho_ten, self.van_ban):
            s.clear()

    def dem(self) -> dict[str, int]:
        return {"so": len(self.so), "thu_dien_tu": len(self.thu), "ho_ten": len(self.ho_ten),
                "van_ban": len(self.van_ban), "ho_ten_trung_kho_gia_bo_qua": self.ho_ten_trung_kho_gia}


def chu_so(s: str) -> str:
    d = "".join(ch for ch in s if ch.isdigit())
    return "0" + d[2:] if d.startswith("84") and len(d) == 11 else d


def chuan_ten(s: str) -> str:
    return " ".join(unicodedata.normalize("NFC", s).split()).lower()


@dataclass
class KhoaTenant:
    cu: dict[uuid.UUID, bytes | None]
    moi: dict[uuid.UUID, bytes]
    goc_bidx_moi: bytes | None


class BoChe:
    def __init__(self, kn: psycopg.Connection, luat: BoLuat, nguon: BoKhoa, dich: BoKhoa,
                 danh_muc: dict[str, CotNhayCam] | None = None, anh_xa: gia.BoAnhXa | None = None) -> None:
        self.kn = kn
        self.luat = luat
        self.nguon = nguon
        self.dich = dich
        self.anh_xa = anh_xa or gia.BoAnhXa()
        self.mau = MauNguon()
        self.khoa: dict[uuid.UUID, KhoaTenant] = {}
        self.thong_ke: dict[str, dict] = {}
        self.bang_xoa: dict[str, int] = {}
        self.cot_ngam_dinh: list[str] = []
        self.bang_vang: list[str] = []
        self._bang = self._luat_hieu_luc(danh_muc or {})

    # ------------------------------------------------------------------ chuẩn bị
    def _luat_hieu_luc(self, danh_muc: dict[str, CotNhayCam]) -> dict[str, LuatBang]:
        """Cột _enc trong danh mục mà thiếu luật vẫn phải mã hoá lại (luật 'giu' ngầm định), nếu
        không bản mã cũ sẽ thành rác khi DEK được thay; biên bản ghi rõ các cột này."""
        bang = {k: LuatBang(v.ten, dict(v.cot), v.aad_dong, v.aad_cot, v.cot_dek) for k, v in self.luat.bang.items()}
        for k, cot in sorted(danh_muc.items()):
            if self.luat.co_luat(k) or not cot.cot.endswith("_enc"):
                continue
            b = bang.setdefault(cot.bang, LuatBang(cot.bang, {}, "id", None, "dek_id"))
            b.cot_dek = b.cot_dek or "dek_id"
            b.cot[cot.cot] = {"luat": "giu", "ngam_dinh": True}
            self.cot_ngam_dinh.append(k)
        return bang

    def _bang_co(self) -> set[str]:
        return {f"{s}.{t}" for s, t in self.kn.execute(
            "SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            "WHERE c.relkind IN ('r','p') AND NOT c.relispartition "
            "AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_%'")}

    def _khoa_chinh(self, bang: str) -> list[str]:
        return [r[0] for r in self.kn.execute(
            "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid "
            "AND a.attnum = ANY(i.indkey) WHERE i.indrelid = %s::regclass AND i.indisprimary "
            "ORDER BY array_position(i.indkey, a.attnum)", (bang,))]

    # ------------------------------------------------------------------ chạy
    def chay(self) -> None:
        co = self._bang_co()
        with self.kn.transaction():
            for b in sorted(t for t in co if self.luat.la_bang_xoa(t)):
                self.bang_xoa[b] = self.kn.execute(sql.SQL("SELECT count(*) FROM {}").format(_ten(b))).fetchone()[0]
                self.kn.execute(sql.SQL("TRUNCATE {}").format(_ten(b)))
        self.bang_vang = sorted(b for b in self._bang if b not in co)
        bang_lam = [b for b in self._bang if b in co]
        tenant: set[uuid.UUID] = set()
        for b in bang_lam + (["keyring.dek"] if "keyring.dek" in co else []):
            tenant |= {r[0] for r in self.kn.execute(sql.SQL("SELECT DISTINCT tenant_id FROM {}").format(_ten(b)))}
        for t in sorted(tenant):
            with self.kn.transaction():
                self.kn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(t),))
                kt = self._doi_khoa(t) if "keyring.dek" in co else KhoaTenant({}, {}, None)
                self.khoa[t] = kt
                for b in bang_lam:
                    self._che_bang(t, self._bang[b], kt)
                if "keyring.dek" in co:
                    self._ghi_khoa(t, kt)

    def _doi_khoa(self, tenant: uuid.UUID) -> KhoaTenant:
        cu: dict[uuid.UUID, bytes | None] = {}
        moi: dict[uuid.UUID, bytes] = {}
        goc = None
        hang = self.kn.execute("SELECT id, purpose, valid_month, wrapped_dek FROM keyring.dek WHERE tenant_id = %s "
                               "ORDER BY purpose, valid_month", (tenant,)).fetchall()
        for dek_id, muc_dich, thang, boc in hang:
            try:
                cu[dek_id] = self.nguon.mo_boc(tenant, bytes(boc))
            except Exception:  # DEK đã huỷ (xoá bằng mật mã) hoặc bản bọc hỏng: dữ liệu không giải được
                cu[dek_id] = None
            moi[dek_id] = os.urandom(32)
            if muc_dich == "BLIND_INDEX" and (goc is None or thang == NEO_BLIND_INDEX):
                goc = moi[dek_id]
        return KhoaTenant(cu, moi, goc)

    def _ghi_khoa(self, tenant: uuid.UUID, kt: KhoaTenant) -> None:
        with self.kn.cursor() as cur:
            for dek_id, dek in kt.moi.items():
                boc, ref = self.dich.boc(tenant, dek)
                cur.execute("UPDATE keyring.dek SET wrapped_dek = %s, kek_ref = %s WHERE id = %s", (boc, ref, dek_id))
        tk = self.thong_ke.setdefault("keyring.dek", {"dong": 0})
        tk["dong"] += len(kt.moi)

    # ------------------------------------------------------------------ từng bảng
    def _che_bang(self, tenant: uuid.UUID, lb: LuatBang, kt: KhoaTenant) -> None:
        pk = self._khoa_chinh(lb.ten)
        cot_luat = sorted(lb.cot, key=lambda c: THU_TU_LUAT.get(lb.cot[c]["luat"], 0))
        phu = {lb.cot[c][k] for c in lb.cot for k in ("gioi",) if lb.cot[c].get(k)}
        phu |= {x for x in (lb.aad_dong, lb.cot_dek) if x}
        doc = list(dict.fromkeys(pk + sorted(phu - set(pk)) + cot_luat))
        tk = self.thong_ke.setdefault(lb.ten, {"dong": 0, "cot": {c: {"luat": lb.cot[c]["luat"], "doi": 0,
                                                                       "khong_giai_duoc": 0} for c in cot_luat}})
        tam = f"_che_{lb.ten.replace('.', '_')}"
        self.kn.execute(sql.SQL("CREATE TEMP TABLE IF NOT EXISTS {} ON COMMIT DROP AS SELECT {} FROM {} WITH NO DATA").format(
            sql.Identifier(tam), sql.SQL(", ").join(map(sql.Identifier, pk + cot_luat)), _ten(lb.ten)))
        cap_nhat = sql.SQL("UPDATE {} b SET {} FROM {} t WHERE {}").format(
            _ten(lb.ten), sql.SQL(", ").join(sql.SQL("{} = t.{}").format(sql.Identifier(c), sql.Identifier(c)) for c in cot_luat),
            sql.Identifier(tam), sql.SQL(" AND ").join(sql.SQL("b.{} = t.{}").format(sql.Identifier(c), sql.Identifier(c)) for c in pk))
        khoi: list[tuple] = []
        with self.kn.cursor(name=f"doc_{tam}") as cur:
            cur.itersize = KHOI
            cur.execute(sql.SQL("SELECT {} FROM {} WHERE tenant_id = %s").format(
                sql.SQL(", ").join(map(sql.Identifier, doc)), _ten(lb.ten)), (tenant,))
            for hang in cur:
                v = dict(zip(doc, hang))
                moi = self._che_dong(tenant, lb, kt, v, cot_luat, tk)
                khoi.append(tuple(v[c] for c in pk) + tuple(moi[c] for c in cot_luat))
                if len(khoi) >= KHOI:
                    self._ghi_khoi(tam, pk + cot_luat, khoi, cap_nhat)
        self._ghi_khoi(tam, pk + cot_luat, khoi, cap_nhat)
        self.kn.execute(sql.SQL("DROP TABLE {}").format(sql.Identifier(tam)))

    def _ghi_khoi(self, tam: str, cot: list[str], khoi: list[tuple], cap_nhat) -> None:
        if not khoi:
            return
        with self.kn.cursor() as cur:
            with cur.copy(sql.SQL("COPY {} ({}) FROM STDIN").format(
                    sql.Identifier(tam), sql.SQL(", ").join(map(sql.Identifier, cot)))) as cp:
                for h in khoi:
                    cp.write_row(h)
            cur.execute(cap_nhat)
            cur.execute(sql.SQL("TRUNCATE {}").format(sql.Identifier(tam)))
        khoi.clear()

    def _che_dong(self, tenant, lb: LuatBang, kt: KhoaTenant, v: dict, cot_luat: list[str], tk: dict) -> dict:
        tk["dong"] += 1
        ro_moi: dict[str, str | None] = {}
        ra: dict[str, object] = {}
        dek = v.get(lb.cot_dek) if lb.cot_dek else None
        row_id = str(v.get(lb.aad_dong))
        for c in cot_luat:
            l = lb.cot[c]
            if l["luat"] == "chi_muc_mu":
                nguon = ro_moi.get(l["tu"])
                ra[c] = None if nguon is None or kt.goc_bidx_moi is None else \
                    c2.chi_muc_mu(kt.goc_bidx_moi, str(tenant), f"{lb.ten}.{c}", nguon)
                if v[c] is not None:
                    tk["cot"][c]["doi"] += 1
                continue
            ma_hoa = c.endswith("_enc")
            goc = v[c]
            if goc is None:
                ro_moi[c], ra[c] = None, None
                continue
            aad = c2.aad(str(tenant), lb.toa_do(c), row_id)
            if ma_hoa:
                khoa_cu = kt.cu.get(dek)
                try:
                    if khoa_cu is None:
                        raise ValueError("thiếu DEK nguồn")
                    goc = c2.giai_ma(khoa_cu, bytes(goc), aad).decode("utf-8")
                except Exception:
                    tk["cot"][c]["khong_giai_duoc"] += 1
                    goc = None
            if goc is not None:
                self._thu_mau(goc)
            moi = self._ap_luat(l, goc, v, ro_moi, lb, c)
            ro_moi[c] = moi
            if moi != goc:
                tk["cot"][c]["doi"] += 1
            if ma_hoa:
                ra[c] = None if moi is None else c2.ma_hoa(kt.moi[dek], dek, moi.encode("utf-8"), aad)
            else:
                ra[c] = moi
        return ra

    def _thu_mau(self, goc: str) -> None:
        """Mọi giá trị nguồn của cột trong luật (kể cả luật 'giu') đều vào tập mẫu quét theo hình dạng,
        để cột bị bỏ sót luật vẫn bị bước quét phát hiện."""
        g = goc.strip()
        so = chu_so(g)
        if 9 <= len(so) <= 12 and len(so) >= len(g.replace(" ", "")) - 3:
            self.mau.so.add(so)
        elif "@" in g:
            self.mau.thu.add(g.lower())
        elif len(g) >= 8 and not _NGAY.fullmatch(g):
            self.mau.van_ban.add(goc)

    # ------------------------------------------------------------------ luật
    def _ap_luat(self, l: dict, goc: str | None, v: dict, ro_moi: dict, lb: LuatBang, c: str) -> str | None:
        ax = self.anh_xa
        loai = l["luat"]
        if loai == "giu":
            return goc
        if loai == "xoa":
            return None
        khoa_goc = goc if goc is not None else f"#khong-giai-duoc|{lb.ten}|{c}|{v.get(lb.aad_dong)}"
        gioi = v.get(l["gioi"]) if l.get("gioi") else None
        if loai == "ho_ten":
            if goc:
                if gia.thuoc_kho_ho_ten_gia(goc.strip()):
                    self.mau.ho_ten_trung_kho_gia += 1
                else:
                    self.mau.ho_ten.add(chuan_ten(goc))
            return ax.anh_xa("ho_ten", chuan_ten(khoa_goc), lambda r: gia.ho_ten(r, gioi), duy_nhat=False)
        if loai == "ngay_sinh_nhom_tuoi":
            try:
                d = date.fromisoformat(khoa_goc)
            except ValueError:
                d = date(self.luat.ngay_moc.year - 40, 1, 1)
            return ax.anh_xa("ngay_sinh", khoa_goc, lambda r: gia.ngay_sinh_cung_nhom(
                r, d, self.luat.ngay_moc, self.luat.nhom_tuoi_nam).isoformat(), duy_nhat=False)
        if loai == "so_dinh_danh":
            if goc:
                self.mau.so.add(chu_so(goc))
            ns = ro_moi.get(l.get("ngay_sinh", ""))
            nam = int(ns[:4]) if ns else None
            return ax.anh_xa("so_dinh_danh", "".join(khoa_goc.split()).upper(), lambda r: gia.so_dinh_danh(
                r, gioi, nam if nam is not None else r.randrange(1966, 2005)))
        sinh = {"so_the_dang": gia.so_the_dang, "dien_thoai": gia.dien_thoai, "thu_dien_tu": gia.thu_dien_tu,
                "ten_dang_nhap": gia.ten_dang_nhap}
        if loai in sinh:
            if goc and loai in ("so_the_dang", "dien_thoai"):
                self.mau.so.add(chu_so(goc))
            elif goc and loai == "thu_dien_tu":
                self.mau.thu.add(goc.strip().lower())
            elif goc:
                self.mau.van_ban.add(goc)
            return ax.anh_xa(loai, khoa_goc.strip().lower() if loai == "thu_dien_tu" else khoa_goc, sinh[loai])
        if loai == "ten_tep":
            if goc and len(goc) >= 8:
                self.mau.van_ban.add(goc)
            return ax.anh_xa("ten_tep", khoa_goc, lambda r: gia.ten_tep(r, khoa_goc))
        if loai == "van_ban_cung_do_dai":
            if goc and len(goc) >= 8:
                self.mau.van_ban.add(goc)
            return ax.anh_xa("van_ban", khoa_goc, lambda r: gia.van_ban_cung_do_dai(r, len(goc or "")), duy_nhat=False)
        raise ValueError(f"Luật chưa hỗ trợ: {loai}")


def _ten(bang: str) -> sql.Composable:
    s, t = bang.split(".", 1)
    return sql.Identifier(s, t)
