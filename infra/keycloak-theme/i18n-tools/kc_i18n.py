# -*- coding: utf-8 -*-
"""Công cụ Việt hoá theme Keycloak của Mission (YC-TK-13).

Nguồn tiếng Anh gốc của Keycloak 26.4.7 nằm trong kc-26.4.7-en/. Bản dịch được
quản lý dạng JSON trong vi/<bo>.json (khoá -> chuỗi tiếng Việt), rồi biên dịch
thành ../mission/<loại>/messages/messages_vi.properties (UTF-8). Chuỗi riêng của
Mission (ghi đè bản dịch chuẩn, vd SCR-0001) đặt trong tuy-bien/<loại>.properties.
Thư mục này nằm ngoài theme để Keycloak không quét nhầm khi gắn ../mission.

Lệnh:
  python3 kc_i18n.py split <bo> <kich_thuoc_lo> <thu_muc_ra>   tách khoá chưa dịch thành lô JSON
  python3 kc_i18n.py merge <bo> <tep_lo.json>...               gộp lô đã dịch vào vi/<bo>.json
  python3 kc_i18n.py build                                     sinh messages_vi.properties
  python3 kc_i18n.py check                                     kiểm tra đủ khoá, placeholder, độ dài

Các bộ (<bo>): base-login, base-account, base-email, base-admin, v2-admin, v3-account.
"""
import json
import os
import re
import sys

GOC = os.path.dirname(os.path.abspath(__file__))
THEME = os.path.join(os.path.dirname(GOC), "mission")
NGUON = os.path.join(GOC, "kc-26.4.7-en")
DICH = os.path.join(GOC, "vi")

# Bộ nguồn -> tệp đích trong theme. Theme admin và account kế thừa keycloak.v2/v3
# nhưng Keycloak gộp thông điệp của mọi tầng cha, nên tệp đích gộp cả bộ base.
DICH_DEN = {
    "login": ["base-login"],
    "account": ["base-account", "v3-account"],
    "admin": ["base-admin", "v2-admin"],
    "email": ["base-email"],
}

# Khoá giữ nguyên tiếng Anh vì là tên riêng, mã kỹ thuật hoặc định dạng.
GIU_NGUYEN = re.compile(r"^(locale_[a-zA-Z_-]+)$")

# Placeholder phải giữ nguyên khi dịch: {{ten}} của admin v2, {0} và {0,choice,...}
# của MessageFormat (so theo chỉ số), thẻ HTML (so theo tên thẻ, bỏ qua "1<chữ" là
# nhánh của {0,choice,...}), %s/%d.
PLACEHOLDER = re.compile(r"\{\{[^}]+\}\}|\{\d+|(?<![0-9#])</?[a-zA-Z][a-zA-Z0-9]*|%s|%d")


def doc_properties(duong_dan):
    """Đọc tệp .properties (UTF-8), giữ thứ tự, xử lý dòng nối bằng dấu \\."""
    with open(duong_dan, encoding="utf-8") as f:
        dong = f.read().split("\n")
    ket_qua = {}
    i = 0
    while i < len(dong):
        d = dong[i].rstrip("\r")
        s = d.lstrip()
        if not s or s[0] in "#!":
            i += 1
            continue
        while _noi_dong(d) and i + 1 < len(dong):
            i += 1
            d = d[:-1] + dong[i].rstrip("\r").lstrip()
        m = re.match(r"^\s*((?:\\.|[^=:\s])+)\s*[=:]?\s?(.*)$", d)
        if m:
            ket_qua[_bo_escape(m.group(1))] = _bo_escape(m.group(2))
        i += 1
    return ket_qua


def _noi_dong(d):
    n = len(d) - len(d.rstrip("\\"))
    return n % 2 == 1


def _bo_escape(s):
    ra = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and re.match(r"[0-9a-fA-F]{4}", s[i + 2:i + 6]):
                ra.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            ra.append({"n": "\n", "t": "\t", "r": "\r", "f": "\f"}.get(n, n))
            i += 2
            continue
        ra.append(c)
        i += 1
    return "".join(ra)


def _escape(s, la_khoa=False):
    s = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    if la_khoa:
        s = s.replace("=", "\\=").replace(":", "\\:").replace(" ", "\\ ")
    elif s.startswith(" "):
        s = "\\" + s
    return s


