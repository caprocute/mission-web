"""Kiểm tra tĩnh dữ liệu khởi tạo của hồ sơ TWĐ (DL-16): danh mục TWD-DM-25, danh mục dùng chung, bộ quy tắc
twd/diem-nghen, bộ tham số luồng twd-tham-quyen và bản kê hồ sơ twd/ban-to-chuc-ueb.

Đây là kiểm tra tạm cho tới khi lõi có bộ nạp hồ sơ (CORE-05-12) và job quy tắc cảnh báo (CORE-04-13) đánh giá CEL
bằng vt-cel; tập lệnh chỉ kiểm biến CEL đã khai báo, không thông dịch biểu thức.
Cách chạy (từ gốc repo):  python -I data/seed/twd/kiem_seed_twd.py .      Cần PyYAML."""
import csv, os, re, sys
from decimal import Decimal
import yaml

NGUYEN_NHAN_HOP_DONG = {"NGUON_LUC", "PHOI_HOP", "THU_TUC", "KHACH_QUAN", "KHAC"}   # tasks.yaml BlockerCauseCategory
MUC_DO = {"CAO", "TRUNG_BINH", "THAP"}                                               # tasks.yaml ImpactLevel
NHOM = {"THUONG_XUYEN", "THEN_CHOT"}                                                 # tasks.yaml TaskGroup
PHAM_VI = {"QUOC_GIA", "KHOI", "TINH", "DON_VI", "VI_TRI"}                           # tasks.yaml CatalogScope
KW = {"true", "false", "null", "in", "size", "int", "double", "string"}
CHUOI = re.compile(r"'[^']*'|\"[^\"]*\"")


