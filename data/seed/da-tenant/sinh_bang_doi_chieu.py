#!/usr/bin/env python3
"""Sinh bảng đối chiếu vai trò × tính năng × dữ liệu thử (YC-PT-25) cho bộ dữ liệu đa tenant.

Đầu vào: ma trận phân quyền `docs/phan-quyen/ma-tran.json` (danh sách chức năng theo màn hình và vai trò AC)
và nguồn dữ liệu `du_lieu.py`. Với mỗi chức năng web, bảng ghi vai trò được dùng, tình trạng dữ liệu thử
(có sẵn, thử trực tiếp, trống có lý do, ngoài phạm vi) và tài khoản gợi ý. Tình trạng do người làm dữ liệu
khai trong `TINH_TRANG` dưới đây; mọi ô trống phải có lý do và cách mở khoá.

    python3 -I data/seed/da-tenant/sinh_bang_doi_chieu.py          # ghi docs/kich-ban-trai-nghiem/BANG-DOI-CHIEU-VAI-TRO.md
    python3 -I data/seed/da-tenant/sinh_bang_doi_chieu.py --kiem   # chỉ kiểm tệp đã commit khớp (mã thoát 1 nếu lệch)
"""

import importlib.util
import json
import sys
from collections import Counter, OrderedDict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_spec = importlib.util.spec_from_file_location("du_lieu", str(HERE / "du_lieu.py"))
D = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(D)
MA_TRAN = ROOT / "docs/phan-quyen/ma-tran.json"
DICH = ROOT / "docs/kich-ban-trai-nghiem/BANG-DOI-CHIEU-VAI-TRO.md"

CO, TRUC_TIEP, TRONG, NGOAI = "Có", "Thử trực tiếp", "Trống", "Ngoài phạm vi"

# Lý do dùng chung cho các ô trống, kèm cách mở khoá.
LY_DO = {
    "ho_so": "Hồ sơ đánh giá chỉ sinh khi saga khoá kỳ chạy với Policy Pack đã phát hành; mở khoá bằng kịch bản "
             "KB-X4 (phát hành gói, khởi chạy khoá kỳ quý trước).",
    "minh_chung": "Minh chứng là tệp thật phải qua quét vi-rút của dịch vụ minh chứng, không nạp sẵn; "
                  "nộp tệp bất kỳ ở kịch bản KB-X5.",
    "ai": "Dịch vụ trợ lý AI chưa bật trên môi trường dev; bỏ qua.",
    "e2ee": "Khoá mã hoá đầu cuối gắn với thiết bị người dùng, không giả lập được; thử trực tiếp khi có chứng thư.",
    "ngoai": "Phụ thuộc hệ thống ngoài (văn phòng điện tử, CSDL cán bộ công chức viên chức) chỉ có bản giả lập; "
             "không có thông điệp đến.",
    "mo_lai": "Chỉ có sau khi kỳ đã khoá và có kết quả xếp loại (KB-X4).",
    "luong": "Tenant thử chưa gắn mẫu luồng duyệt; tiến trình theo mẫu chỉ xuất hiện khi gắn mẫu ở màn hình cấu "
             "hình luồng duyệt.",
    "canh_bao": "Cảnh báo do luật phát hiện sinh ra từ dữ liệu thật (check-in nhảy vọt, OKR chép nhau); dữ liệu nạp "
                "đã có check-in nhảy hơn 50% nên có thể xuất hiện, chưa xác nhận trên dev.",
    "dang_ky": "Đăng ký nhiệm vụ cần mục danh mục gắn vị trí việc làm; dữ liệu tổ chức thử chưa có vị trí việc làm.",
    "ui_pack": "UI Pack chỉ nạp được khi máy nạp có Python 3.10 trở lên; máy chủ dev bỏ qua, quản trị tự soạn.",
    "lich_su_tc": "Cây tổ chức thử chỉ có bản hiện hành, chưa có lịch sử thay đổi; thử thay đổi tổ chức để tạo lịch sử.",
    "nen_tang": "Vai trò quản trị nền tảng, mẫu luồng nội bộ nền tảng: tenant thử tạo bằng SQL, không có tài khoản AC-01.",
    "di_dong": "Ứng dụng di động đã bỏ khỏi phạm vi (quyết định 24/10/2026).",
}

