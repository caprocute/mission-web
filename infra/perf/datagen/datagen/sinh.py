"""Sinh dữ liệu một cell theo hồ sơ phân phối, ghi tệp COPY theo từng tenant."""

from __future__ import annotations

import csv
import hashlib
import hmac
import json
import math
import time
import uuid
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

from . import ten_gia
from .bang import CHUA_CO_MIGRATION, COT
from .ghi import BoGhiCopy
from .ho_so import THU_MUC_DL07, HoSo
from .ngau_nhien import BoChiaTheoTyLe, NguonNgauNhien, ban_ma_gia

VO_HAN = "infinity"
UTC = timezone.utc
PHIEN_BAN_MAY_TRANG_THAI = "1.0.0"  # suy luận: task_lifecycle đã ghim ở CORE-02-13
# Khoá HMAC của chỉ mục mù chỉ dùng ở môi trường perf (không phải bí mật: dữ liệu đều giả).
KHOA_CHI_MUC_MU_PERF = b"mission-perf-blind-index-khong-dung-cho-san-xuat"
TRANG_THAI_CO_MINH_CHUNG = ("DANG_THUC_HIEN", "CHO_NGHIEM_THU", "HOAN_THANH", "TAM_DUNG")
DONG_TU = ["Xây dựng", "Triển khai", "Rà soát", "Tổng hợp", "Hoàn thiện", "Tổ chức", "Kiểm tra",
           "Báo cáo", "Tham mưu", "Theo dõi"]
DOI_TUONG = ["kế hoạch công tác", "báo cáo chuyên đề", "đề án", "văn bản hướng dẫn", "hội nghị",
             "số liệu thống kê", "chương trình hành động", "hồ sơ thẩm định"]


@dataclass
class DonVi:
    id: uuid.UUID
    cap: int
    cha: "DonVi | None"
    khoi: str
    nguoi: list = field(default_factory=list)
    truong: uuid.UUID | None = None