def bien_cel(bt):
    """Các đường dẫn gốc.thuộc_tính[.con] trong biểu thức CEL, bỏ chuỗi và từ khoá."""
    e = CHUOI.sub("''", bt)
    return {m.group(0) for m in re.finditer(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", e)} - KW


def kiem(goc):
    loi = []
    sd = os.path.join(goc, "data/seed/twd")
    ho_so_dir = os.path.join(goc, "data/policy-pack/twd/ban-to-chuc-ueb")
    doc = lambda p: yaml.safe_load(open(p, encoding="utf-8"))

    # 1. Danh mục TWD-DM-25
    dm = list(csv.DictReader(open(os.path.join(sd, "danh-muc/work_catalog.csv"), encoding="utf-8")))
    if [d["code"] for d in dm] != ["TWD-DM-25"] or dm[0]["scope"] not in PHAM_VI or dm[0]["scope"] != "KHOI":
        loi.append("work_catalog.csv phải có đúng một danh mục TWD-DM-25 ở phạm vi KHOI")
    muc = list(csv.DictReader(open(os.path.join(sd, "danh-muc/catalog_item.csv"), encoding="utf-8")))
    ma = [m["code"] for m in muc]
    if len(ma) != len(set(ma)): loi.append("catalog_item.csv có mã trùng (R43)")
    for m in muc:
        w = f"catalog_item {m['code']}"
        if m["catalog_code"] != "TWD-DM-25": loi.append(f"{w}: catalog_code sai")
        if not 1 <= int(m["difficulty"]) <= 5: loi.append(f"{w}: mức khó ngoài 1..5")
        if not Decimal(1) <= Decimal(m["base_score"]) <= Decimal(100): loi.append(f"{w}: điểm chuẩn ngoài 1..100 (R43)")
        if m["default_group"] not in NHOM: loi.append(f"{w}: nhóm {m['default_group']} không thuộc TaskGroup")
        if not (-1 <= Decimal(m["score_adjust_min"]) <= 0 <= Decimal(m["score_adjust_max"]) <= 10):
            loi.append(f"{w}: khung chỉnh điểm ngoài miền của hợp đồng")
        if "giả lập" not in m["ghi_chu"]: loi.append(f"{w}: thiếu đánh dấu giả lập")

    # 2. Danh mục dùng chung
    dc = doc(os.path.join(sd, "danh-muc/danh-muc-dung-chung.yaml"))
    if [t["ma"] for t in dc["truc"]["muc"]] != [f"TR{i}" for i in range(1, 7)]: loi.append("trục phải là TR1..TR6")
    if [t["ma"] for t in dc["chi_tieu_kt_xh"]["muc"]] != [f"CT{i}" for i in range(1, 6)]: loi.append("chỉ tiêu phải là CT1..CT5")
    bac = [m["bac"] for m in dc["dieu_kien_cong_nhan_moc"]["muc"]]
    if bac != sorted(bac): loi.append("điều kiện công nhận mốc phải xếp theo bậc tăng dần")
    tcc, so = dc["tieu_chi_chung"], dc["tieu_chi_chung"]["so_tieu_chi"]
    if len(tcc["ca_nhan"]) != so["LANH_DAO"] or \
            sum(not t.get("chi_lanh_dao") for t in tcc["ca_nhan"]) != so["KHONG_GIU_CHUC_VU"] or \
            len(tcc["tap_the"]) != so["TAP_THE"]:
        loi.append("bộ tiêu chí chung không khớp 12/10/11")
    nn = {m["ma"]: m for m in dc["nguyen_nhan_diem_nghen"]["muc"]}
    for m in nn.values():
        if m["ma_hop_dong"] not in NGUYEN_NHAN_HOP_DONG: loi.append(f"nguyên nhân {m['ma']}: ma_hop_dong ngoài BlockerCauseCategory")
        if m["nhom"] not in {"KHACH_QUAN", "CHU_QUAN", "THEO_TUNG_BAN_GHI"}: loi.append(f"nguyên nhân {m['ma']}: nhóm sai")

    # 3. Bộ quy tắc twd/diem-nghen
    dn = doc(os.path.join(sd, "diem-nghen.yaml"))
    md = dn["metadata"]
    if md.get("gia_lap") is not True or not md.get("ghi_chu_gia_lap"): loi.append("diem-nghen: thiếu đánh dấu giả lập")
    truong = set(dn["types"]["MucTheoDoi"])
    ts = dn["params"]
    duoc = {"muc." + t for t in truong} | {"tham_so." + k for k in ts} | \
        {f"tham_so.{k}.{c}" for k, v in ts.items() if isinstance(v.get("default"), dict) for c in v["default"]}
    def kiem_bt(w, bt):
        for b in bien_cel(str(bt)):
            if b not in duoc: loi.append(f"{w}: biến '{b}' chưa khai báo")
    qt = dn["quy_tac"]
    if [q["so"] for q in qt] != list(range(1, 10)): loi.append("diem-nghen: phải có đúng chín quy tắc 1..9 theo thứ tự")
    if len({q["ma"] for q in qt}) != len(qt): loi.append("diem-nghen: mã quy tắc trùng")
    for q in qt:
        w = f"diem-nghen {q['ma']}"
        if len(q["ma"]) > 64: loi.append(f"{w}: mã dài quá ruleCode (64)")
        if not q["sinh_ban_ghi"]:
            if "dieu_kien" in q: loi.append(f"{w}: quy tắc không sinh bản ghi không được có điều kiện sinh")
            continue
        kiem_bt(w + " dieu_kien", q["dieu_kien"])
        kiem_bt(w + " bat", q["bat"])
        bg = q["ban_ghi"]
        kiem_bt(w + " muc_do", bg["muc_do"])
        for g in re.findall(r"(?:^|[?:(]\s*)'([A-Z_]+)'", bg["muc_do"].strip()):
            if g not in MUC_DO: loi.append(f"{w}: mức {g} không thuộc ImpactLevel")
        if bg["nguyen_nhan"] not in nn: loi.append(f"{w}: nguyên nhân {bg['nguyen_nhan']} không có trong danh mục")
        if bg["loai"] not in {"DIEM_NGHEN", "RUI_RO"}: loi.append(f"{w}: loại bản ghi sai")
        for k in ("nhac_tu_ngay", "nhac_lai_moi"):
            if k in q: kiem_bt(f"{w} {k}", q[k])
        for b in re.findall(r"\{(\w+)\}", q.get("mau_noi_dung", "")):
            if b not in truong and b != "chuc_danh_nguoi_nhan": loi.append(f"{w}: biến mẫu {{{b}}} không có trong ngữ cảnh")
    bat_kb = set(ts["bat"]["default"])
    if bat_kb != {f"Q{q['so']}" for q in qt if q["sinh_ban_ghi"]}:
        loi.append("diem-nghen: cờ bật tắt phải phủ đúng các quy tắc sinh bản ghi (B.6 dòng 2)")

    # 4. Tham số luồng twd-tham-quyen
    tq = doc(os.path.join(sd, "twd-tham-quyen.yaml"))
    han = tq["mau_luong"]["danh_gia_quy"]["han_buoc_ngay_lam_viec"]
    if list(han.values()) != [2, 2, 1, 1]: loi.append("twd-tham-quyen: hạn từng bước đợt quý phải là 2/2/1/1")
    if [d["tt"] for d in tq["mau_luong"]["duyet_de_nghi"]["tham_quyen"]] != list(range(1, 9)):
        loi.append("twd-tham-quyen: bảng thẩm quyền duyệt thay đổi phải có dòng 1..8 của Phụ lục B.5")

    # 5. Bản kê hồ sơ và khung DC-02
    hs = doc(os.path.join(ho_so_dir, "ho-so.yaml"))
    if hs.get("kind") != "CustomerProfile" or hs["metadata"].get("gia_lap") is not True:
        loi.append("ho-so.yaml: phải là CustomerProfile đánh dấu giả lập")
    tp = hs["thanh_phan"]
    if tp["bo_quy_tac_canh_bao"]["id"] != md["id"] or tp["bo_quy_tac_canh_bao"]["version"] != md["version"]:
        loi.append("ho-so.yaml: bộ quy tắc cảnh báo không khớp diem-nghen.yaml")
    if tp["tham_so_luong"]["bo"] != tq["metadata"]["id"]: loi.append("ho-so.yaml: bộ tham số luồng không khớp")
    if tp["danh_muc"]["code"] != "TWD-DM-25" or str(tp["danh_muc"]["version"]) != dm[0]["version_no"]:
        loi.append("ho-so.yaml: danh mục không khớp work_catalog.csv")
    pp = doc(os.path.join(goc, "data/policy-pack", tp["policy_pack"]["id"], "policy.yaml"))
    if pp["metadata"]["version"] != tp["policy_pack"]["version"]: loi.append("ho-so.yaml: phiên bản gói không khớp policy.yaml")
    for p in tp.values():
        if isinstance(p, dict) and "nguon" in p and not os.path.exists(os.path.join(goc, p["nguon"])):
            loi.append(f"ho-so.yaml: không thấy {p['nguon']}")
    kh = doc(os.path.join(ho_so_dir, hs["khung_tham_so"]))
    for it in kh["items"]:
        k = it["paramKey"]
        if it["tightenDirection"] not in {"TANG", "GIAM", "KHONG"}: loi.append(f"khung {k}: tightenDirection sai")
        if not set(it["allowedScopes"]) <= {"KHOI", "TINH", "DON_VI"}: loi.append(f"khung {k}: allowedScopes sai")
        if k.startswith("tham_so."):
            phan = k.split(".")[1:]   # khoá params của gói có thể chứa dấu chấm ("he_so.muc_do_kho")
            if not any(".".join(phan[:i]) in pp["params"] for i in range(1, len(phan) + 1)):
                loi.append(f"khung {k}: gói không có tham số này")
        elif k.startswith("diem_nghen.") and k.split(".")[1] not in ts:
            loi.append(f"khung {k}: bộ quy tắc không có tham số này")
    adj = {(m["score_adjust_min"], m["score_adjust_max"]) for m in muc}
    dmk = next(it for it in kh["items"] if it["paramKey"] == "danh_muc.diem_chuan")
    if adj != {(f"{dmk['minValue']:.2f}", f"{dmk['maxValue']:.2f}")}:
        loi.append("khung danh_muc.diem_chuan không khớp score_adjust_min/max của danh mục")

    # 6. Phủ ca UEB: mọi ca G1–G13, 32.4 tình huống 1–25, CTT1–CTT16, CTB1–CTB30 phải nằm ở bộ ca vàng (tính điểm)
    # hoặc ở danh mục ca nghiệm thu (luồng, chặn, thông báo) của data/golden/twd.
    vang = doc(os.path.join(goc, "data/policy-pack/twd/diem-cong-viec/tests/bo-ca-mau-twd.yaml"))["ca"]
    nt = doc(os.path.join(goc, "data/golden/twd/ca-nghiem-thu.yaml"))["ca"]
    co = {re.match(r"[A-Z]+\d+", c["id"]).group(0) for c in vang if re.match(r"(G|TH|CTT|CTB)\d+", c["id"])}
    co |= {"TH1", "TH2", "TH3"} if any(c["id"].startswith("LO-TH") or c["id"] in ("LO-A", "LO-B") for c in vang) else set()
    co |= {"CTT11"} if any(c["id"] == "G1" for c in vang) else set()
    co |= {"G13"} if any(c["id"] == "LO-G13" for c in vang) else set()
    for c in nt:
        if c["ma"] in co: loi.append(f"ca nghiệm thu {c['ma']} trùng với bộ ca vàng")
        if not c.get("ngoai_pham_vi") and not c.get("task"): loi.append(f"ca nghiệm thu {c['ma']}: thiếu task lõi")
        co.add(c["ma"])
    can = {f"G{i}" for i in range(1, 14)} | {f"TH{i}" for i in range(1, 26)} | \
        {f"CTT{i}" for i in range(1, 17)} | {f"CTB{i}" for i in range(1, 31)}
    for m in sorted(can - co): loi.append(f"ca UEB {m} chưa có ở bộ ca vàng lẫn danh mục ca nghiệm thu")
    return loi, len(muc), len(qt)


if __name__ == "__main__":
    loi, n_muc, n_qt = kiem(sys.argv[1] if len(sys.argv) > 1 else ".")
    for m in loi: print("LỖI:", m)
    print(f"Danh mục TWD-DM-25: {n_muc} dòng; bộ quy tắc twd/diem-nghen: {n_qt} quy tắc")
    print("KẾT QUẢ:", "ĐẠT" if not loi else f"KHÔNG ĐẠT, {len(loi)} lỗi")
    sys.exit(1 if loi else 0)
