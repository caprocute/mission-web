"""Luật của rào chắn (DV-14-03, TL-01 chương "Trí tuệ nhân tạo", BR-17-02, BR-17-04, BR-17-07). Mọi mẫu viết
trên văn bản đã qua text.fold (chữ thường, không dấu). Bộ ca đối kháng ở data/golden/ai-guardrail/."""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.guard.text import fold


def _rx(*parts: str) -> re.Pattern[str]:
    return re.compile("|".join(f"(?:{p})" for p in parts))


# ─────────────────────────── Tiêm lệnh ───────────────────────────
_VERB_IGNORE = r"(?:bo qua|phot lo|lo di|quen(?: di)?|xoa(?: bo)?|huy(?: bo)?|vo hieu(?: hoa)?|khong (?:can )?tuan (?:theo|thu)|dung tuan theo|ignore|disregard|forget|override|bypass)"  # noqa: E501
_THING = r"(?:huong dan|chi dan|chi thi|lenh|quy tac|quy dinh|rang buoc|loi nhac|nguyen tac|instructions?|rules?|prompts?|guidelines?|constraints?)"  # noqa: E501

INJECTION = _rx(
    rf"{_VERB_IGNORE}\b.{{0,40}}\b{_THING}",
    r"(?:system|he thong|developer|nha phat trien)\s*(?:prompt|loi nhac|message|thong diep)",
    r"(?:tiet lo|in ra|hien thi|cho (?:toi|tao|minh) (?:xem|biet)|lap lai|reveal|print|show|repeat)\b.{0,30}\b(?:loi nhac|prompt|huong dan (?:goc|he thong|ban dau)|chi dan (?:goc|ban dau)|cau hinh an|system)",  # noqa: E501
    r"\b(?:tu (?:bay )?gio|ke tu (?:bay )?gio|bay gio|from now on|now)\b.{0,20}\b(?:ban|may|you)\b.{0,10}\b(?:la|se la|dong vai|are|act as|will be)\b",  # noqa: E501
    r"\b(?:dong vai|hay dong vai|gia vo la|nhap vai|act as|pretend (?:to be|you are)|roleplay as)\b",
    r"\b(?:jailbreak|dan mode|do anything now|developer mode|che do (?:nha phat trien|khong gioi han|quan tri)|god mode)\b",  # noqa: E501
    r"<\|?(?:im_start|im_end|system|endoftext|eot_id|start_header_id)\|?>|\[/?inst\]|<</?sys>>|</?s>",
    r"(?:^|\s)(?:#{2,}\s*)?(?:system|assistant|he thong)\s*:\s",
    r"\b(?:khong con|khong phai) (?:bi )?(?:rang buoc|gioi han|kiem duyet)\b",
    r"\b(?:tat|go bo|bo) (?:che do )?(?:kiem duyet|rao chan|bo loc|an toan)\b",
    r"\b(?:ghi|cap nhat|xoa|sua|thay doi|chen)\b.{0,30}\b(?:co so du lieu|csdl|database|bang du lieu)\b.{0,20}\b(?:truc tiep|ngay|luon)\b",  # noqa: E501
    r"\b(?:goi|thuc thi|chay)\b.{0,15}\b(?:api|lenh he thong|shell|sql|ham)\b.{0,20}\b(?:post|put|delete|xoa|ghi|drop|update)\b",  # noqa: E501
    r"\b(?:giai ma|decode)\b.{0,30}\b(?:base64|rot13|hex|ma hoa)\b.{0,40}\b(?:lam theo|thuc hien|follow|execute)\b",
    r"\b(?:base64|rot13|hex)\b.{0,20}\b(?:giai ma|decode)\b.{0,30}\b(?:roi|va|then)\b.{0,10}\b(?:lam theo|thuc hien|follow|execute)\b",  # noqa: E501
)

# ─────────────────────────── Ca dùng bị cấm (BR-17-04) ───────────────────────────
# Hỏi quy định về chấm điểm, xếp loại (HOI_DAP_QUY_DINH) và giải thích điểm đã có là ca dùng hợp lệ; chỉ chặn khi
# yêu cầu trợ lý tự cho điểm, đề xuất mức hay nhận xét một người, một đơn vị cụ thể.
_SURNAME = (r"(?:nguyen|tran|le|pham|hoang|huynh|phan|vu|vo|dang|bui|do|ho|ngo|duong|ly|dinh|truong|lam|mai|trinh|"
            r"cao|ta|luu|ha|kieu|doan|quach|to|thai|luong|chu|nong|lo|tong|giang|khuc|vuong|[a-h])")