# Tình trạng theo chức năng (khoá `SCR-xxxx.hanh-dong`) hoặc theo màn hình (khoá `SCR-xxxx`).
# Giá trị: (tình trạng, mô tả dữ liệu hoặc mã lý do trong LY_DO).
TINH_TRANG = OrderedDict([
    ("SCR-0001", (TRUC_TIEP, "51 tài khoản, mật khẩu và mã OTP thử dùng chung (TAI-KHOAN.md)")),
    ("SCR-0001.chon-phan-cong", (CO, "Nhiều tài khoản có hơn một vai trò (ví dụ bí thư vừa là lãnh đạo vừa là cấp quyết định)")),
    ("SCR-0001.khoa-thiet-bi", (TRONG, "e2ee")),
    ("SCR-0002", (CO, "Việc được giao, việc đang làm, mục tiêu chờ duyệt, đề nghị chờ quyết định, thông báo nhắc việc")),
    ("SCR-0002.cap-nhat-nhanh", (CO, "Mỗi tenant có 3–4 nhiệm vụ đang thực hiện của cán bộ")),
    ("SCR-0101", (NGOAI, "nen_tang")),
    ("SCR-0102", (NGOAI, "nen_tang")),
    ("SCR-0103.xem", (CO, "Cây 12/12/7 đơn vị, ba cấp, có cấp xã và chi bộ thôn (khối Đảng)")),
    ("SCR-0103.nhap-excel", (TRUC_TIEP, "Cây đã có; nhập Excel để thêm đơn vị mới")),
    ("SCR-0103.thay-doi", (TRUC_TIEP, "lich_su_tc")),
    ("SCR-0103.khoa-phuc-hoi", (TRONG, "e2ee")),
    ("SCR-0103.nhat-ky-phuc-hoi", (TRONG, "e2ee")),
    ("SCR-0104", (TRONG, "ngoai")),
    ("SCR-0105.xem", (CO, "Vai trò gốc của 51 tài khoản cộng vai trò cấp thêm có thời hạn (đã duyệt và đang chờ duyệt)")),
    ("SCR-0105.cap", (CO, "Mỗi tenant có một đề nghị cấp vai trò đang chờ duyệt để xem mẫu; cấp thêm cho người khác")),
    ("SCR-0105.duyet", (CO, "Đề nghị cấp quyền chờ duyệt: khối Đảng (uỷ ban kiểm tra), khối chính quyền (chuyên viên tổng hợp)")),
    ("SCR-0105.thu-hoi", (TRUC_TIEP, "Thu hồi vai trò cấp thêm đã có hiệu lực")),
    ("SCR-0106", (CO, "Hồ sơ quyền của người được cấp thêm vai trò, người được uỷ quyền")),
    ("SCR-0107.xem", (CO, "Uỷ quyền duyệt thay và check-in thay đang hiệu lực (2 khối Đảng, 2 chính quyền, 1 doanh nghiệp)")),
    ("SCR-0107.tao", (CO, "Lãnh đạo khai thêm uỷ quyền nghiệm thu thay cho người khác")),
    ("SCR-0107.thu-hoi", (TRUC_TIEP, "Thu hồi uỷ quyền đang hiệu lực")),
    ("SCR-0201", (CO, "10/9/8 bộ mục tiêu: cây tỉnh → sở, ban → phòng → cá nhân; mục tiêu cá nhân; đủ trạng thái")),
    ("SCR-0202.soan-don-vi", (CO, "Mục tiêu nháp của đơn vị chờ soạn tiếp; mục tiêu bị trả lại có ý kiến để sửa")),
    ("SCR-0202.soan-ca-nhan", (CO, "Mục tiêu cá nhân nháp (chuyên viên tổng hợp, chuyên viên thanh tra, nhân viên nhân sự)")),
    ("SCR-0202.ai-soat", (TRONG, "ai")),
    ("SCR-0202.trinh", (CO, "Mục tiêu nháp và mục tiêu bị trả lại để trình lại")),
    ("SCR-0203", (CO, "Mỗi tenant một bộ mục tiêu đang chờ duyệt; lịch sử bộ đã trả lại có ý kiến")),
    ("SCR-0203.duyet-lo", (CO, "Trình thêm các bộ nháp để có nhiều bộ chờ duyệt rồi duyệt hàng loạt")),
    ("SCR-0301.check-in", (CO, "Mục tiêu đã duyệt có kết quả then chốt chưa check-in và đã check-in")),
    ("SCR-0301.lich-su", (CO, "63 lần check-in, nhiều kết quả có 2–3 lần, có lần tự tin thấp kèm vướng mắc")),
    ("SCR-0301.giao-check-in-thay", (CO, "Uỷ quyền check-in thay đã có ở khối Đảng và chính quyền")),
    ("SCR-0302", (NGOAI, "di_dong")),
    ("SCR-0401.xem", (CO, "Danh mục công việc 6/7/7 mục theo nhóm, có điểm chuẩn")),
    ("SCR-0401.sua", (TRUC_TIEP, "Thêm mục, nhân bản phiên bản trên danh mục đã có")),
    ("SCR-0402.xem", (CO, "Kế hoạch quý: nháp, chờ duyệt, trả lại, đã duyệt (mỗi tenant hai kế hoạch)")),
    ("SCR-0402.lap", (CO, "Kế hoạch nháp của Tỉnh uỷ, UBND tỉnh, Công ty để lập tiếp; kế hoạch bị trả lại để sửa")),
    ("SCR-0402.gui-duyet", (CO, "Kế hoạch nháp đã có dòng để gửi duyệt")),
    ("SCR-0402.dieu-chinh", (CO, "Kế hoạch Sở Tài chính đã duyệt (chỉ gồm dòng danh mục) để tạo phiên bản điều chỉnh")),
    ("SCR-0403.so-sanh", (TRUC_TIEP, "Có sau khi tạo điều chỉnh kế hoạch (phiên bản 2)")),
    ("SCR-0403.tra-lai", (CO, "Kế hoạch chờ duyệt của Ban Tổ chức Tỉnh uỷ, Xí nghiệp Cấp nước số 1")),
    ("SCR-0403.duyet-ky", (CO, "Kế hoạch chờ duyệt; ký số cần xác thực bước lên và MySign giả lập trên dev. Kế hoạch có "
                            "dòng ngoài danh mục chỉ duyệt được sau bước định điểm (BR-04-05), máy chủ dev chưa có bước này")),
    ("SCR-0501.soan", (CO, "Mỗi tenant một nhiệm vụ nháp chưa giao")),
    ("SCR-0501.giao", (CO, "Nhiệm vụ nháp để giao; nhiệm vụ đã giao chưa nhận để giao lại")),
    ("SCR-0501.giao-dong-loat", (TRUC_TIEP, "Giao cho nhiều phòng, xã trong cây tổ chức")),
    ("SCR-0501.trung", (CO, "Nhiệm vụ cùng nhóm, cùng sản phẩm trong kỳ để so sánh gần giống")),
    ("SCR-0501.khoa-nguoi-nhan", (TRONG, "e2ee")),
    ("SCR-0502", (TRONG, "ngoai")),
    ("SCR-0503.xem", (CO, "Việc của tôi đủ trạng thái: đã giao, đang làm, chờ nghiệm thu, hoàn thành, tạm dừng, huỷ")),
    ("SCR-0503.nhan-viec", (CO, "Nhiệm vụ đã giao chưa nhận (2–3 mỗi tenant)")),
    ("SCR-0503.tien-do", (CO, "Nhiệm vụ đang làm ở 20–60%")),
    ("SCR-0503.dang-ky", (TRONG, "dang_ky")),
    ("SCR-0504", (NGOAI, "di_dong")),
    ("SCR-0601.xem", (CO, "Chi tiết có nhật ký tiến độ, lịch sử trạng thái, điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại")),
    ("SCR-0601.tien-do", (CO, "Nhiệm vụ đang làm")),
    ("SCR-0601.bao-nghen", (CO, "Mỗi tenant một điểm nghẽn mẫu (mở, đang xử lý hoặc đã giải quyết); báo thêm trên việc khác")),
    ("SCR-0601.xu-ly-nghen", (CO, "Điểm nghẽn đang mở hoặc đang xử lý chờ người giao đóng")),
    ("SCR-0601.duyet-de-nghi", (CO, "Đề nghị gia hạn, đổi số lượng đang chờ quyết định; lịch sử đề nghị đã duyệt và từ chối")),
    ("SCR-0601.nop-sp", (TRUC_TIEP, "minh_chung")),
    ("SCR-0602.gui", (CO, "Nhiệm vụ đang làm chưa có đề nghị để gửi; đề nghị mẫu đủ bốn loại (gia hạn, số lượng, tạm dừng, huỷ)")),
    ("SCR-0602.rut", (CO, "Đề nghị đang chờ duyệt của chính người chủ trì")),
    ("SCR-0701.soan", (TRUC_TIEP, "minh_chung")),
    ("SCR-0701.nop", (TRUC_TIEP, "minh_chung")),
    ("SCR-0701.xem", (TRONG, "minh_chung")),
    ("SCR-0702", (NGOAI, "di_dong")),
    ("SCR-0703", (TRONG, "minh_chung")),
    ("SCR-0801.xem", (CO, "Quý trước có lệnh khoá đã duyệt chưa khởi chạy; quý hiện hành đang mở")),
    ("SCR-0801.tao-ky", (TRUC_TIEP, "Hai kỳ đã có; tạo kỳ quý sau hoặc kỳ năm")),
    ("SCR-0801.lap-lenh", (CO, "Lệnh khoá quý trước đã lập; lập lệnh cho kỳ khác")),
    ("SCR-0801.duyet-lenh", (CO, "Lệnh khoá quý trước đã được lãnh đạo duyệt (xem lịch sử); duyệt lệnh mới nếu lập thêm")),
    ("SCR-0801.khoi-chay", (TRUC_TIEP, "ho_so")),
    ("SCR-0801.dieu-khien", (TRUC_TIEP, "ho_so")),
    ("SCR-0801.de-nghi-huy", (TRUC_TIEP, "ho_so")),
    ("SCR-0801.duyet-huy", (TRUC_TIEP, "ho_so")),
    ("SCR-0901", (TRONG, "ho_so")),
    ("SCR-0902", (TRONG, "ho_so")),
    ("SCR-0903", (TRONG, "ho_so")),
    ("SCR-0904", (TRONG, "ho_so")),
    ("SCR-1001", (TRONG, "ho_so")),
    ("SCR-1002", (TRONG, "ho_so")),
    ("SCR-1101", (TRONG, "ho_so")),
    ("SCR-1102", (TRONG, "ho_so")),
    ("SCR-1201", (TRONG, "mo_lai")),
    ("SCR-1202", (TRONG, "mo_lai")),
    ("SCR-1301", (TRONG, "mo_lai")),
    ("SCR-1302", (CO, "Sổ cái ghi mọi thao tác đã nạp (duyệt mục tiêu, giao việc, quyết định đề nghị, cấp quyền)")),
    ("SCR-1401.xem", (TRONG, "canh_bao")),
    ("SCR-1401.xu-ly", (TRONG, "canh_bao")),
    ("SCR-1401.ket-luan", (TRONG, "canh_bao")),
    ("SCR-1401.nguong", (TRUC_TIEP, "Cấu hình ngưỡng của tenant")),
    ("SCR-1501", (CO, "Policy Pack mẫu bản nháp của từng tenant (khối Đảng: Quy định mới; chính quyền, doanh nghiệp: NĐ 335)")),
    ("SCR-1502", (CO, "Ba bảng quyết định xếp loại nhập từ gói mẫu")),
    ("SCR-1503.chay", (CO, "Bộ ca mẫu đã duyệt của gói (63 ca khối Đảng, bộ ca NĐ 335 cho hai tenant còn lại)")),
    ("SCR-1503.xem-duyet", (CO, "Có sau khi quản trị nghiệp vụ gửi phê duyệt phát hành (KB-X4)")),
    ("SCR-1503.ky-duyet", (CO, "Có sau khi gửi phê duyệt; ký số cần xác thực bước lên trên dev")),
    ("SCR-1504", (TRONG, "luong")),
    ("SCR-1505", (NGOAI, "nen_tang")),
    ("SCR-1506", (TRONG, "luong")),
    ("SCR-1601.xem", (CO, "Bảng điều hành tổng hợp từ 34 nhiệm vụ, 27 bộ mục tiêu, 63 check-in; có kỳ trước để so sánh")),
    ("SCR-1601.nhac", (CO, "Đã có một lần nhắc việc mỗi tenant; nhắc thêm trên việc chậm khác")),
    ("SCR-1601.giao-lai", (CO, "Đã có một lần giao lại mỗi tenant; giao lại việc đã giao chưa nhận")),
    ("SCR-1601.nguon-ai", (TRONG, "ai")),
    ("SCR-1602", (TRUC_TIEP, "Dựng dashboard từ dữ liệu nhiệm vụ, mục tiêu đã có")),
    ("SCR-1603", (TRONG, "minh_chung")),
    ("SCR-1701", (TRONG, "ai")),
    ("SCR-1801", (TRUC_TIEP, "ui_pack")),
    ("SCR-1802", (TRUC_TIEP, "Dựng biểu mẫu động mới cho tenant")),
    ("SCR-1803", (NGOAI, "di_dong")),
])