@dataclass
class ThongKe:
    dem: Counter = field(default_factory=Counter)
    trang_thai_quy_cu: Counter = field(default_factory=Counter)
    trang_thai_quy_hien_hanh: Counter = field(default_factory=Counter)
    so_phien_ban_moi_minh_chung: Counter = field(default_factory=Counter)
    kich_thuoc: list = field(default_factory=list)
    giay: float = 0.0

    def json(self) -> dict:
        kt = sorted(self.kich_thuoc)
        return {
            "dem": dict(self.dem),
            "trang_thai_quy_cu": dict(self.trang_thai_quy_cu),
            "trang_thai_quy_hien_hanh": dict(self.trang_thai_quy_hien_hanh),
            "so_phien_ban_moi_minh_chung": {str(k): v for k, v in self.so_phien_ban_moi_minh_chung.items()},
            "kich_thuoc_trung_vi": kt[len(kt) // 2] if kt else None,
            "giay_sinh": round(self.giay, 3),
        }


def ma_tenant(ho_so: HoSo, stt: int) -> uuid.UUID:
    return NguonNgauNhien(ho_so.hat_giong, "tenant", stt).ma()


def _dt(d: date, giay: int = 9 * 3600) -> datetime:
    return datetime(d.year, d.month, d.day, tzinfo=UTC) + timedelta(seconds=giay)


def _chi_muc_mu(gia_tri: str) -> bytes:
    return hmac.new(KHOA_CHI_MUC_MU_PERF, gia_tri.encode(), hashlib.sha256).digest()


def _so_nguyen_theo_trung_binh(rng: NguonNgauNhien, tb: float) -> int:
    """Lấy phần nguyên cộng một với xác suất phần lẻ: tổng sát trung bình, phương sai nhỏ."""
    n = math.floor(tb)
    return n + (1 if rng.random() < tb - n else 0)


class BoSinh:
    def __init__(self, ho_so: HoSo, thu_muc: Path) -> None:
        self.hs = ho_so
        self.thu_muc = thu_muc

    def sinh_tat_ca(self) -> dict:
        tong = {"ho_so": self.hs.ten, "he_so": self.hs.he_so, "hat_giong": self.hs.hat_giong,
                "tenant": {}, "chua_sinh": CHUA_CO_MIGRATION,
                "tien_trinh_flowable": {"so_luong": self.hs.tien_trinh_flowable,
                                        "ghi_chu": "tách DL-08b (gọi API khởi động theo lô)"}}
        for stt in range(self.hs.so_tenant):
            t = ma_tenant(self.hs, stt)
            tk = self.sinh_tenant(t, stt)
            tong["tenant"][str(t)] = tk.json()
        (self.thu_muc / "thong-ke.json").write_text(
            json.dumps(tong, ensure_ascii=False, indent=2), encoding="utf-8")
        return tong

    # ------------------------------------------------------------------ một tenant
    def sinh_tenant(self, tenant: uuid.UUID, stt: int) -> ThongKe:
        bat_dau = time.perf_counter()
        hs = self.hs
        thu_muc = self.thu_muc / f"tenant-{stt:03d}-{tenant}"
        ghi = BoGhiCopy(thu_muc, COT)
        tk = ThongKe()
        he_thong = NguonNgauNhien(hs.hat_giong, tenant, "he-thong").ma()
        nam = hs.nam
        dau_nam, giua_nam = date(nam, 1, 1), date(nam, 7, 1)

        # Khoá DEK thử (chỉ bản bọc giả) cho trường C2 của người và tên tệp.
        dek: dict[str, uuid.UUID] = {}
        rng = NguonNgauNhien(hs.hat_giong, tenant, "dek")
        for muc_dich in ("DINH_DANH_CA_NHAN", "TEP_MINH_CHUNG", "BLIND_INDEX"):
            dek[muc_dich] = rng.ma()
            ghi.ghi("keyring.dek", {
                "id": dek[muc_dich], "tenant_id": tenant, "purpose": muc_dich,
                "valid_month": dau_nam, "wrapped_dek": rng.byte(60), "kek_ref": "perf/kek-thu",
                "alg_version": 1, "status": "ACTIVE"})

        # Kỳ đánh giá theo quý.
        ky: dict[int, dict] = {}
        rng = NguonNgauNhien(hs.hat_giong, tenant, "ky")
        for q in hs.cac_quy:
            bd = date(nam, 3 * q - 2, 1)
            kt = (date(nam + 1, 1, 1) if q == 4 else date(nam, 3 * q + 1, 1)) - timedelta(days=1)
            da_khoa = q < hs.quy_hien_hanh
            ky[q] = {"id": rng.ma(), "bucket": nam * 10 + q, "bd": bd, "kt": kt}
            ghi.ghi("config.period", {
                "id": ky[q]["id"], "tenant_id": tenant, "created_by": he_thong,
                "updated_by": he_thong, "code": f"Q{q}-{nam}", "name": f"Quý {q} năm {nam}",
                "kind": "QUY", "flow": "CA_HAI", "start_date": bd, "end_date": kt,
                "period_bucket": nam * 10 + q, "status": "DA_KHOA" if da_khoa else "MO",
                "locked_at": _dt(kt + timedelta(days=10)) if da_khoa else None})

        khoi, chuc_danh = self._danh_muc(ghi, tenant, he_thong)
        don_vi = self._cay_to_chuc(ghi, tk, tenant, he_thong, khoi, dau_nam, giua_nam)
        nguoi = self._nhan_su(ghi, tk, tenant, he_thong, don_vi, chuc_danh, dek, dau_nam, giua_nam)
        self._nhiem_vu(ghi, tk, tenant, ky, don_vi, nguoi, dek)
        ghi.dong()
        tk.dem.update(ghi.dem)
        tk.giay = time.perf_counter() - bat_dau
        return tk

    # ------------------------------------------------------------------ danh mục DL-07
    def _danh_muc(self, ghi, tenant, he_thong):
        rng = NguonNgauNhien(self.hs.hat_giong, tenant, "danh-muc")
        khoi: dict[str, tuple[uuid.UUID, str]] = {}
        with (THU_MUC_DL07 / "subject_group.csv").open(encoding="utf-8") as f:
            for r in csv.DictReader(f):
                i = rng.ma()
                khoi[r["code"]] = (i, r["flow"])
                ghi.ghi("org.subject_group", {
                    "id": i, "tenant_id": tenant, "code": r["code"], "name": r["name"],
                    "flow": r["flow"], "description": r["description"],
                    "valid_from": r["valid_from"], "valid_to": r["valid_to"] or VO_HAN,
                    "created_by": he_thong, "updated_by": he_thong})
        chuc_danh: dict[str, uuid.UUID] = {}
        dong = []
        with (THU_MUC_DL07 / "job_title.csv").open(encoding="utf-8") as f:
            dong.extend(csv.DictReader(f))
        # Chức danh phổ thông cho người không thuộc diện Trung ương quản lý (giả lập, YC-PT-14).
        for ma, ten, cap in (("CD-PERF-TP", "Trưởng phòng", 5), ("CD-PERF-PP", "Phó Trưởng phòng", 6),
                             ("CD-PERF-CVC", "Chuyên viên chính", 7), ("CD-PERF-CV", "Chuyên viên", 8)):
            dong.append({"code": ma, "name": ten, "level": cap, "is_central_managed": "false",
                         "subject_group_code": "", "valid_from": "2026-01-01", "valid_to": ""})
        for r in dong:
            i = rng.ma()
            chuc_danh[r["code"]] = i
            sg = khoi.get(r["subject_group_code"])
            ghi.ghi("org.job_title", {
                "id": i, "tenant_id": tenant, "code": r["code"], "name": r["name"],
                "level": int(r["level"]), "is_central_managed": r["is_central_managed"] == "true",
                "subject_group_id": sg[0] if sg else None, "valid_from": r["valid_from"],
                "valid_to": r["valid_to"] or VO_HAN, "created_by": he_thong, "updated_by": he_thong})
        return khoi, chuc_danh

    # ------------------------------------------------------------------ cây tổ chức 5 cấp
    def _cay_to_chuc(self, ghi, tk, tenant, he_thong, khoi, dau_nam, giua_nam) -> list[DonVi]:
        hs = self.hs
        rng = NguonNgauNhien(hs.hat_giong, tenant, "cay")
        tong = hs.don_vi_moi_tenant
        so_cap = [1] + [max(1, round(tong * r)) for r in hs.ty_le_cap[1:]]
        so_cap[-1] = max(1, tong - sum(so_cap[:-1]))
        theo_cap: list[list[DonVi]] = []
        ma_khoi = sorted(khoi)
        for cap, so in enumerate(so_cap, start=1):
            muc = []
            for _ in range(so):
                cha = rng.choice(theo_cap[-1]) if theo_cap else None
                k = "K2" if cha is None else (rng.choice(ma_khoi) if cap == 2 else cha.khoi)
                muc.append(DonVi(rng.ma(), cap, cha, k))
            theo_cap.append(muc)
        tat_ca = [dv for muc in theo_cap for dv in muc]
        cap_hanh_chinh = {1: "TINH", 2: "TINH", 3: "XA", 4: "XA", 5: "XA"}  # suy luận
        for stt, dv in enumerate(tat_ca, start=1):
            loai, ten = ten_gia.ten_don_vi(rng, dv.cap, stt)
            sg_id, luong = khoi[dv.khoi]
            luong_ap_dung = ["DANG", "NHA_NUOC"] if luong == "CA_HAI" else [luong]
            doi_ten = dv.cap > 1 and rng.random() < hs.ty_le_doi_phien_ban
            moc = [(dau_nam, giua_nam if doi_ten else VO_HAN, ten)]
            if doi_ten:
                moc.append((giua_nam, VO_HAN, ten + " (sau sắp xếp)"))
            for tu, den, ten_pb in moc:
                ghi.ghi("org.org_unit", {
                    "version_id": rng.ma(), "id": dv.id, "tenant_id": tenant,
                    "code": f"DV{stt:06d}", "name": ten_pb, "short_name": None, "unit_type": loai,
                    "subject_group_id": sg_id, "flows": luong_ap_dung,
                    "level": cap_hanh_chinh[dv.cap], "valid_from": tu, "valid_to": den,
                    "created_by": he_thong, "updated_by": he_thong})
            if dv.cha is not None:
                ghi.ghi("org.org_unit_relation", {
                    "version_id": rng.ma(), "id": rng.ma(), "tenant_id": tenant,
                    "tree": "CHUYEN_MON", "parent_unit_id": dv.cha.id, "child_unit_id": dv.id,
                    "relation_type": "TRUC_THUOC", "valid_from": dau_nam, "valid_to": VO_HAN,
                    "created_by": he_thong, "updated_by": he_thong})
            tk.dem[f"don_vi_cap_{dv.cap}"] += 1
        return tat_ca

    # ------------------------------------------------------------------ nhân sự và phân công
    def _nhan_su(self, ghi, tk, tenant, he_thong, don_vi, chuc_danh, dek, dau_nam, giua_nam):
        hs = self.hs
        rng = NguonNgauNhien(hs.hat_giong, tenant, "nguoi")
        so_nguoi = hs.tai_khoan_moi_tenant
        trong_so = [hs.trong_so_nguoi_theo_cap[dv.cap - 1] for dv in don_vi]
        # Mỗi đơn vị có ít nhất một người (người đầu tiên là trưởng đơn vị), phần còn lại theo trọng số.
        noi_lam = list(don_vi) + rng.choices(don_vi, weights=trong_so, k=max(0, so_nguoi - len(don_vi)))
        nguoi = []
        for dv in noi_lam[:so_nguoi]:
            pid = rng.ma()
            ho_ten, gioi = ten_gia.ho_ten(rng)
            ns = ten_gia.ngay_sinh(rng, hs.nam)
            cccd = ten_gia.so_dinh_danh_gia(rng, gioi, ns.year)
            dang_vien = rng.random() < hs.ty_le_dang_vien
            so_the = f"{ten_gia.MA_TINH_GIA}{rng.randrange(10**9):09d}" if dang_vien else None
            k = dek["DINH_DANH_CA_NHAN"]
            ghi.ghi("org.person", {
                "version_id": rng.ma(), "id": pid, "tenant_id": tenant, "full_name": ho_ten,
                "gender": gioi, "birth_date_enc": ban_ma_gia(rng, k, len(ns.isoformat())),
                "national_id_enc": ban_ma_gia(rng, k, len(cccd)),
                "national_id_bidx": _chi_muc_mu(cccd),
                "party_member_no_enc": ban_ma_gia(rng, k, len(so_the)) if so_the else None,
                "party_member_no_bidx": _chi_muc_mu(so_the) if so_the else None,
                "is_party_member": dang_vien, "cbccvc_code": f"PERF{len(nguoi) + 1:08d}",
                "employment_status": "DANG_CONG_TAC", "dek_id": k, "valid_from": dau_nam,
                "valid_to": VO_HAN, "created_by": he_thong, "updated_by": he_thong})
            la_truong = dv.truong is None
            if la_truong:
                dv.truong = pid
            dv.nguoi.append(pid)
            cd = chuc_danh["CD-PERF-TP" if la_truong else rng.choice(["CD-PERF-CV", "CD-PERF-CVC", "CD-PERF-PP"])]
            # Một phân công chính tại mỗi thời điểm; một phần nhỏ điều động giữa năm sang đơn vị khác.
            dieu_dong = not la_truong and len(don_vi) > 1 and rng.random() < hs.ty_le_doi_phien_ban
            moc = [(dau_nam, giua_nam if dieu_dong else VO_HAN, dv)]
            if dieu_dong:
                moc.append((giua_nam, VO_HAN, rng.choice([d for d in don_vi if d is not dv])))
            for tu, den, noi in moc:
                ghi.ghi("org.assignment", {
                    "version_id": rng.ma(), "id": rng.ma(), "tenant_id": tenant, "person_id": pid,
                    "org_unit_id": noi.id, "job_title_id": cd, "kind": "CHINH",
                    "branch": "CHINH_QUYEN", "is_head": la_truong and noi is dv, "valid_from": tu,
                    "valid_to": den, "created_by": he_thong, "updated_by": he_thong})
            nguoi.append((pid, dv))
        tk.dem["dieu_dong"] = ghi.dem.get("org.assignment", 0) - len(nguoi)
        return nguoi

    # ------------------------------------------------------------------ nhiệm vụ, tiến độ, minh chứng
    def _xac_suat_minh_chung(self) -> float:
        hs = self.hs
        so_quy = len(hs.cac_quy)
        cu = sum(v for k, v in hs.trang_thai_quy_cu.items() if k in TRANG_THAI_CO_MINH_CHUNG)
        moi = sum(v for k, v in hs.trang_thai_quy_hien_hanh.items() if k in TRANG_THAI_CO_MINH_CHUNG)
        so_quy_cu = sum(1 for q in hs.cac_quy if q < hs.quy_hien_hanh)
        ty_le = (cu * so_quy_cu + moi * (so_quy - so_quy_cu)) / so_quy
        return min(1.0, hs.phien_ban_minh_chung / (hs.nhiem_vu * ty_le * hs.phien_ban_moi_minh_chung_tb))

    def _nhiem_vu(self, ghi, tk, tenant, ky, don_vi, nguoi, dek) -> None:
        hs = self.hs
        tb = hs.nhiem_vu_moi_nguoi_moi_quy
        p_mc = self._xac_suat_minh_chung()
        mu = math.log(hs.kich_thuoc_trung_vi)
        for q in hs.cac_quy:
            rng = NguonNgauNhien(hs.hat_giong, tenant, "nhiem-vu", q)
            k = ky[q]
            bucket, bd, kt = k["bucket"], k["bd"], k["kt"]
            so_ngay = (kt - bd).days
            phan_phoi = hs.trang_thai_quy_cu if q < hs.quy_hien_hanh else hs.trang_thai_quy_hien_hanh
            dem_tt = tk.trang_thai_quy_cu if q < hs.quy_hien_hanh else tk.trang_thai_quy_hien_hanh
            stt = 0
            chia_tt = BoChiaTheoTyLe(phan_phoi)
            chia_pb = BoChiaTheoTyLe(hs.so_phien_ban_moi_minh_chung)
            for pid, dv in nguoi:
                giao = dv.truong if dv.truong != pid else (dv.cha.truong if dv.cha else pid)
                for _ in range(_so_nguyen_theo_trung_binh(rng, tb)):
                    stt += 1
                    tid = rng.ma()
                    tt = chia_tt.chon()
                    dem_tt[tt] += 1
                    ngay_giao = bd + timedelta(days=rng.randrange(max(1, so_ngay // 2)))
                    han = ngay_giao + timedelta(days=rng.randrange(7, max(8, (kt - ngay_giao).days + 1)))
                    han = min(han, kt)
                    tien_do = {"NHAP": 0, "DA_GIAO": 0, "HUY": 0, "CHO_NGHIEM_THU": 1, "HOAN_THANH": 1,
                               "DANG_THUC_HIEN": round(rng.uniform(0.1, 0.9), 2),
                               "TAM_DUNG": round(rng.uniform(0.1, 0.6), 2)}[tt]
                    ke_hoach = Decimal(rng.choice([1, 1, 1, 2, 3, 5]))
                    tieu_de = f"{rng.choice(DONG_TU)} {rng.choice(DOI_TUONG)} quý {q}"
                    da_giao = tt != "NHAP"
                    hoan_thanh = _dt(han, 15 * 3600) if tt == "HOAN_THANH" else None
                    ghi.ghi("task.task", {
                        "id": tid, "tenant_id": tenant, "created_by": giao, "updated_by": giao,
                        "period_id": k["id"], "period_bucket": bucket, "code": f"NV{q}.{stt // 1000:06d}.{stt % 1000:03d}",
                        "title": tieu_de, "task_group": "THEN_CHOT" if rng.random() < hs.ty_le_nhiem_vu_then_chot else "THUONG_XUYEN",
                        "is_key_task": False, "source": rng.choice(["KE_HOACH", "KE_HOACH", "VAN_BAN", "DOT_XUAT"]),
                        "assigner_id": giao, "assigned_at": _dt(ngay_giao) if da_giao else None,
                        "acceptor_id": giao if tt in ("CHO_NGHIEM_THU", "HOAN_THANH") else None,
                        "start_date": ngay_giao, "due_date": han, "planned_quantity": ke_hoach,
                        "done_quantity": ke_hoach * Decimal(str(tien_do)), "unit": "sản phẩm",
                        "progress_pct": Decimal(str(tien_do)), "status": tt,
                        "is_overdue": tt == "DANG_THUC_HIEN" and q < hs.quy_hien_hanh,
                        "dup_fingerprint": hashlib.sha256(f"{tid}{tieu_de}".encode()).digest(),
                        "state_machine_version": PHIEN_BAN_MAY_TRANG_THAI, "completed_at": hoan_thanh}, bucket)
                    ghi.ghi("task.task_assignee", {
                        "id": rng.ma(), "tenant_id": tenant, "created_by": giao, "updated_by": giao,
                        "period_id": k["id"], "period_bucket": bucket, "task_id": tid,
                        "assignee_type": "CA_NHAN", "person_id": pid, "role": "CHU_TRI",
                        "accepted_at": _dt(ngay_giao, 11 * 3600) if tt not in ("NHAP", "DA_GIAO") else None,
                        "valid_from": ngay_giao}, bucket)
                    so_bao_cao = {"DANG_THUC_HIEN": rng.randint(1, 3), "CHO_NGHIEM_THU": rng.randint(2, 3),
                                  "HOAN_THANH": rng.randint(2, 3), "TAM_DUNG": 1}.get(tt, 0)
                    for i in range(so_bao_cao):
                        ghi.ghi("task.task_progress", {
                            "id": rng.ma(), "tenant_id": tenant, "created_by": pid, "updated_by": pid,
                            "period_id": k["id"], "period_bucket": bucket, "task_id": tid,
                            "reported_at": _dt(ngay_giao + timedelta(days=i + 1), 16 * 3600),
                            "progress_pct": Decimal(str(round(tien_do * (i + 1) / so_bao_cao, 4))),
                            "channel": rng.choice(["WEB", "WEB", "MOBILE"]),
                            "idempotency_key": f"perf-{tid.hex[:20]}-{i}"}, bucket)
                    if tt in TRANG_THAI_CO_MINH_CHUNG and rng.random() < p_mc:
                        self._minh_chung(ghi, tk, rng, tenant, k, tid, tt, pid, giao, ngay_giao, dek, mu, chia_pb)

    def _minh_chung(self, ghi, tk, rng, tenant, k, tid, tt, pid, giao, ngay_giao, dek, mu, chia_pb) -> None:
        hs = self.hs
        bucket = k["bucket"]
        did, eid, oid = rng.ma(), rng.ma(), rng.ma()
        so_pb = chia_pb.chon()
        tk.so_phien_ban_moi_minh_chung[so_pb] += 1
        trang_thai_cuoi = {"HOAN_THANH": "DA_XAC_NHAN", "CHO_NGHIEM_THU": "DANG_NGHIEM_THU",
                           "DANG_THUC_HIEN": "DA_NOP", "TAM_DUNG": "NHAP"}[tt]
        ghi.ghi("product.deliverable", {
            "id": did, "tenant_id": tenant, "created_by": giao, "updated_by": giao,
            "period_id": k["id"], "period_bucket": bucket, "task_id": tid,
            "name": f"Sản phẩm của nhiệm vụ {tid.hex[:8]}", "deliverable_type": rng.choice(["VAN_BAN", "BAO_CAO", "SO_LIEU"]),
            "unit": "văn bản", "quantity_planned": Decimal(1),
            "quantity_accepted": Decimal(1) if tt == "HOAN_THANH" else Decimal(0),
            "status": {"HOAN_THANH": "DA_XAC_NHAN", "CHO_NGHIEM_THU": "DANG_THUC_HIEN"}.get(tt, "DANG_THUC_HIEN")}, bucket)
        phien_ban = []
        co_tep = False
        for so in range(1, so_pb + 1):
            cuoi = so == so_pb
            tt_pb = trang_thai_cuoi if cuoi else "YEU_CAU_BO_SUNG"
            nop = tt_pb != "NHAP"
            vid = rng.ma()
            kich_thuoc = min(hs.kich_thuoc_tran, max(1024, int(rng.lognormvariate(mu, hs.kich_thuoc_sigma))))
            ovid = rng.ma() if nop else None
            sha = rng.byte(32)
            if nop:
                co_tep = True
                if len(tk.kich_thuoc) < 200_000:
                    tk.kich_thuoc.append(kich_thuoc)
                ghi.ghi("evidence.object_version", {
                    "id": ovid, "tenant_id": tenant, "object_meta_id": oid, "version_no": so,
                    "sha256": sha, "size_bytes": kich_thuoc, "bucket": "mission-evidence-perf",
                    "object_key": f"t/{tenant}/minh-chung/{oid}/{so}", "ceph_version_id": rng.byte(8).hex(),
                    "scan_status": "SACH", "created_by": pid})
            ngay_nop = _dt(ngay_giao + timedelta(days=so + 2), 10 * 3600)
            ghi.ghi("product.evidence_version", {
                "id": vid, "tenant_id": tenant, "created_by": pid, "updated_by": pid,
                "period_id": k["id"], "period_bucket": bucket, "evidence_id": eid, "task_id": tid,
                "version_no": so, "status": tt_pb, "object_id": oid if nop else None,
                "object_version_id": ovid, "content_sha256": sha if nop else None,
                "size_bytes": kich_thuoc, "media_type": "application/pdf",
                "file_name": f"minh-chung-{so}.pdf", "source": "TAI_LEN",
                "scan_status": "SACH" if nop else "CHO_QUET", "submitted_by": pid if nop else None,
                "submitted_at": ngay_nop if nop else None,
                "accepted_at": ngay_nop + timedelta(days=2) if tt_pb == "DA_XAC_NHAN" else None}, bucket)
            phien_ban.append((vid, ovid))
        ghi.ghi("product.evidence", {
            "id": eid, "tenant_id": tenant, "created_by": pid, "updated_by": pid,
            "period_id": k["id"], "period_bucket": bucket, "deliverable_id": did, "task_id": tid,
            "title": f"Minh chứng nhiệm vụ {tid.hex[:8]}", "current_version_id": phien_ban[-1][0],
            "current_status": trang_thai_cuoi, "source_type": "TAI_LEN"}, bucket)
        if co_tep:
            ghi.ghi("evidence.object_meta", {
                "id": oid, "tenant_id": tenant, "created_by": pid, "updated_by": pid,
                "owner_module": "product", "owner_ref_id": eid,
                "file_name_enc": ban_ma_gia(rng, dek["TEP_MINH_CHUNG"], len("minh-chung-1.pdf")),
                "dek_id": dek["TEP_MINH_CHUNG"], "media_type": "application/pdf", "classification": "C1",
                "current_version_id": next(o for _, o in reversed(phien_ban) if o), "retention_class": "MINH_CHUNG"})
