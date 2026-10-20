"""Kiểm tra tĩnh bộ mẫu biểu của Policy Pack (DL-05, H5, QT-09, QT-11).

Cách chạy:  python -I data/seed/docgen/kiem_mau.py data/policy-pack [--nghiem-ngat]

Tập lệnh duyệt mọi tệp ``<gói>/templates/<mẫu>/manifest.json`` dưới thư mục gốc và kiểm tra:
  * cấu trúc tệp kê khai, mã biểu mẫu thuộc danh mục ``dossier.form_instance.form_code``;
  * tag vùng trộn dạng ``snake_case``, không trùng trong một mẫu;
  * nguồn dữ liệu là cột có thật trong TL-03 (danh sách ``NGUON_HOP_LE``) hoặc giá trị suy ra ``suy_ra:<tên>``;
  * vùng số liệu điểm, mức bị khoá (BR-11-02), có tem phiên bản ở chân trang (BR-11-03),
    nhãn tiêu chí chung trỏ tới bảng nhãn có thật (BR-11-04), không in dữ liệu mức C3;
  * nếu đã có tệp DOCX: tệp là OOXML hợp lệ và tập tag content control trùng khít tập tag trong kê khai.

Ở trạng thái ``KHUNG_CHO_BAN_MAU`` thiếu tệp DOCX chỉ là cảnh báo (trừ khi chạy ``--nghiem-ngat``); ở mọi trạng thái
khác thiếu DOCX là lỗi. Mẫu ``GIA_LAP`` (YC-PT-14) phải in dòng "MẪU GIẢ LẬP" trên tài liệu.
Chỉ dùng thư viện chuẩn của Python 3.12.
"""
from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

MA_BIEU_MAU = {"MAU_1A", "MAU_1B", "MAU_1C", "MAU_1D", "MAU_2", "MAU_3", "BANG_CHAM_DIEM"}
TRANG_THAI = {"KHUNG_CHO_BAN_MAU", "GIA_LAP", "CHO_DUYET", "HIEU_LUC"}
DAU_GIA_LAP = "MẪU GIẢ LẬP"
LUONG = {"DANG", "NHA_NUOC"}
DOI_TUONG = {"CA_NHAN", "TAP_THE", "DANH_SACH"}
KIEU = {"van_ban", "van_ban_dai", "so", "ngay", "co", "bang", "nhan_muc", "nhan_tieu_chi", "nhan_trang_thai", "nhan_nhom"}
KIEU_SO_LIEU = {"so", "nhan_muc"}
VUNG = {"dau_trang", "than", "ky", "chan_trang"}
MUC_DU_LIEU = {"C0", "C1", "C2"}  # C3 chỉ xử lý theo luồng E2EE, không bao giờ in lên biểu mẫu sinh ở máy chủ
NGUON_PHAI_KHOA = ("appraisal.score_snapshot", "appraisal.rating_proposal", "appraisal.general_criteria_result.met")
TAG = re.compile(r"^[a-z][a-z0-9_]*$")
SUY_RA = re.compile(r"^suy_ra:[a-z][a-z0-9_]*$")
TEM_PHIEN_BAN = {"appraisal.score_snapshot.policy_version", "dossier.form_instance.template_version"}