NHAN_KH = {"NHAP": "nháp", "CHO_DUYET": "chờ duyệt", "TRA_LAI": "trả lại", "DA_DUYET": "đã duyệt"}

VAI_TRO_NGAN = {
    "AC-01": "QT nền tảng", "AC-02": "QT tenant", "AC-03": "QT nghiệp vụ", "AC-04": "Lãnh đạo", "AC-05": "Quản lý",
    "AC-06": "Cán bộ", "AC-07": "Thẩm định", "AC-08": "Cấp quyết định", "AC-09": "Duyệt thứ hai",
    "AC-10": "Kiểm tra", "AC-11": "Hệ thống ngoài", "AC-12": "Trợ lý AI",
}


def tinh_trang(c):
    tt = TINH_TRANG.get(c["key"]) or TINH_TRANG.get(c["scr"])
    if not tt:
        raise SystemExit("Chưa khai tình trạng cho " + c["key"])
    trang_thai, mo_ta = tt
    if mo_ta in LY_DO:
        mo_ta = LY_DO[mo_ta]
    return trang_thai, mo_ta


def vai_tro_dung(c):
    """Các AC có quyền làm (X, T, D) hoặc chỉ xem (R) với chức năng."""
    lam, xem = [], []
    for ac, q in c["quyen"].items():
        if ac in ("AC-01", "AC-11", "AC-12"):
            continue
        if q[0] in "XTD":
            lam.append(ac)
        elif q[0] == "R":
            xem.append(ac)
    return lam, xem


