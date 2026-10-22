"""Sinh danh mục điểm chuẩn TWD-DM-25 của hồ sơ TWĐ (DL-16, giả lập) từ tệp mô phỏng Phụ lục 01 của UEB.

Nguồn dòng danh mục là cột "Loại sản phẩm theo danh mục điểm chuẩn" (AL), "Điểm chuẩn" (AN), "Hệ số mức độ khó" (AO)
và "Nhóm nhiệm vụ" (W) của trang "Cây toàn trình", cột I, J, K của trang "Cây thường xuyên"; các dòng tách 22a, 34b…
đối chiếu với trang "Danh mục điểm – tách dòng" (tổng điểm dòng con bằng điểm dòng gốc). Chỉ những dòng của bản dự
thảo số 25 mà cây mô phỏng dùng tới được nạp; phần còn lại của danh mục chưa có trong bộ tài liệu bàn giao.

Cách chạy (từ gốc repo; tệp nguồn không nằm trong repo):
    python -I data/seed/twd/sinh_danh_muc_twd.py <đường dẫn Phụ lục 01 .xlsx>
Kết quả: data/seed/twd/danh-muc/work_catalog.csv và catalog_item.csv. Cần openpyxl."""
import csv, os, re, sys
from decimal import Decimal
import openpyxl

DICH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "danh-muc")
MA_DM = "TWD-DM-25"
# Bảng mức khó của gói twd/diem-cong-viec (he_so.muc_do_kho): lõi chỉ lưu mức, giá trị nằm trong gói.
MUC_KHO = {Decimal("0.8"): 1, Decimal("1"): 2, Decimal("1.25"): 3, Decimal("1.6"): 4, Decimal("2"): 5}
NHOM = {"Trọng tâm, then chốt": "THEN_CHOT", "Thường xuyên": "THUONG_XUYEN"}
# Khung DC-02 (tham-so/khung-twd.yaml của hồ sơ): đơn vị chỉnh điểm chuẩn trong ±20% so với dòng của khối.
CHINH_MIN, CHINH_MAX = "-0.20", "0.20"
CHAT_LUONG = "Theo thang mức chất lượng 1,00 / 0,75 / 0,50 / 0,25 / 0 do người xác nhận chấm"
SP_DIEM = r"\((.+); ([\d,]+) điểm\)"
RE_TACH = re.compile(r"^Dòng (\d+)([a-z]) – tách từ dòng \1 “(.+?)\s*” " + SP_DIEM + r"; .*?: (.+) \(([\d,]+) điểm\)$")
RE_DONG = re.compile(r"^Dòng (\d+): (.+) " + SP_DIEM + r"$")
RE_TX = re.compile(r"^Dòng (\d+): (.+)$")


def so(x):
    return Decimal(str(x).replace(",", ".")).normalize()


def loi(m):
    sys.exit("LỖI: " + m)


def them(muc, ma, dong):
    """Nhóm là của từng việc, không của dòng danh mục: một dòng dùng ở cả hai nhóm thì nhóm mặc định lấy nhóm
    được dùng nhiều hơn (bằng nhau thì then chốt). Các thuộc tính còn lại phải trùng nhau."""
    nhom, sp = dong.pop("nhom"), dong.pop("sp")
    cu = muc.get(ma)
    if cu and {k: v for k, v in cu.items() if k not in ("dem", "sp")} != dong:
        loi(f"dòng {ma} có hai nội dung khác nhau trong tệp nguồn:\n  {cu}\n  {dong}")
    d = muc.setdefault(ma, dict(dong, dem={}, sp=[]))
    if sp not in d["sp"]: d["sp"].append(sp)
    dem = d["dem"]
    dem[nhom] = dem.get(nhom, 0) + 1