_TITLE = r"(?:dong chi|anh|chi|ong|ba|em|ban|co|chu|bac|thu truong|thay)"
_NAMED = rf"(?:\b{_TITLE} {_SURNAME}\b|\b{_SURNAME} (?:van|thi|duc|minh|quang|thanh|ngoc|huu|xuan)\b)"
_PERSON = (rf"(?:{_NAMED}|nguoi nay|nguoi do|cap duoi|nhan vien (?:nay|do|cua|trong)|can bo (?:nay|do|cua|trong)|"
           r"chuyen vien (?:nay|do)|truong phong|pho phong|giam doc|lanh dao (?:nay|do|phong|don vi))")
_TARGET = (rf"(?:{_PERSON}|toi|minh|tao|em|phong (?:toi|minh|nay|[a-z]+ [a-z]+)|don vi (?:toi|minh|nay|[a-z]+)|"
           r"ca nhan (?:toi|nay)|tap the (?:nay|phong|don vi))")
_LEVEL = (r"(?:hoan thanh xuat sac|hoan thanh tot|hoan thanh nhiem vu|khong hoan thanh|xuat sac|loai [a-d]\b|"
          r"muc [a-d1-4]\b|loai (?:tot|kha|trung binh|yeu)|chien si thi dua|lao dong tien tien)")
_ASK = r"(?:cham|cho|tinh|uoc tinh|uoc luong|uoc|du doan|du kien|de xuat|goi y|kien nghi|nen cho|danh|cho biet)"
SCORING_REQUEST = _rx(
    rf"\b{_ASK}\b.{{0,15}}\b(?:diem|xep loai|muc xep loai|muc danh gia|loai)\b.{{0,30}}\b{_TARGET}\b",
    rf"\b{_TARGET}\b.{{0,40}}\b(?:duoc|dat|se dat|nen duoc|nen dat|co the dat|xung dang|du dieu kien|co du|xep)\b"
    rf".{{0,25}}(?:\bbao nhieu diem|\bmay diem|\bdiem (?:gi|nao|bao nhieu)|\bloai gi|\bloai nao|\bmuc gi|\bmuc nao|"
    rf"\bhang may|{_LEVEL})",
    r"\b(?:de xuat|goi y|du kien|du doan|kien nghi)\b.{0,10}\b(?:muc )?(?:xep loai|cham diem|cho diem)\b",
    r"\b(?:xep loai|cham diem|cho diem|danh gia xep loai)\b.{0,12}\b(?:giup|ho|thay)\b",
    rf"\b(?:hay|giup|nen)\b.{{0,20}}\b(?:xep|cham)\b.{{0,30}}\b{_TARGET}\b",
    rf"\b(?:cham|cho)\b.{{0,5}}\b(?:{_TARGET}|{_TITLE} [a-z]+)\b.{{0,10}}\b(?:bao nhieu|may|\d+) diem\b",
    rf"\b(?:nhan xet|phe binh|binh xet|nhan dinh|danh gia)\b.{{0,20}}\b(?:ve |cho |cua )?{_PERSON}",
    rf"\b(?:viet|soan|lap|dua ra|cho)\b.{{0,20}}\b(?:ban nhan xet|loi nhan xet|nhan xet|phieu danh gia|y kien danh gia)"
    rf"\b.{{0,40}}\b(?:{_PERSON}|cap duoi|nhan vien|can bo|cong chuc|vien chuc)\b",
    rf"\b(?:nang luc|pham chat|thai do|uu diem|khuyet diem|han che|diem manh|diem yeu) (?:cua|ve)\b.{{0,10}}{_PERSON}",
    r"\b(?:thi dua|khen thuong|ky luat|danh hieu)\b.{0,30}\b(?:ai|nguoi nao|dong chi nao|xung dang|nen trao)\b",
    r"\b(?:ai|nguoi nao|dong chi nao|can bo nao)\b.{0,30}\b(?:dang|xung dang|nen)\b.{0,15}\b(?:khen|ky luat|xep loai|"
    r"xuat sac|thi dua)\b",
    r"\b(?:xep hang|so sanh)\b.{0,30}\b(?:can bo|nhan vien|cong chuc|vien chuc|dong chi|nguoi)\b",
    r"\b(?:rate|score|grade|rank|evaluate|appraise|assess)\b.{0,30}\b(?:employee|staff|officer|person|him|her|"
    r"colleague|subordinate)\b",
)