def tai_khoan_theo_vai_tro(roles):
    """Một tài khoản mỗi tenant cho một vai trò: ưu tiên người đang có dữ liệu đúng vai (chủ trì việc với cán bộ,
    người giao việc với quản lý, người duyệt mục tiêu với lãnh đạo) và người có ít vai trò kiêm nhiệm nhất."""
    out = []
    for t in D.TENANT:
        ma = t["ma"]
        uu_tien = {
            "member": {n["chu"] for n in D.NHIEM_VU[ma]},
            "manager": {n["giao"] for n in D.NHIEM_VU[ma]},
            "leader": {o["duyet"] for o in D.OKR[ma]},
            "policy_author": {t["goi_chinh_sach"]["soan"]},
        }
        for r in roles:
            ung = [c for c in t["can_bo"] if r in c["vai_tro"]]
            if ung:
                ung.sort(key=lambda c: (c["tk"] not in uu_tien.get(r, set()), len(c["vai_tro"])))
                out.append(ung[0]["tk"])
                break
    return out


def goi_y_tai_khoan(c):
    lam, xem = vai_tro_dung(c)
    ac = lam or xem
    roles = [v["role"] for v in VAI_TRO if v["ac"] in ac]
    return tai_khoan_theo_vai_tro(roles)


def ten_vai_tro(acs):
    return ", ".join(VAI_TRO_NGAN[a] for a in acs) if acs else "—"