# Bảng và cột theo TL-03 (từ điển dữ liệu). Thêm cột mới ở đây khi TL-03 bổ sung, không tự đặt tên cột.
NGUON_HOP_LE: dict[str, set[str]] = {
    "appraisal.score_snapshot": {
        "id", "policy_version", "policy_content_hash", "input_hash", "total_score", "regular_score", "key_task_score",
        "general_criteria_score", "kpi_score", "exceed_ratio", "objective_ratio", "general_criteria_level",
        "auto_rating_code", "rule_checks", "downgrade_reasons", "group_scores_json", "detail_json", "computed_at",
    },
    "appraisal.rating_proposal": {"level_kind", "rating_code", "proposed_at", "signed_at", "reason_enc", "comment_enc"},
    "appraisal.rating_explanation": {
        "explanation_type", "auto_text", "legal_ref", "explanation_enc", "system_level", "conditions_json",
    },
    "appraisal.general_criteria_result": {"stage", "criteria_code", "compliance_ratio", "level", "comment_enc", "met"},
    "appraisal.self_assessment": {
        "strengths_enc", "weaknesses_enc", "remedial_plan_enc", "content_enc", "breakthrough_enc", "claims_json",
        "submitted_at", "signed_at",
    },
    "appraisal.annual_rating": set(),
    "appraisal.prospect_group": {"group_code", "rank_in_group", "suggested_group", "training_plan_ref"},
    "dossier.form_instance": {"form_code", "template_version", "data_snapshot_hash", "pdf_sha256", "signed_at"},
    "dossier.review_meeting": {"meeting_type", "scheduled_at", "draft_sent_at", "meeting_at", "vote_json"},
    "dossier.remediation_item": {"content_enc", "status", "due_period_id"},
    "dossier.special_case": {"case_code", "effect", "legal_ref", "effective_from", "effective_to"},
    "oversight.complaint_response": {"record_kind", "response_enc", "response_doc_ref", "responded_at"},
    "org_replica.org_unit_v": {"code", "name"},
    "org_replica.person_v": {"full_name", "cbccvc_code"},
    "org.job_title": {"code", "name"},
    "config.period": {"code", "name", "kind", "start_date", "end_date"},
}

W_NS = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


class KetQua:
    def __init__(self) -> None:
        self.loi: list[str] = []
        self.canh_bao: list[str] = []

    def them_loi(self, noi: str, thong_diep: str) -> None:
        self.loi.append(f"LỖI   {noi}: {thong_diep}")

    def them_canh_bao(self, noi: str, thong_diep: str) -> None:
        self.canh_bao.append(f"CẢNH BÁO {noi}: {thong_diep}")


def kiem_nguon(nguon: str | None, truong: dict, khung: bool, noi: str, kq: KetQua) -> None:
    if nguon is None:
        if not (khung and truong.get("suy_luan")):
            kq.them_loi(noi, "thiếu nguồn dữ liệu (chỉ được để trống ở khung chờ bản mẫu và phải đánh dấu suy_luan)")
        return
    if SUY_RA.match(nguon):
        return
    phan = nguon.split(".")
    if len(phan) not in (2, 3):
        kq.them_loi(noi, f"nguồn '{nguon}' không đúng dạng schema.bảng[.cột] hoặc suy_ra:<tên>")
        return
    bang = ".".join(phan[:2])
    if bang not in NGUON_HOP_LE:
        kq.them_loi(noi, f"bảng '{bang}' không có trong danh sách nguồn theo TL-03")
    elif len(phan) == 3 and phan[2] not in NGUON_HOP_LE[bang]:
        kq.them_loi(noi, f"cột '{phan[2]}' không có trong bảng '{bang}' theo TL-03")


def duyet_truong(ds: list, manifest: dict, khung: bool, ten: str, tags: list[str], kq: KetQua, trong_bang: bool) -> None:
    for i, t in enumerate(ds):
        noi = f"{ten} trường #{i + 1}"
        if not isinstance(t, dict):
            kq.them_loi(noi, "phải là đối tượng JSON")
            continue
        tag = t.get("tag")
        if not isinstance(tag, str) or not TAG.match(tag):
            kq.them_loi(noi, f"tag '{tag}' không ở dạng snake_case")
            continue
        noi = f"{ten} [{tag}]"
        tags.append(tag)
        if not isinstance(t.get("nhan"), str) or not t["nhan"].strip():
            kq.them_loi(noi, "thiếu nhãn tiếng Việt")
        kieu = t.get("kieu")
        if kieu not in KIEU:
            kq.them_loi(noi, f"kiểu '{kieu}' không hợp lệ")
        if t.get("vung", "than") not in VUNG:
            kq.them_loi(noi, f"vùng '{t.get('vung')}' không hợp lệ")
        if t.get("muc_du_lieu") not in MUC_DU_LIEU:
            kq.them_loi(noi, f"mức dữ liệu '{t.get('muc_du_lieu')}' không được in lên biểu mẫu")
        nguon = t.get("nguon")
        kiem_nguon(nguon, t, khung, noi, kq)
        phai_khoa = kieu in KIEU_SO_LIEU or (isinstance(nguon, str) and nguon.startswith(NGUON_PHAI_KHOA))
        if phai_khoa and t.get("khoa_so_lieu") is not True:
            kq.them_loi(noi, "vùng số liệu điểm, mức phải khoá (BR-11-02)")
        if kieu == "nhan_tieu_chi" and not manifest.get("nhan_tieu_chi_chung"):
            kq.them_loi(noi, "dùng nhãn tiêu chí chung nhưng mẫu chưa khai báo nhan_tieu_chi_chung (BR-11-04)")
        if kieu == "bang":
            if trong_bang:
                kq.them_loi(noi, "không hỗ trợ bảng lồng trong bảng")
            cot = t.get("cot")
            if cot:
                duyet_truong(cot, manifest, khung, f"{ten} [{tag}]", tags, kq, True)
            elif not (khung and t.get("suy_luan")):
                kq.them_loi(noi, "bảng phải khai báo danh sách cột")


