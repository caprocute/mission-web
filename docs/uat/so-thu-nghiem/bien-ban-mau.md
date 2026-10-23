# Mẫu biên bản kiểm thử chấp nhận một kỳ đánh giá

> Nguồn: task QA-16; `ke-hoach.md` mục 6, 7, 9; TL-04 bảng mức sự cố SEV-1 đến SEV-4. Mẫu dùng chung cho buổi diễn tập (tenant hư cấu Sở Thử nghiệm) và buổi nghiệm thu với tenant thật. Khi dùng, sao tệp này thành `docs/uat/<mã tenant>/bien-ban.md` (hoặc `bien-ban-dien-tap-<ngày>.md`) rồi điền; không sửa tệp mẫu.

**Loại biên bản:** ☐ Biên bản diễn tập ☐ Biên bản nghiệm thu

**Tenant:** ........................ **Kỳ đánh giá:** ........................ **Từ ngày** ........ **đến ngày** ........

**Phiên bản phần mềm** (mã bản phát hành web và máy chủ): ........................

**Bộ quy định đánh giá đã ghim cho kỳ** (tên và phiên bản, luồng Đảng và luồng Nhà nước): ........................

## 1. Thành phần tham gia

| Vai trò | Họ tên | Đơn vị | Có mặt các ngày |
|---|---|---|---|
| Trưởng tổ UAT | | | |
| Thư ký | | | |
| Kỹ sư trực | | | |
| Quản trị nền tảng | | | |
| Quản trị tenant | | | |
| Quản trị nghiệp vụ | | | |
| Lãnh đạo | | | |
| Cán bộ quản lý | | | |
| Cán bộ | | | |
| Người thẩm định | | | |
| Cấp quyết định | | | |
| Người duyệt thứ hai | | | |
| Cơ quan kiểm tra | | | |
| Đại diện tenant (chỉ biên bản nghiệm thu) | | | |

## 2. Kiểm điều kiện bắt đầu

| Điều kiện (theo `ke-hoach.md` mục 5) | Đạt/Không | Bằng chứng (đường dẫn, ảnh chụp, mã lượt chạy) |
|---|---|---|
| Tenant đã qua quy trình đưa vào sử dụng | | |
| Bộ kiểm thử hành trình đầu cuối chạy xanh trên môi trường đích | | |
| Bộ ca mẫu của bộ quy định ghim cho kỳ đạt 100% | | |
| Bảng tính tay khớp bộ tính tham chiếu (`kiem_ket_qua_mong_doi.py` báo khớp đủ ca) | | |
| Dữ liệu thử không chứa dữ liệu cá nhân thật | | |
| Người đại diện có tài khoản, ứng dụng tạo mã OTP và chứng thư ký số thử | | |

## 3. Kết quả từng kịch bản

Kết quả ghi một trong bốn giá trị: Đạt, Đạt có ghi chú, Không đạt, Chưa kiểm (chỉ cho kịch bản tuỳ chọn).

| Kịch bản | Nội dung | Ngày chạy | Người thực hiện | Kết quả | Số phiếu lỗi |
|---|---|---|---|---|---|
| 01 | Chuẩn bị tenant, đơn vị và kỳ | | | | |
| 02 | Đăng nhập, chọn vai trò, xác thực bậc hai | | | | |
| 03 | Bộ quy định đánh giá và luồng duyệt | | | | |
| 04 | Kế hoạch quý | | | | |
| 05 | Mục tiêu và kết quả then chốt | | | | |
| 06 | Giao việc và nhận việc | | | | |
| 07 | Tiến độ, check-in, điểm nghẽn, gia hạn | | | | |
| 08 | Nộp minh chứng | | | | |
| 09 | Nghiệm thu minh chứng | | | | |
| 10 | Bảng điều hành | | | | |
| 11 | Khoá kỳ (A: diễn tập huỷ; B: kỳ chính) | | | | |
| 12 | Tự đánh giá | | | | |
| 13 | Thẩm định | | | | |
| 14 | Quyết định xếp loại hàng loạt | | | | |
| 15 | Khiếu nại | | | | |
| 16 | Hội nghị kiểm điểm và gói hồ sơ số | | | | |
| 17 | Kiểm tra toàn vẹn, cảnh báo, mở lại kỳ | | | | |
| 18 | Chế độ Đảng viên cao tuổi | | | | |
| Tuỳ chọn | Trợ lý AI / Di động / Giao diện riêng của tenant | | | | |