def thong_ke_nguon():
    kq = []
    for t in D.TENANT:
        ma = t["ma"]
        nv = Counter(D.dich_nhiem_vu(n) for n in D.NHIEM_VU[ma])
        mt = Counter(o.get("dich", "DA_DUYET") for o in D.OKR[ma])
        ci = sum(len(x) if isinstance(x, (list, tuple)) else 1
                 for o in D.OKR[ma] for k in o["kr"] for x in [k[8]] if x is not None)
        ci_truoc = sum(len(x) if isinstance(x, (list, tuple)) else 1
                       for o in D.KY_TRUOC[ma]["okr"] for k in o["kr"] for x in [k[8]] if x is not None)
        kq.append(OrderedDict([
            ("Tenant", t["ten"]), ("Tài khoản", len(t["can_bo"])), ("Đơn vị", len(t["don_vi"])),
            ("Bộ mục tiêu kỳ này (đã duyệt/chờ duyệt/trả lại/nháp)",
             "%d (%d/%d/%d/%d)" % (len(D.OKR[ma]), mt["DA_DUYET"], mt["CHO_DUYET"], mt["TRA_LAI"], mt["NHAP"])),
            ("Check-in kỳ này", ci),
            ("Bộ mục tiêu và check-in quý trước", "%d, %d" % (len(D.KY_TRUOC[ma]["okr"]), ci_truoc)),
            ("Nhiệm vụ (nháp/đã giao/đang làm/chờ nghiệm thu/hoàn thành/tạm dừng/huỷ)",
             "%d (%d/%d/%d/%d/%d/%d/%d)" % (len(D.NHIEM_VU[ma]), nv["NHAP"], nv["DA_GIAO"], nv["DANG_THUC_HIEN"],
                                           nv["CHO_NGHIEM_THU"], nv["HOAN_THANH"], nv["TAM_DUNG"], nv["HUY"])),
            ("Điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại",
             "%d, %d, %d, %d" % (sum(1 for n in D.NHIEM_VU[ma] if n.get("nghen")),
                                 sum(1 for n in D.NHIEM_VU[ma] if n.get("de_nghi")),
                                 sum(1 for n in D.NHIEM_VU[ma] if n.get("nhac")),
                                 sum(1 for n in D.NHIEM_VU[ma] if n.get("giao_lai")))),
            ("Kế hoạch quý", ", ".join(NHAN_KH[k["dich"]] for k in D.KE_HOACH[ma])),
            ("Uỷ quyền, phân quyền thêm", "%d, %d" % (len(D.UY_QUYEN[ma]), len(D.PHAN_QUYEN[ma]))),
            ("Policy Pack mẫu", t["goi_chinh_sach"]["goi"].split("/", 2)[-1]),
        ]))
    return kq