def doc(duong_dan):
    wb = openpyxl.load_workbook(duong_dan, read_only=True, data_only=True)
    muc, tach = {}, {}
    for r in wb["Danh mục điểm – tách dòng"].iter_rows(min_row=4, values_only=True):
        if isinstance(r[0], int) and r[4]:
            tach[str(r[4])] = (r[0], so(r[3]), so(r[6]), str(r[5]).strip())
    for r in wb["Cây toàn trình"].iter_rows(min_row=6, values_only=True):
        al, g = r[37], r[6]
        if not al or g not in ("Công việc (CV)", "Công việc con (CVc)") or not str(al).startswith("Dòng"):
            continue
        al, diem, kho, nhom = str(al).strip(), so(r[39]), so(r[40]), NHOM[r[22]]
        if (m := RE_TACH.match(al)):
            goc, chu, ten_goc, sp_goc, d_goc, loai_sp, d = m.groups()
            ma = goc + chu
            if ma not in tach: loi(f"dòng tách {ma} không có ở trang tách dòng")
            t = tach[ma]
            if (t[0], t[1], t[2], t[3]) != (int(goc), so(d_goc), so(d), loai_sp.strip()):
                loi(f"dòng tách {ma} lệch trang tách dòng: {t} / {(goc, d_goc, d, loai_sp)}")
            ten, sp, ghi = f"{ten_goc.strip()}: {loai_sp.strip()}", loai_sp.strip(), \
                f"tách từ dòng {goc} ({sp_goc}; {d_goc} điểm), điểm tách giả định (Đặc tả 3.8)"
        elif (m := RE_DONG.match(al)):
            ma, ten, sp, d = m.groups()
            ghi = ""
        else:
            loi(f"không đọc được dòng danh mục: {al[:120]}")
        if so(d) != diem: loi(f"dòng {ma}: điểm trong tên {d} khác cột điểm chuẩn {diem}")
        them(muc, ma, dict(ma=ma, ten=ten.strip(), sp=sp.strip(), kho=MUC_KHO[kho], diem=diem, nhom=nhom, ghi=ghi))
    for r in wb["Cây thường xuyên"].iter_rows(min_row=5, values_only=True):
        if r[1] != "Công việc" or not r[8] or not str(r[8]).startswith("Dòng"):
            continue
        m = RE_TX.match(str(r[8]).strip())
        if not m: loi(f"không đọc được dòng thường xuyên: {r[8]}")
        ma, ten = m.groups()
        them(muc, ma, dict(ma=ma, ten=ten.strip(), sp=str(r[9]).strip(), kho=MUC_KHO[so(r[11])], diem=so(r[10]),
                           nhom="THUONG_XUYEN", ghi=""))
    # Mỗi dòng gốc đã tách: các dòng con cây dùng tới không được vượt điểm dòng gốc (Đặc tả 3.8).
    for goc in {t[0] for t in tach.values()}:
        con = [t for t in tach.values() if t[0] == goc]
        if sum(t[2] for t in con) != con[0][1]: loi(f"dòng {goc}: tổng điểm dòng con khác điểm dòng gốc")
    return muc


def khoa(ma):
    m = re.match(r"(\d+)([a-z]?)", ma)
    return int(m.group(1)), m.group(2)


def ghi(muc):
    os.makedirs(DICH, exist_ok=True)
    with open(os.path.join(DICH, "work_catalog.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["code", "name", "scope", "source", "status", "version_no", "ghi_chu"])
        w.writerow([MA_DM, "Danh mục công việc tạm thời của Ban (dự thảo số 25), phần định hướng mới", "KHOI",
                    "TU_TAO", "HIEU_LUC", 25, "giả lập theo UEB; chỉ gồm các dòng cây mô phỏng dùng tới"])
    with open(os.path.join(DICH, "catalog_item.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["catalog_code", "code", "name", "product_name", "unit", "difficulty", "base_score",
                    "quality_standard", "evidence_requirement", "default_group", "standard_days",
                    "score_adjust_min", "score_adjust_max", "ghi_chu"])
        for ma in sorted(muc, key=khoa):
            d = muc[ma]
            d["nhom"] = max(sorted(d["dem"]), key=lambda k: (d["dem"][k], k == "THEN_CHOT"))
            w.writerow([MA_DM, f"TWD25-{ma}", d["ten"], "; ".join(d["sp"]), "sản phẩm", d["kho"], f"{d['diem']:.3f}",
                        CHAT_LUONG, "Sản phẩm đã được người xác nhận duyệt", d["nhom"], "",
                        CHINH_MIN, CHINH_MAX, "; ".join(x for x in ("giả lập", d["ghi"]) if x)])
    return len(muc)


if __name__ == "__main__":
    if len(sys.argv) != 2: sys.exit(__doc__)
    n = ghi(doc(sys.argv[1]))
    print(f"Đã ghi {n} dòng danh mục {MA_DM} vào {os.path.relpath(DICH)}")