Kịch bản không có hành trình kiểm thử tự động tương ứng (xem phụ lục của `kich-ban.md`) được ghi chú ở đây: ........................

## 4. Đối chiếu điểm và mức xếp loại

Cột "Mong đợi" chép từ `du-lieu-mau/ket-qua-mong-doi.yaml` (hoặc bảng tính tay của tenant thật); cột "Trên hệ thống" chép từ phiếu xếp loại đã ký số. Lệch bất kỳ ô nào về điểm hoặc mức là lỗi tối thiểu SEV-2.

| Người được đánh giá | Điểm mong đợi | Điểm trên hệ thống | Mức mong đợi | Mức trên hệ thống | Câu giải thích đúng căn cứ | Khớp |
|---|---|---|---|---|---|---|
| Ngô Văn Kiểm Tra | 100 | | Hoàn thành xuất sắc nhiệm vụ | | | |
| Trần Thị Quản Lý | 98,8 | | Hoàn thành tốt nhiệm vụ (không đạt điều kiện tỷ lệ vượt kế hoạch) | | | |
| Phạm Thị Thẩm Định | 91,7 | | Hoàn thành tốt nhiệm vụ | | | |
| Nguyễn Văn Cán Bộ | 76,7 | | Hoàn thành nhiệm vụ | | | |
| Lê Văn Lãnh Đạo | 100 | | Hoàn thành xuất sắc nhiệm vụ | | | |

Kết quả chạy bộ ca mẫu trước khoá kỳ: ........ / ........ ca đạt; sau khoá kỳ: ........ / ........ ca đạt.

## 5. Kiểm tra toàn vẹn sổ cái

| Lượt kiểm | Thời điểm | Số bản ghi đã kiểm | Kết quả | Người chạy |
|---|---|---|---|---|
| Sau khoá kỳ | | | | |
| Sau trả lời khiếu nại | | | | |
| Sau mở lại kỳ (nếu chạy) | | | | |

## 6. Sổ phiếu lỗi

| Số phiếu | Kịch bản | Mô tả ngắn | Mức (SEV-1..4) | Trạng thái | Hướng xử lý |
|---|---|---|---|---|---|
| | | | | | |

Tổng hợp: SEV-1 ...., SEV-2 ...., SEV-3 ...., SEV-4 ....; số lỗi còn mở khi kết thúc: ....

## 7. Ghi nhận vận hành (tham khảo, không phải tiêu chí đạt)

Thời gian chạy tiến trình khoá kỳ: ........ Cảnh báo vận hành phát sinh: ........ Nhận xét về độ trễ các thao tác chính so với SLO của cell: ........

## 8. Góp ý của người dùng

Tổng hợp từ mười checklist vai trò, tách riêng phần về số chạm và chế độ Đảng viên cao tuổi:

........................................................................

## 9. Kết luận

| Tiêu chí | Đạt/Không | Ghi chú |
|---|---|---|
| TC-01 Hoàn thành trọn một kỳ | | |
| TC-02 Bộ ca mẫu đạt 100% | | |
| TC-03 Sổ cái hợp lệ cả kỳ | | |
| TC-04 Điểm và mức khớp bảng tính tay | | |
| TC-05 Không còn SEV-1, SEV-2 mở | | |
| TC-06 Quyền đúng ma trận | | |
| TC-07 Ba chạm và chế độ cao tuổi | | |
| TC-08 Nội dung giao diện | | |

**Kết luận chung:** ☐ Đạt ☐ Đạt có điều kiện (còn lỗi SEV-3 trở xuống có hướng xử lý) ☐ Không đạt

Phần hệ thống ngoài chạy với bản giả lập: ........................

## 10. Chữ ký

| Trưởng tổ UAT | Thư ký | Đại diện tổ phát triển | Đại diện tenant (chỉ biên bản nghiệm thu) |
|---|---|---|---|
| | | | |

Bản có chữ ký số lưu ngoài repo; tệp trong repo chỉ giữ nội dung đã điền.