def nguon(bo):
    return doc_properties(os.path.join(NGUON, bo + "_en.properties"))


def doc_dich(bo):
    p = os.path.join(DICH, bo + ".json")
    if not os.path.exists(p):
        return {}
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def ghi_dich(bo, d):
    os.makedirs(DICH, exist_ok=True)
    en = nguon(bo)
    co_thu_tu = {k: d[k] for k in en if k in d}
    with open(os.path.join(DICH, bo + ".json"), "w", encoding="utf-8") as f:
        json.dump(co_thu_tu, f, ensure_ascii=False, indent=1)
        f.write("\n")


def lenh_split(bo, kich_thuoc, thu_muc):
    en, vi = nguon(bo), doc_dich(bo)
    con = [k for k in en if k not in vi]
    os.makedirs(thu_muc, exist_ok=True)
    for so, dau in enumerate(range(0, len(con), kich_thuoc), 1):
        lo = {k: en[k] for k in con[dau:dau + kich_thuoc]}
        p = os.path.join(thu_muc, "%s-%02d.json" % (bo, so))
        with open(p, "w", encoding="utf-8") as f:
            json.dump(lo, f, ensure_ascii=False, indent=1)
        print(p, len(lo))


def lenh_merge(bo, tep):
    en, vi = nguon(bo), doc_dich(bo)
    for t in tep:
        with open(t, encoding="utf-8") as f:
            lo = json.load(f)
        for k, v in lo.items():
            if k in en and isinstance(v, str):
                vi[k] = v
    ghi_dich(bo, vi)
    print(bo, "%d/%d khoá đã dịch" % (len(vi), len(en)))


def placeholder(s):
    return sorted(PLACEHOLDER.findall(s))


def lenh_check():
    loi = 0
    for bo in sorted({b for v in DICH_DEN.values() for b in v}):
        en, vi = nguon(bo), doc_dich(bo)
        thieu = [k for k in en if k not in vi]
        if thieu:
            loi += 1
            print("THIẾU %s: %d khoá, vd %s" % (bo, len(thieu), thieu[:5]))
        for k, v in vi.items():
            if k not in en:
                continue
            if placeholder(en[k]) != placeholder(v):
                loi += 1
                print("PLACEHOLDER %s.%s\n  en: %s\n  vi: %s" % (bo, k, en[k], v))
            if not v.strip() and en[k].strip():
                loi += 1
                print("RỖNG %s.%s" % (bo, k))
            # Nhãn ngắn (nút, cột, menu) không được dài quá gấp đôi bản tiếng Anh.
            if len(en[k]) <= 16 and len(v) > max(2 * len(en[k]), 24):
                print("CẢNH BÁO ĐỘ DÀI %s.%s: '%s' -> '%s'" % (bo, k, en[k], v))
    print("Kết quả:", "ĐẠT" if loi == 0 else "%d lỗi" % loi)
    return loi


def lenh_build():
    for loai, bos in DICH_DEN.items():
        gop = {}
        for bo in bos:
            gop.update(doc_dich(bo))
        tuy_bien = os.path.join(GOC, "tuy-bien", loai + ".properties")
        if os.path.exists(tuy_bien):
            gop.update(doc_properties(tuy_bien))
        gop["locale_vi"] = "Tiếng Việt"
        gop["locale_en"] = "English"
        ra = os.path.join(THEME, loai, "messages", "messages_vi.properties")
        os.makedirs(os.path.dirname(ra), exist_ok=True)
        with open(ra, "w", encoding="utf-8") as f:
            f.write("# Sinh tự động bởi i18n-tools/kc_i18n.py (YC-TK-13), không sửa tay.\n")
            f.write("# Bản dịch nằm ở i18n-tools/vi/*.json; chuỗi riêng của Mission ở i18n-tools/tuy-bien/<loại>.properties.\n")
            for k in sorted(gop):
                f.write("%s=%s\n" % (_escape(k, True), _escape(gop[k])))
        print(ra, len(gop))


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        sys.exit(2)
    if a[0] == "split":
        lenh_split(a[1], int(a[2]), a[3])
    elif a[0] == "merge":
        lenh_merge(a[1], a[2:])
    elif a[0] == "build":
        lenh_build()
    elif a[0] == "check":
        sys.exit(1 if lenh_check() else 0)
    else:
        print(__doc__)
        sys.exit(2)
