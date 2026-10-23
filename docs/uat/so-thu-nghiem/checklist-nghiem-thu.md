# Checklist nghiệm thu theo vai trò: kỳ đánh giá thí điểm tại Sở Thử nghiệm

> Nguồn: task QA-16; `wiki/15a-ma-tran-phan-quyen.md` (mô tả vai trò AC-01 đến AC-12); `ke-hoach.md` mục 6 (tiêu chí chấp nhận TC-01 đến TC-08); `kich-ban.md`.

## Cách dùng

Mỗi người đại diện nhận checklist của vai trò mình và đánh dấu sau khi làm xong các kịch bản được giao. Một ô chỉ được đánh dấu "Có" khi chính người đó đã thấy kết quả trên màn hình; nếu không làm được hoặc kết quả sai, đánh "Không" và ghi số phiếu lỗi ở cột ghi chú. Dòng "Góp ý" ở cuối mỗi checklist là để người dùng ghi cảm nhận về sự dễ hiểu của màn hình, câu chữ và số chạm; đây là đầu vào quan trọng cho tổ phát triển, kể cả khi mọi ô khác đều "Có". Trưởng tổ UAT gom các checklist đã ký vào biên bản (`bien-ban-mau.md`).

Vai trò AC-11 (hệ thống bên ngoài) và AC-12 (tác vụ tự động) không có người đại diện; phần việc của chúng được kiểm gián tiếp qua các dòng có ghi "(gián tiếp)".

---

## AC-01. Quản trị nền tảng (Tạ Văn Nền)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Tenant Sở Thử nghiệm được cấp phát đúng cụm, đúng bộ quy định gốc và giao diện mặc định | 01 | | |
| 2 | Kích hoạt tenant thành công, trạng thái hiển thị là đang sử dụng | 01 | | |
| 3 | Không xem được dữ liệu nghiệp vụ (nhiệm vụ, hồ sơ đánh giá) của tenant | 01 | | |
| 4 | Đăng nhập có xác thực bậc hai | 02 | | |

Góp ý: ......................................................................

## AC-02. Quản trị tenant (Hoàng Văn Quản Trị, dự phòng: tài khoản quản trị thứ hai)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Cây đơn vị và phân công mười người đại diện khớp tệp tài khoản | 01 | | |
| 2 | Thay đổi có hiệu lực từ ngày mai không làm đổi phân công hôm nay | 01 | | |
| 3 | Đồng bộ nhân sự chạy xong, báo cáo đối soát không còn sai lệch (gián tiếp: hệ thống nhân sự giả lập) | 01 | | |
| 4 | Hai kỳ (chính và phụ) tạo đúng lịch mốc | 01 | | |
| 5 | Hai người cùng sửa một đơn vị thì người lưu sau được báo dữ liệu đã thay đổi | 01 | | |
| 6 | Lập lệnh khoá kỳ, trình duyệt, khởi chạy được; không tự phê duyệt lệnh của mình | 11 | | |
| 7 | Diễn tập huỷ khoá kỳ phụ: tiến trình dừng, kỳ phụ trở về đang mở | 11 | | |
| 8 | Theo dõi đủ sáu bước khoá kỳ chính tới khi hoàn tất (gián tiếp: tác vụ tự động) | 11 | | |
| 9 | Không mở được danh sách tenant của nền tảng | 01 | | |

Góp ý: ......................................................................

## AC-03. Quản trị nghiệp vụ (Đỗ Thị Chính Sách)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Xem được bộ quy định đánh giá của luồng Đảng và luồng Nhà nước được ghim cho kỳ | 03 | | |
| 2 | Chạy bộ ca mẫu đạt 100% | 03 | | |
| 3 | Sửa ngưỡng làm hỏng ca mẫu thì không gửi duyệt được | 03 | | |
| 4 | Mô phỏng luồng duyệt chỉ ra đúng người duyệt dự kiến | 03 | | |
| 5 | Không tự phê duyệt phát hành của chính mình | 03 | | |

Góp ý: ......................................................................