def tag_trong_docx(tep: Path, noi: str, kq: KetQua) -> tuple[set[str] | None, str]:
    try:
        with zipfile.ZipFile(tep) as z:
            ten = set(z.namelist())
            if "[Content_Types].xml" not in ten or "word/document.xml" not in ten:
                kq.them_loi(noi, "tệp DOCX thiếu [Content_Types].xml hoặc word/document.xml")
                return None, ""
            tags: set[str] = set()
            chu: list[str] = []
            for phan in sorted(ten):
                if not re.match(r"^word/(document|header\d*|footer\d*)\.xml$", phan):
                    continue
                goc = ElementTree.fromstring(z.read(phan))
                chu.extend(t.text or "" for t in goc.iter(f"{W_NS}t"))
                for sdt_pr in goc.iter(f"{W_NS}sdtPr"):
                    for tag in sdt_pr.iter(f"{W_NS}tag"):
                        gia_tri = tag.get(f"{W_NS}val")
                        if gia_tri:
                            tags.add(gia_tri)
            return tags, "".join(chu)
    except (zipfile.BadZipFile, ElementTree.ParseError, OSError) as e:
        kq.them_loi(noi, f"tệp DOCX không phải OOXML hợp lệ ({e.__class__.__name__})")
        return None, ""


def kiem_manifest(tep: Path, goc: Path, nhan: dict, nghiem_ngat: bool, kq: KetQua) -> str | None:
    noi = str(tep.relative_to(goc))
    try:
        m = json.loads(tep.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        kq.them_loi(noi, f"không đọc được JSON ({e})")
        return None
    form_code = m.get("form_code")
    if form_code not in MA_BIEU_MAU:
        kq.them_loi(noi, f"mã biểu mẫu '{form_code}' không thuộc danh mục")
    trang_thai = m.get("trang_thai")
    if trang_thai not in TRANG_THAI:
        kq.them_loi(noi, f"trạng thái '{trang_thai}' không hợp lệ")
    khung = trang_thai in ("KHUNG_CHO_BAN_MAU", "GIA_LAP")
    tv = m.get("template_version")
    if not isinstance(tv, str) or not tv or len(tv) > 32:
        kq.them_loi(noi, "template_version phải là chuỗi 1–32 ký tự (dossier.form_instance.template_version)")
    luong = m.get("luong")
    if not isinstance(luong, list) or not luong or not set(luong) <= LUONG:
        kq.them_loi(noi, "luong phải là danh sách con của DANG, NHA_NUOC")
    if m.get("doi_tuong") not in DOI_TUONG:
        kq.them_loi(noi, f"doi_tuong '{m.get('doi_tuong')}' không hợp lệ")
    bang_nhan = m.get("nhan_tieu_chi_chung")
    if bang_nhan is not None and bang_nhan not in nhan.get("nhan_tieu_chi_chung", {}):
        kq.them_loi(noi, f"bảng nhãn tiêu chí chung '{bang_nhan}' không có trong nhan-dung-chung.json")
    if not isinstance(m.get("can_cu"), list) or not m["can_cu"]:
        kq.them_loi(noi, "thiếu danh sách căn cứ truy vết (can_cu)")
    ban = m.get("ban_chinh_thuc") or {}
    if trang_thai == "HIEU_LUC" and not (ban.get("so_hieu_van_ban") and ban.get("ngay_ban_hanh")):
        kq.them_loi(noi, "mẫu hiệu lực phải ghi số hiệu và ngày ban hành văn bản gốc")

    ds = m.get("truong")
    if not isinstance(ds, list) or not ds:
        kq.them_loi(noi, "thiếu danh sách trường")
        return form_code
    tags: list[str] = []
    duyet_truong(ds, m, khung, noi, tags, kq, False)
    trung = sorted({t for t in tags if tags.count(t) > 1})
    if trung:
        kq.them_loi(noi, f"tag bị trùng: {', '.join(trung)}")
    tem = {t.get("nguon") for t in ds if isinstance(t, dict) and t.get("vung") == "chan_trang"}
    if not TEM_PHIEN_BAN <= tem:
        kq.them_loi(noi, "chân trang thiếu tem phiên bản bộ quy tắc hoặc phiên bản mẫu (BR-11-03)")

    tep_mau = tep.parent / str(m.get("tep_mau") or "")
    if not m.get("tep_mau") or not tep_mau.is_file():
        thong_diep = f"chưa có tệp mẫu DOCX '{m.get('tep_mau')}'"
        if trang_thai == "KHUNG_CHO_BAN_MAU" and not nghiem_ngat:
            kq.them_canh_bao(noi, thong_diep + " (chờ bản mẫu chính thức)")
        else:
            kq.them_loi(noi, thong_diep)
    else:
        tag_docx, van_ban = tag_trong_docx(tep_mau, str(tep_mau.relative_to(goc)), kq)
        if trang_thai == "GIA_LAP" and DAU_GIA_LAP not in van_ban:
            kq.them_loi(noi, "mẫu giả lập phải in dòng \"MẪU GIẢ LẬP\" trên tài liệu (YC-PT-14)")
        if tag_docx is not None:
            thieu = sorted(set(tags) - tag_docx)
            thua = sorted(tag_docx - set(tags))
            if thieu:
                kq.them_loi(noi, f"DOCX thiếu vùng trộn: {', '.join(thieu)}")
            if thua:
                kq.them_loi(noi, f"DOCX có vùng trộn không khai báo: {', '.join(thua)}")
    return form_code


def chay(goc: Path, nghiem_ngat: bool = False) -> KetQua:
    kq = KetQua()
    tep_nhan = Path(__file__).resolve().parent / "nhan-dung-chung.json"
    nhan = json.loads(tep_nhan.read_text(encoding="utf-8"))
    ds = sorted(goc.glob("**/templates/*/manifest.json"))
    if not ds:
        kq.them_loi(str(goc), "không tìm thấy tệp kê khai mẫu biểu nào")
        return kq
    theo_goi: dict[Path, list[str]] = {}
    for tep in ds:
        ma = kiem_manifest(tep, goc, nhan, nghiem_ngat, kq)
        if ma:
            theo_goi.setdefault(tep.parent.parent, []).append(ma)
    da_co: set[str] = set()
    for goi, ma_ds in theo_goi.items():
        da_co |= set(ma_ds)
        trung = sorted({x for x in ma_ds if ma_ds.count(x) > 1})
        if trung:
            kq.them_loi(str(goi.relative_to(goc)), f"một gói có hai mẫu cùng mã: {', '.join(trung)}")
    thieu = sorted(MA_BIEU_MAU - da_co)
    if thieu:
        kq.them_loi(str(goc), f"chưa có kê khai cho mẫu: {', '.join(thieu)}")
    return kq


def main(argv: list[str]) -> int:
    nghiem_ngat = "--nghiem-ngat" in argv
    tham_so = [a for a in argv if not a.startswith("--")]
    if len(tham_so) != 1:
        print("Cách dùng: python -I kiem_mau.py <thư mục data/policy-pack> [--nghiem-ngat]", file=sys.stderr)
        return 2
    goc = Path(tham_so[0]).resolve()
    kq = chay(goc, nghiem_ngat)
    for dong in kq.canh_bao + kq.loi:
        print(dong)
    print(f"Kết quả: {len(kq.loi)} lỗi, {len(kq.canh_bao)} cảnh báo.")
    return 1 if kq.loi else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