def sinh():
    m = json.load(open(str(MA_TRAN), encoding="utf-8"))
    global VAI_TRO
    VAI_TRO = m["vai_tro"]
    cn = [c for c in m["chuc_nang"]]
    w = []
    w.append("> Nguồn: sinh tự động bởi `data/seed/da-tenant/sinh_bang_doi_chieu.py` từ `docs/phan-quyen/ma-tran.json` "
             "và `data/seed/da-tenant/du_lieu.py`; không sửa tay. Quy tắc: YC-PT-25 (sổ tay yêu cầu).\n")
    w.append("# Bảng đối chiếu vai trò × tính năng × dữ liệu thử\n")
    w.append("Bảng này trả lời câu hỏi: với mỗi tính năng trong ma trận phân quyền, vai trò nào được dùng, bộ dữ liệu "
             "thử đa tenant đã có gì để trải nghiệm, và nếu chưa có thì vì sao và mở khoá bằng cách nào. Tài khoản gợi ý "
             "lấy theo thứ tự tenant khối Đảng (`tu.`), khối chính quyền (`cq.`), doanh nghiệp (`dn.`); mật khẩu và mã "
             "OTP thử ở `data/seed/da-tenant/TAI-KHOAN.md`. Kịch bản từng bước ở `KICH-BAN-DA-TENANT.md`.\n")
    w.append("## 1. Dữ liệu đã nạp theo tenant\n")
    tk = thong_ke_nguon()
    w.append("| Hạng mục | " + " | ".join(r["Tenant"] for r in tk) + " |")
    w.append("|---|" + "---|" * len(tk))
    for k in list(tk[0].keys())[1:]:
        w.append("| %s | %s |" % (k, " | ".join(str(r[k]) for r in tk)))
    w.append("")
    w.append("Số liệu nhiệm vụ, mục tiêu tính theo trạng thái đích trong nguồn; bộ nạp qua API (`seed-da-tenant-api.py`) "
             "in bảng thống kê thực tế (mới/đã có) sau mỗi lần chạy, kèm danh sách bước lỗi nếu có.\n")

    w.append("## 2. Tài khoản theo vai trò\n")
    w.append("| Vai trò | Khối Đảng | Khối chính quyền | Doanh nghiệp |")
    w.append("|---|---|---|---|")
    for v in VAI_TRO:
        if v["ac"] in ("AC-01", "AC-11", "AC-12"):
            continue
        cells = []
        for t in D.TENANT:
            tks = [c["tk"] for c in t["can_bo"] if v["role"] in c["vai_tro"]]
            cells.append(", ".join("`%s`" % x for x in tks) if tks else "—")
        w.append("| %s (%s) | %s |" % (v["ten"], VAI_TRO_NGAN[v["ac"]], " | ".join(cells)))
    w.append("")
    w.append("Quản trị nền tảng (AC-01), hệ thống ngoài (AC-11) và trợ lý AI (AC-12) không có tài khoản trong bộ dữ liệu: "
             "tenant thử được tạo bằng SQL, hệ thống ngoài chỉ có bản giả lập, dịch vụ trợ lý AI chưa bật trên dev.\n")

    w.append("## 3. Tính năng, vai trò và dữ liệu thử\n")
    w.append("Cột tình trạng: **Có** là dữ liệu nạp sẵn đúng cho tính năng; **Thử trực tiếp** là tính năng tạo dữ liệu mới "
             "trên nền đã có, không cần nạp trước; **Trống** là chưa có dữ liệu, kèm lý do và cách mở khoá; **Ngoài phạm vi** "
             "là tính năng của nền tảng, di động hoặc hệ thống ngoài.\n")
    dem = Counter()
    scr_hien = None
    for c in cn:
        if c["scr"] != scr_hien:
            scr_hien = c["scr"]
            w.append("")
            w.append("### %s %s\n" % (scr_hien, m["man_hinh"][scr_hien]))
            w.append("| Chức năng | Vai trò làm | Vai trò xem | Tình trạng | Dữ liệu thử hoặc lý do | Tài khoản gợi ý |")
            w.append("|---|---|---|---|---|---|")
        tt, mo_ta = tinh_trang(c)
        if not c["web"]:
            tt, mo_ta = NGOAI, LY_DO["di_dong"]
        dem[tt] += 1
        lam, xem = vai_tro_dung(c)
        tks = goi_y_tai_khoan(c) if tt in (CO, TRUC_TIEP) else []
        w.append("| %s | %s | %s | %s | %s | %s |" % (
            c["ten"].replace("|", "/"), ten_vai_tro(lam), ten_vai_tro(xem), tt, mo_ta,
            ", ".join("`%s`" % x for x in tks) if tks else "—"))

    w.append("")
    w.append("## 4. Tổng hợp\n")
    w.append("| Tình trạng | Số chức năng |")
    w.append("|---|---|")
    for k in (CO, TRUC_TIEP, TRONG, NGOAI):
        w.append("| %s | %d |" % (k, dem[k]))
    w.append("")
    w.append("Các nhóm còn trống và cách mở khoá:\n")
    nhom = OrderedDict()
    for c in cn:
        tt, mo_ta = tinh_trang(c)
        if tt == TRONG:
            nhom.setdefault(mo_ta, []).append(c["scr"])
    for ly_do, scrs in nhom.items():
        w.append("- %s Màn hình: %s." % (ly_do, ", ".join(sorted(set(scrs)))))
    w.append("")
    w.append("## Liên quan\n")
    w.append("- [Kịch bản trải nghiệm đa tenant](KICH-BAN-DA-TENANT.md)")
    w.append("- [Ma trận phân quyền](../../wiki/15a-ma-tran-phan-quyen.md)")
    w.append("- [Bộ dữ liệu thử đa tenant](../../data/seed/da-tenant/README.md)")
    return "\n".join(w) + "\n"


def main():
    nd = sinh()
    if "--kiem" in sys.argv[1:]:
        if not DICH.exists() or DICH.read_text(encoding="utf-8") != nd:
            print("Bảng đối chiếu lệch nguồn, chạy lại sinh_bang_doi_chieu.py", file=sys.stderr)
            return 1
        print("Bảng đối chiếu khớp nguồn.")
        return 0
    DICH.write_text(nd, encoding="utf-8")
    print("Đã sinh " + str(DICH.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