SCORING_OUTPUT = _rx(
    r"\b(?:de xuat|kien nghi|goi y|nen)\b.{0,30}\b(?:xep loai|muc xep loai|xep vao loai|xep muc)\b",
    rf"\b(?:xep loai|xep vao|dat muc|dat loai|xep muc)\b.{{0,15}}{_LEVEL}",
    r"\b(?:cham|cho|dat|duoc)\s*\d{1,3}(?:[.,]\d+)?\s*(?:/\s*100\s*)?diem\b",
    r"\bdiem (?:danh gia|de xuat|du kien)\b.{0,20}\d",
    rf"\b(?:nhan xet|danh gia)\s*:?\s*{_NAMED}",
)

# ─────────────────────────── Hỏi dữ liệu cá nhân (C2) ───────────────────────────
_C2_THING = (r"(?:so (?:dinh danh|cccd|cmnd|can cuoc|ho chieu|dien thoai|di dong|tai khoan|bao hiem|bhxh)|cccd|cmnd|"
             r"ngay sinh|nam sinh|dia chi (?:nha|thuong tru|tam tru|cu tru|rieng|email|lien he)|que quan|"
             r"email (?:ca nhan|rieng)?|muc luong|bang luong|tien luong|thu nhap|tai san|benh an|suc khoe|ton giao|"
             r"tien an|tien su|ho so (?:ca nhan|dang vien)|ly lich|hoan canh gia dinh)")
C2_REQUEST = _rx(
    rf"\b{_C2_THING}\b.{{0,25}}\b(?:cua|thuoc)\b",
    rf"\b(?:cho (?:toi|minh|tao|em) (?:biet|xin)|liet ke|tra cuu|tim|lay|xuat)\b.{{0,40}}\b{_C2_THING}\b",
    rf"\b(?:ket qua xep loai|ket qua danh gia|diem danh gia|nhan xet|xep loai) (?:cua|ve)\b.{{0,15}}{_PERSON}",
    r"\b(?:national id|phone number|home address|date of birth|salary)\b.{0,20}\bof\b",
)

# ─────────────────────────── Dữ liệu cá nhân trong văn bản ───────────────────────────
PII = [
    ("SO_DINH_DANH", "[SỐ ĐỊNH DANH]",
     re.compile(r"(?<![\w-])(?<!\d[.,])(?:\d[ .-]?){11}\d(?![\w-]|[.,]\d)|(?<![\w-])(?<!\d[.,])\d{9}(?![\w-]|[.,]\d)")),
    ("SO_DIEN_THOAI", "[SỐ ĐIỆN THOẠI]",
     re.compile(r"(?<![\w-])(?:\+?84[ .-]?|0)(?:3|5|7|8|9)\d(?:[ .-]?\d){7}(?![\w-])")),
    ("EMAIL", "[EMAIL]", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")),
    ("NGAY_SINH", "[NGÀY SINH]",
     re.compile(r"(?i)(?:sinh|ngày sinh|ngay sinh|dob|born)\D{0,15}\d{1,2}[/.-]\d{1,2}[/.-](?:19|20)\d{2}")),
]


@dataclass(frozen=True)
class Masked:
    text: str
    kinds: tuple[str, ...]


def mask_pii(text: str) -> Masked:
    """Thay dữ liệu cá nhân bằng nhãn; trả cả loại đã che để ghi nhật ký (không ghi giá trị)."""
    kinds: list[str] = []
    out = text
    for kind, label, rx in PII:
        out, n = rx.subn(label, out)
        if n:
            kinds.append(kind)
    return Masked(out, tuple(kinds))


def is_injection(text: str) -> bool:
    return INJECTION.search(fold(text)) is not None


def asks_scoring(text: str) -> bool:
    return SCORING_REQUEST.search(fold(text)) is not None


def outputs_scoring(text: str) -> bool:
    return SCORING_OUTPUT.search(fold(text)) is not None


def asks_c2(text: str) -> bool:
    return C2_REQUEST.search(fold(text)) is not None