## AC-04. Lãnh đạo (Lê Văn Lãnh Đạo)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Trả lại kế hoạch quý kèm lý do; phê duyệt lần hai có ký số | 04 | | |
| 2 | Phê duyệt mục tiêu của phòng | 05 | | |
| 3 | Giao việc cho người chủ trì như dữ liệu mẫu | 06 | | |
| 4 | Nhận báo cáo điểm nghẽn từ trưởng phòng | 07 | | |
| 5 | Nghiệm thu minh chứng với đúng số lượng và mức chất lượng, có xác thực bậc hai | 09 | | |
| 6 | Bảng điều hành khớp số liệu kỳ, đi xuống tới minh chứng không quá ba lần chọn | 10 | | |
| 7 | Phê duyệt lệnh khoá kỳ chính và kỳ phụ | 11 | | |
| 8 | Phiếu xếp loại của chính mình đúng mức trong dữ liệu mẫu | 14 | | |
| 9 | Chủ trì hội nghị kiểm điểm và lập biên bản | 16 | | |

Góp ý: ......................................................................

## AC-05. Cán bộ quản lý (Trần Thị Quản Lý)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Lập, trình, sửa và trình lại kế hoạch quý của phòng; xem được lịch sử trình duyệt | 04 | | |
| 2 | Lập mục tiêu của phòng; trả lại và phê duyệt mục tiêu của cán bộ | 05 | | |
| 3 | Giao việc, huỷ nhiệm vụ trùng, từ chối nhiệm vụ tự đề xuất | 06 | | |
| 4 | Giao việc cho người ngoài đơn vị bị từ chối | 06 | | |
| 5 | Xử lý điểm nghẽn và đề nghị gia hạn | 07 | | |
| 6 | Yêu cầu bổ sung minh chứng và nghiệm thu đúng dữ liệu mẫu | 09 | | |
| 7 | Lưu và mở lại bộ lọc trên bảng điều hành của phòng | 10 | | |
| 8 | Phiếu xếp loại của chính mình có giải thích vì sao chỉ đạt mức hoàn thành tốt | 14 | | |

Góp ý: ......................................................................

## AC-06. Cán bộ (Nguyễn Văn Cán Bộ)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Trang "Việc của tôi hôm nay" hiện đúng nhiệm vụ của mình | 02, 06 | | |
| 2 | Lập mục tiêu, sửa theo yêu cầu, gửi lại | 05 | | |
| 3 | Cập nhật tiến độ và check-in kết quả then chốt trong không quá ba chạm | 07 | | |
| 4 | Gửi check-in hai lần chỉ tạo một lần ghi nhận | 07 | | |
| 5 | Nộp minh chứng; tệp thử mã độc bị chặn | 08 | | |
| 6 | Nộp lại minh chứng khi được yêu cầu bổ sung | 09 | | |
| 7 | Điểm tạm tính trên phiếu tự đánh giá là 76,7 | 12 | | |
| 8 | Phiếu xếp loại mức hoàn thành nhiệm vụ, có giải thích dễ hiểu | 14 | | |
| 9 | Gửi khiếu nại, theo dõi và đọc văn bản trả lời | 15 | | |
| 10 | Làm được các việc thường ngày trong chế độ Đảng viên cao tuổi | 18 | | |
| 11 | Chữ lớn, tương phản cao, không có phần nào bị che hoặc tràn khung | 18 | | |

Góp ý (nhất là về chế độ Đảng viên cao tuổi và số chạm): ......................................................................

## AC-07. Người thẩm định (Phạm Thị Thẩm Định)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Trả lại phiếu tự đánh giá kèm lý do | 12 | | |
| 2 | Xem đủ kết quả nhiệm vụ, minh chứng và phiếu tự đánh giá trên một màn hình hồ sơ | 13 | | |
| 3 | Mức đề xuất của hệ thống khớp dữ liệu mẫu cho cả năm hồ sơ | 13 | | |
| 4 | Không thẩm định được hồ sơ của chính mình | 13 | | |
| 5 | Gửi giải trình khi có khiếu nại | 15 | | |
| 6 | Lập, gửi ký và xuất gói hồ sơ số | 16 | | |

Góp ý: ......................................................................

## AC-08. Cấp quyết định (Vũ Văn Quyết Định)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Duyệt phát hành tham số luồng duyệt | 03 | | |
| 2 | Quyết định xếp loại năm hồ sơ và ký số lô | 14 | | |
| 3 | Không được quyết định hồ sơ mình đã thẩm định (kiểm bằng tài khoản thẩm định nếu cần) | 14 | | |
| 4 | Ký số văn bản trả lời khiếu nại | 15 | | |
| 5 | Ký số gói hồ sơ số; chữ ký kiểm tra được là hợp lệ | 16 | | |
| 6 | Duyệt lượt một yêu cầu mở lại kỳ | 17 | | |

Góp ý: ......................................................................

## AC-09. Người duyệt thứ hai (Bùi Thị Duyệt Hai)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Duyệt huỷ khoá kỳ phụ | 11 | | |
| 2 | Duyệt lượt hai yêu cầu mở lại kỳ; yêu cầu chỉ có hiệu lực sau lượt này | 17 | | |
| 3 | Cùng một người duyệt cả hai lượt bị từ chối | 17 | | |
| 4 | Duyệt khi chưa xác thực bậc hai bị yêu cầu xác thực lại | 17 | | |

Góp ý: ......................................................................

## AC-10. Cơ quan kiểm tra (Ngô Văn Kiểm Tra)

| # | Nội dung kiểm | Kịch bản | Có/Không | Ghi chú |
|---|---|---|---|---|
| 1 | Xem tiến trình khoá kỳ ở chế độ chỉ đọc | 11 | | |
| 2 | Tiếp nhận khiếu nại, yêu cầu giải trình, kết luận | 15 | | |
| 3 | Người bị khiếu nại không xử lý được khiếu nại liên quan tới mình | 15 | | |
| 4 | Tra cứu bản ghi sổ cái có thời điểm, người thực hiện, dấu niêm phong | 17 | | |
| 5 | Kiểm tra toàn vẹn cả kỳ báo hợp lệ | 17 | | |
| 6 | Ghi kết luận cho cảnh báo bất thường (nếu có) | 17 | | |
| 7 | Đề nghị mở lại kỳ và xem mô phỏng tác động | 17 | | |
| 8 | Phiếu xếp loại của chính mình mức hoàn thành xuất sắc | 14 | | |

Góp ý: ......................................................................

---

## Checklist cuối kỳ (trưởng tổ UAT)

| Tiêu chí | Nội dung | Có/Không | Bằng chứng |
|---|---|---|---|
| TC-01 | Tenant hoàn thành trọn một kỳ: mọi kịch bản bắt buộc chạy hết, kỳ chính đã khoá, năm hồ sơ có quyết định xếp loại | | Biên bản mục 3 và 4 |
| TC-02 | Bộ ca mẫu của bộ quy định ghim cho kỳ đạt 100% | | Kịch bản 03 |
| TC-03 | Kiểm tra toàn vẹn sổ cái báo hợp lệ cho cả kỳ sau khi khoá và sau khi trả lời khiếu nại | | Kịch bản 17 |
| TC-04 | Điểm, mức xếp loại và câu giải thích của năm hồ sơ khớp `du-lieu-mau/ket-qua-mong-doi.yaml` | | Biên bản mục 4 |
| TC-05 | Không còn lỗi SEV-1, SEV-2 đang mở; mọi lỗi SEV-3, SEV-4 có phiếu và hướng xử lý | | Sổ phiếu lỗi |
| TC-06 | Quyền đúng ma trận: mỗi vai trò chỉ thấy và làm đúng chức năng của mình; thao tác bị chặn có thông báo dễ hiểu | | Mười checklist vai trò |
| TC-07 | Thao tác thường ngày không quá ba chạm; chế độ Đảng viên cao tuổi dùng được trên các màn hình đó | | Kịch bản 07, 18 |
| TC-08 | Văn phong nghiêm túc, đúng thuật ngữ nghiệp vụ, không lộ mã kỹ thuật hay trạng thái thô | | Góp ý trong các checklist |

Tổng hợp góp ý của người dùng về chế độ Đảng viên cao tuổi và số chạm ghi ở mục kết luận của biên bản.
