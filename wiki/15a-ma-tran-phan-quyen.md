> Nguồn: Baseline mục 10 (AC), mục 11 (QT, SCR), mục 13 (module API); TL-01 chương 16 "Uỷ quyền tập trung bằng ABAC", Phụ lục C (endpoint kèm quyền); TL-02 chương "Tác nhân và phân quyền", bảng thông tin (Tác nhân, Quyền) và bảng chức năng của từng SCR từ SCR-0001 đến SCR-1803, các BR có nội dung tách vai trò, bốn mắt, step-up; TL-03 `config.role_binding`, `config.period_lock_order`, mục phân loại dữ liệu; TL-04 SEC-13 đến SEC-18, SEC-22, SEC-58. Dữ liệu có cấu trúc: `docs/phan-quyen/ma-tran.json`.

# Ma trận phân quyền

Trang này là bảng tra cứu vai trò nào được làm chức năng nào trên từng màn hình web, kèm phạm vi dữ liệu áp dụng. Nguyên tắc, mô hình vai trò và cách hiện thực ở từng tầng nằm ở [trang 15](15-phan-quyen.md); trang này chỉ chứa ma trận và các ràng buộc gắn với chức năng. Người làm màn hình dùng các dòng của SCR mình phụ trách để quyết định ẩn hay hiện menu, nút và thao tác, đồng thời biết thao tác nào phải chờ máy chủ trả 403 hoặc 401 step-up để hiển thị đúng thông báo MSG.

## Cách đọc và cách sửa

Ma trận chính có mỗi hàng là một chức năng của một màn hình, gom theo quy trình QT, và mỗi cột là một tác nhân từ AC-01 đến AC-12. Mỗi ô ghi một ký hiệu quyền và một phạm vi dữ liệu, ví dụ `D/Cd` nghĩa là được duyệt trên đơn vị mình và cây đơn vị cấp dưới. Ô có hậu tố `*` là ô suy luận: TL-02 không ghi trực tiếp vai trò đó cho chức năng đó, người lập ma trận suy ra từ bảng chức năng, BR hoặc phạm vi mặc định của vai trò; lý do suy luận ghi ở bảng ghi chú cuối ma trận chính và trong cột "Ghi chú" của tệp Excel. Ô suy luận cần người phụ trách xác nhận trước khi dùng làm căn cứ kiểm thử chấp nhận.

Ma trận chỉ nói vai trò nào có thể có quyền; quyền thật còn phụ thuộc thuộc tính của đối tượng (trạng thái, người khởi tạo, người được giao, kỳ đã khoá hay chưa) và do máy chủ quyết định. Vì vậy một ô `D/Gn` cho người nghiệm thu không có nghĩa là mọi `manager` thấy nút "Xác nhận nghiệm thu"; nút chỉ hiện khi người đó là người nghiệm thu được chỉ định của nhiệm vụ và khác người nộp, theo đúng cột "Ràng buộc".

Các bảng giữa cặp dấu `BEGIN GEN` và `END GEN` được sinh tự động từ `docs/phan-quyen/ma-tran.json` bằng `python3 docs/phan-quyen/gen_matrix.py`, lệnh này đồng thời sinh tệp `docs/phan-quyen/ma-tran-phan-quyen.xlsx`. Muốn đổi một ô, sửa tệp JSON rồi chạy lại lệnh; không sửa tay bảng sinh ra, vì `gen_matrix.py --check` sẽ báo lệch. Phần văn xuôi ngoài các khối sinh tự động thì sửa trực tiếp như mọi trang wiki.

## Ký hiệu và phạm vi

<!-- BEGIN GEN:chu-giai -->
| Ký hiệu | Nghĩa | Mô tả |
|---|---|---|
| `X` | Toàn quyền | Tạo, sửa, xoá, cấu hình, điều khiển đối tượng trong phạm vi. |
| `T` | Tạo, thực hiện | Khởi tạo, soạn, cập nhật, nộp, gửi (vai trò người khởi tạo hoặc người thực hiện). |
| `D` | Duyệt, quyết định | Phê duyệt, trả lại, nghiệm thu, thẩm định, quyết định, ký số (vai trò người xác nhận). |
| `R` | Xem | Đọc, lọc, tải, xuất trong phạm vi; không ghi nghiệp vụ. |
| `A` | Trợ lý AI | Đọc dưới danh tính người dùng đang dùng trợ lý và trả gợi ý; không ghi (AC-12). |
| `H` | Hệ thống ngoài | Tham gia qua tích hợp (client credentials hoặc IdP môi giới), không có giao diện (AC-11). |
| `-` | Không có quyền | Ẩn chức năng; máy chủ từ chối nếu gọi trực tiếp. |

| Phạm vi | Nghĩa | Mô tả |
|---|---|---|
| `B` | Bản thân | Đối tượng của chính người dùng: nhiệm vụ mình chủ trì hoặc phối hợp, KR mình sở hữu, hồ sơ của mình. |
| `Gn` | Được giao, được chỉ định | Đối tượng mà người dùng được gán vai trò trên chính đối tượng đó: người nghiệm thu được chỉ định, người xử lý khiếu nại, người duyệt do OPA chỉ định, thành viên hội nghị. |
| `Đv` | Đơn vị | Đơn vị của phân công đang chọn (`config.role_binding.scope = DON_VI`). |
| `Cd` | Đơn vị và cây cấp dưới | Đơn vị và toàn bộ cây đơn vị cấp dưới tại thời điểm hiện hành (`DON_VI_VA_CAP_DUOI`). |
| `Tn` | Toàn tenant | Mọi đơn vị trong tenant, vẫn chịu RLS theo `tenant_id`. |
| `N` | Nền tảng | Vùng điều phối toàn cục, nhiều tenant (chỉ AC-01). |

Dữ liệu hiện có 179 chức năng trên 53 màn hình, trong đó 175 chức năng thuộc 49 màn hình web; 87 ô được đánh dấu suy luận (`*`).
<!-- END GEN:chu-giai -->

## Ma trận chính: chức năng theo màn hình

Các dòng có tiền tố "[Di động]" là màn hình của ứng dụng Flutter (SVC-18), ghi để đối chiếu vì dùng chung API và quyền; phân hệ web không làm các màn hình này. Ứng dụng di động đã bỏ khỏi phạm vi ngày 24/10/2026, wiki 14; các dòng này giữ trong ma trận vì đã sinh vào gói OPA và chính sách thao tác API mà web vẫn dùng. Khoá trong ngoặc là phần sau dấu chấm của khoá chức năng đầy đủ dạng `SCR-qqnn.<khoá>`, dùng làm tên quyền ở phía giao diện (WEB-054).

<!-- BEGIN GEN:ma-tran-chinh -->
Dòng tiêu đề cột: AC-01 `platform_admin`, AC-02 `tenant_admin`, AC-03 `policy_author`, AC-04 `leader`, AC-05 `manager`, AC-06 `member`, AC-07 `appraiser`, AC-08 `decider`, AC-09 `second_approver`, AC-10 `inspector`, AC-11 `(client credentials)`, AC-12 `ai_readonly`.

#### QT-00. Đăng nhập, chọn đơn vị và vai trò

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0001 | Đăng nhập bằng tài khoản cơ quan, VNeID hoặc chứng thư số, xác thực MFA `dang-nhap` | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | H | - | BR-00-01, BR-00-05, BR-00-06; SEC-13, SEC-14, SEC-17 |
| SCR-0001 | Chọn phân công (đơn vị, vai trò) sau đăng nhập `chon-phan-cong` | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | - | - | BR-00-01 (vai trò AAL2 không chọn được ở phiên AAL1) |
| SCR-0001 | Báo cáo truy cập bất thường (thu hồi phiên, đổi mật khẩu) `bao-bat-thuong` | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | - | - | BR-00-06; SEC-54 |
| SCR-0001 | Đăng ký, thu hồi khoá mã hoá đầu cuối của thiết bị hoặc chứng thư số `khoa-thiet-bi` | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | - | - | MFA (AAL2); ADR-27; SEC-25 |
| SCR-0002 | Xem trang chủ "Việc của tôi hôm nay" (bố cục theo vai trò) `xem` | R/B* | R/B* | R/B* | R/B | R/B | R/B | R/B | R/B* | R/B* | R/B* | - | - |  |
| SCR-0002 | Đổi phân công (ngữ cảnh) trên thanh trên `doi-phan-cong` | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | T/B | - | - | Ngữ cảnh mới yêu cầu AAL cao hơn thì step-up trước (TL-02 Ngữ cảnh làm việc) |
| SCR-0002 | Cập nhật nhanh tiến độ, mở check-in, nộp sản phẩm từ trang chủ `cap-nhat-nhanh` | - | - | - | - | T/B | T/B | - | - | - | - | - | - |  |
| SCR-0002 | Đánh dấu thông báo đã đọc `doc-thong-bao` | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | T/B* | - | - |  |

#### QT-01. Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0101 | Xem, lọc danh sách tenant và tải theo cell `xem` | R/N | - | - | - | - | - | - | - | - | - | - | - | Phiên AAL2 |
| SCR-0101 | Xuất danh sách tenant (tác vụ 202) `xuat` | R/N | - | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0102 | Khởi tạo tenant bước 1, 2, 3 và khởi chạy saga cấp phát `buoc-1-3` | X/N | - | - | - | - | - | - | - | - | - | - | - | Phiên AAL2 |
| SCR-0102 | Khai báo IdP, kiểm tra kết nối, nhập tổ chức ban đầu (bước 4, 5) `buoc-4-5` | R/N* | T/Tn | - | - | - | - | - | - | - | - | - | - | Phiên AAL2 |
| SCR-0102 | Kích hoạt tenant (bước 6, chuyển `DANG_VAN_HANH`) `kich-hoat` | D/N | - | - | - | - | - | - | - | - | - | - | - | MFA bước lên |
| SCR-0102 | Huỷ yêu cầu khởi tạo ở `KHOI_TAO` `huy` | X/N | - | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0103 | Xem cây tổ chức hiện tại và tại một ngày trong quá khứ `xem` | - | R/Tn | - | - | - | - | R/Cd* | - | - | R/Tn* | - | - |  |
| SCR-0103 | Nhập cây tổ chức từ Excel `nhap-excel` | - | X/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0103 | Thay đổi tổ chức (thành lập, sáp nhập, chia tách, giải thể) và sửa đơn vị `thay-doi` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-01-06 (không ghi đè lịch sử) |
| SCR-0103 | Thay khoá phục hồi của tổ chức, ghi biên bản khôi phục và nạp khoá bọc cho thiết bị mới khi mất khoá `khoa-phuc-hoi` | - | X/Tn* | - | - | - | - | - | - | - | - | - | - | MFA (AAL2); Shamir 3 trên 5, mảnh khoá giao trực tiếp theo biên bản (SEC-25) |

#### QT-12. Khiếu nại, giải trình

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0103 | Xem nhật ký khôi phục khoá mã hoá đầu cuối `nhat-ky-phuc-hoi` | - | R/Tn* | - | - | - | - | - | - | - | R/Tn* | - | - | MFA (AAL2) |

#### QT-01. Khởi tạo tenant, cây tổ chức và đồng bộ nhân sự

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0104 | Xem lần đồng bộ và danh sách lệch nhân sự `xem` | - | R/Tn | - | - | - | - | - | - | - | R/Tn* | H | - |  |
| SCR-0104 | Đồng bộ ngay, lịch đồng bộ, chạy lại thông điệp lỗi `dong-bo` | - | X/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0104 | Xử lý lệch: lấy theo nguồn, giữ nội bộ, ánh xạ thủ công `xu-ly-lech` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | Phiên AAL2 khi áp dụng hàng loạt |
| SCR-0104 | Giao cán bộ tổ chức đơn vị kiểm tra dòng lệch `giao-xu-ly` | - | T/Tn | - | - | - | - | R/Gn* | - | - | - | - | - |  |
| SCR-0105 | Xem vai trò đã cấp theo đơn vị (lọc vai trò, còn hiệu lực, sắp hết hạn, chờ duyệt) `xem` | - | R/Tn | - | R/Cd* | - | - | - | - | - | R/Tn* | - | - |  |
| SCR-0105 | Cấp vai trò có thời hạn, đơn vị phạm vi, phạm vi và luồng `cap` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-01-13, BR-01-14, BR-01-15 (step-up), BR-01-16 |
| SCR-0105 | Duyệt thứ hai hoặc từ chối đề nghị cấp `decider`, `second_approver`, `policy_author` `duyet` | - | D/Tn | - | D/Cd* | - | - | - | - | - | - | - | - | Bốn mắt: người duyệt khác người cấp và người được cấp (BR-01-13, BR-01-14); step-up |
| SCR-0105 | Thu hồi vai trò trước hạn, lý do bắt buộc `thu-hoi` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-01-13, BR-01-15 (step-up) |
| SCR-0106 | Xem hồ sơ quyền của một người: vai trò hiện hành, uỷ quyền đã giao và đã nhận, lịch sử `xem` | - | R/Tn | - | R/Cd* | - | - | - | - | - | R/Tn | - | - | Mọi lần tra cứu được ghi (BR-13-07) |
| SCR-0106 | Xuất lịch sử quyền của một người ra CSV `xuat` | - | R/Tn | - | - | - | - | - | - | - | R/Tn | - | - | Step-up khi xuất (BR-01-15) |
| SCR-0107 | Xem uỷ quyền khi vắng mặt đang hiệu lực và đã kết thúc của đơn vị `xem` | - | R/Tn* | - | R/Cd | - | - | - | - | - | R/Tn* | - | - |  |
| SCR-0107 | Khai báo duyệt thay, nghiệm thu thay, check-in KR thay có thời hạn `tao` | - | - | - | T/Cd | - | - | - | - | - | - | - | - | BR-01-13, BR-01-16, BR-01-17 (không uỷ quyền lại), BR-01-18; step-up |
| SCR-0107 | Thu hồi uỷ quyền trước hạn `thu-hoi` | - | - | - | T/Cd | - | - | - | - | - | - | - | - | Step-up (BR-01-15) |

#### QT-02. Lập và phê duyệt mục tiêu, OKR

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0201 | Xem cây mục tiêu, bảng, bản đồ liên kết, xuất mẫu báo cáo OKR `xem` | - | - | - | R/Cd* | R/Đv | R/Đv | - | - | - | - | - | A |  |
| SCR-0201 | Thêm mục tiêu của đơn vị (mở SCR-0202) `them` | - | - | - | - | T/Đv | - | - | - | - | - | - | - | Kỳ chưa khoá |
| SCR-0201 | Check-in KR từ cây mục tiêu `check-in` | - | - | - | - | T/B | T/B | - | - | - | - | - | - | Chủ sở hữu KR, mục tiêu `DA_DUYET` |
| SCR-0202 | Soạn, lưu nháp mục tiêu và KR của đơn vị `soan-don-vi` | - | - | - | - | T/Đv | - | - | - | - | - | - | - |  |
| SCR-0202 | Soạn, lưu nháp OKR cá nhân `soan-ca-nhan` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-0202 | Trợ lý AI soát KR và dùng gợi ý vào ô `ai-soat` | - | - | - | - | T/Đv | T/B | - | - | - | - | - | A | BR-02-11 (chỉ ghi khi người soạn xác nhận) |
| SCR-0202 | Trình phê duyệt OKR `trinh` | - | - | - | - | T/Đv | T/B | - | - | - | - | - | - |  |
| SCR-0203 | Xem hàng chờ OKR, kết quả kiểm tra tự động, cây ở chế độ chỉ đọc `xem` | - | - | - | R/Cd | - | - | - | - | - | - | - | - |  |
| SCR-0203 | Phê duyệt OKR `duyet` | - | - | - | D/Cd | - | - | - | - | - | - | - | - | BR-02-08 (người duyệt khác người soạn), MSG-02-11 |
| SCR-0203 | Trả lại OKR để sửa `tra-lai` | - | - | - | D/Cd | - | - | - | - | - | - | - | - |  |
| SCR-0203 | Duyệt hàng loạt các bộ OKR đủ điều kiện `duyet-lo` | - | - | - | D/Cd | - | - | - | - | - | - | - | - | BR-02-08 áp cho từng bộ |

#### QT-03. Check-in KR

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0301 | Check-in KR: lưu nháp, gửi, lấy lại số liệu nguồn `check-in` | - | - | - | - | T/B | T/B | - | - | - | - | - | - | Người được giao check-in thay ghi rõ "check-in thay" |
| SCR-0301 | Xem lịch sử check-in `lich-su` | - | - | - | R/Cd* | R/Đv* | R/B | - | - | - | - | - | - |  |
| SCR-0301 | Giao quyền check-in tạm thời khi chủ sở hữu KR vắng mặt dài ngày `giao-check-in-thay` | - | - | - | T/Đv* | - | - | - | - | - | - | - | - |  |
| SCR-0302 | [Di động] Check-in nhanh, nói để nhập `check-in-dd` | - | - | - | - | T/B | T/B | - | - | - | - | - | A* |  |

#### QT-04. Lập và phê duyệt kế hoạch công tác quý

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0401 | Xem thư viện danh mục công việc `xem` | - | - | R/Tn* | R/Đv* | R/Đv | R/Đv | - | - | - | - | - | A* |  |
| SCR-0401 | Thêm, sửa mục, nhân bản phiên bản, nhập Excel danh mục `sua` | - | - | X/Tn* | - | X/Gn | - | - | - | - | - | - | - |  |
| SCR-0402 | Xem kế hoạch quý của đơn vị `xem` | - | - | - | R/Cd* | R/Đv | R/Đv* | R/Cd | - | - | - | - | - |  |
| SCR-0402 | Lập kế hoạch: sao chép quý trước, gợi ý từ OKR, thêm dòng, lưu nháp `lap` | - | - | - | - | T/Đv | - | - | - | - | - | - | - |  |
| SCR-0402 | Gửi duyệt kế hoạch `gui-duyet` | - | - | - | - | T/Đv | - | - | - | - | - | - | - |  |
| SCR-0402 | Tạo điều chỉnh kế hoạch (phiên bản n+1) `dieu-chinh` | - | - | - | - | T/Đv | - | - | - | - | - | - | - | BR-04-10 (tối đa 2 lần mỗi quý) |
| SCR-0403 | So sánh phiên bản kế hoạch `so-sanh` | - | - | - | R/Đv | - | - | - | - | - | - | - | - |  |
| SCR-0403 | Trả lại kế hoạch với ý kiến `tra-lai` | - | - | - | D/Đv | - | - | - | - | - | - | - | - |  |
| SCR-0403 | Duyệt và ký số kế hoạch `duyet-ky` | - | - | - | D/Đv | - | - | - | - | - | - | - | - | BR-04-08 (người duyệt khác người lập); BR-00-02 (MFA trong 15 phút) |

#### QT-05. Giao việc, đăng ký nhiệm vụ, nhận nhiệm vụ từ văn bản

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0501 | Soạn, lưu nháp, huỷ nháp nhiệm vụ `soan` | - | - | - | - | T/Đv | - | - | - | - | - | - | - |  |
| SCR-0501 | Giao việc (chủ trì, phối hợp, người nghiệm thu) `giao` | - | - | - | - | T/Đv | - | - | - | - | - | - | - | BR-05-02 (người nghiệm thu khác chủ trì, phối hợp), MSG-05-02 |
| SCR-0501 | Giao đồng loạt cho nhiều đơn vị `giao-dong-loat` | - | - | - | - | T/Cd* | - | - | - | - | - | - | - |  |
| SCR-0501 | Xem nhiệm vụ gần giống `trung` | - | - | - | - | R/Đv | - | - | - | - | - | - | - |  |
| SCR-0501 | Tra khoá công khai người nhận, khoá ký danh bạ và khoá phục hồi để mã hoá nội dung mật `khoa-nguoi-nhan` | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | R/Tn* | - | - | ADR-27; chỉ khoá công khai, không có dữ liệu C3 |
| SCR-0502 | Nhận văn bản chỉ đạo từ hệ thống văn phòng điện tử `nhan-van-ban` | - | - | - | - | R/Đv | - | - | - | - | - | H | - |  |
| SCR-0502 | Trích nhiệm vụ từ văn bản bằng AI `ai-trich` | - | - | - | - | - | - | - | - | - | - | - | A | BR-02-11 tương tự: chỉ ghi khi người giao xác nhận |
| SCR-0502 | Sửa, bỏ, gộp, xác nhận nhiệm vụ trích; bỏ qua văn bản `xac-nhan` | - | - | - | - | T/Đv | - | - | - | - | - | - | - |  |
| SCR-0502 | Xác nhận và giao các nhiệm vụ đã xác nhận `xac-nhan-giao` | - | - | - | - | T/Đv | - | - | - | - | - | - | - | BR-05-02 |
| SCR-0503 | Xem, lọc việc của tôi, xuất Excel `xem` | - | - | - | - | - | R/B | - | - | - | - | - | - |  |
| SCR-0503 | Nhận việc (`DA_GIAO` → `DANG_THUC_HIEN`) `nhan-viec` | - | - | - | - | - | T/B | - | - | - | - | - | - | Người chủ trì |
| SCR-0503 | Cập nhật tiến độ một nhiệm vụ hoặc hàng loạt `tien-do` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-0503 | Gửi đăng ký nhiệm vụ cho người quản lý `dang-ky` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-0504 | [Di động] Xem việc, cập nhật tiến độ, báo điểm nghẽn `dd` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |

#### QT-06. Cập nhật tiến độ, điểm nghẽn, đề nghị gia hạn/điều chỉnh

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0601 | Xem chi tiết nhiệm vụ, tiến độ, sản phẩm, lịch sử `xem` | - | - | - | R/Cd | R/Đv | R/B | R/Cd* | - | - | R/Tn* | - | - |  |
| SCR-0601 | Cập nhật tiến độ `tien-do` | - | - | - | - | - | T/B | - | - | - | - | - | - | Người chủ trì; nhiệm vụ đang thực hiện; BR-06-02 |
| SCR-0601 | Báo điểm nghẽn `bao-nghen` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-0601 | Xử lý điểm nghẽn: giao người xử lý, đặt hạn, đóng `xu-ly-nghen` | - | - | - | - | D/Đv | - | - | - | - | - | - | - | Người giao |
| SCR-0601 | Duyệt đề nghị gia hạn, điều chỉnh (chọn nguyên nhân, chấp nhận, từ chối) `duyet-de-nghi` | - | - | - | D/Cd* | D/Đv | - | - | - | - | - | - | - | BR-06-08 (người duyệt khác người gửi) |
| SCR-0601 | Nộp sản phẩm (mở SCR-0701) `nop-sp` | - | - | - | - | - | T/B | - | - | - | - | - | - | Người chủ trì |
| SCR-0602 | Lập, xem trước hệ quả, đính kèm, gửi đề nghị gia hạn, điều chỉnh, tạm dừng, huỷ `gui` | - | - | - | - | - | T/B | - | - | - | - | - | - | BR-06-09 |
| SCR-0602 | Rút lại đề nghị `CHO_DUYET` của mình `rut` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |

#### QT-07. Nộp và nghiệm thu sản phẩm, minh chứng

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0701 | Tải tệp, chọn văn bản, lưu nháp minh chứng `soan` | - | - | - | - | - | T/B | - | - | - | - | - | - | SEC-47, SEC-48 |
| SCR-0701 | Nộp minh chứng để nghiệm thu `nop` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-0701 | Xem minh chứng đã nộp `xem` | - | - | - | - | R/Gn* | R/B | R/Cd | - | - | - | - | - |  |
| SCR-0702 | [Di động] Chụp ảnh, OCR, xác nhận và nộp minh chứng `dd` | - | - | - | - | - | T/B | - | - | - | - | - | A |  |
| SCR-0703 | Mở minh chứng, kiểm toàn vẹn, xem trước điểm `mo` | - | - | - | - | R/Gn | - | - | - | - | - | - | - |  |
| SCR-0703 | Xác nhận nghiệm thu chất lượng 5 mức `xac-nhan` | - | - | - | - | D/Gn | - | - | - | - | - | - | - | BR-07-06 (khác người nộp, phối hợp); BR-07-11 (MFA trong 15 phút) |
| SCR-0703 | Yêu cầu bổ sung hoặc từ chối minh chứng `bo-sung-tu-choi` | - | - | - | - | D/Gn | - | - | - | - | - | - | - | BR-07-06 |

#### QT-08. Khoá kỳ (saga chốt kỳ)

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0801 | Xem danh sách kỳ, lệnh khoá kỳ và tiến trình saga, sơ đồ BPMN `xem` | - | R/Tn | - | R/Tn* | - | - | - | - | R/Tn | R/Tn | - | - |  |
| SCR-0801 | Tạo kỳ, lịch ngày nghỉ và làm bù `tao-ky` | - | X/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0801 | Lập lệnh khoá kỳ `lap-lenh` | - | T/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-0801 | Phê duyệt lệnh khoá kỳ `duyet-lenh` | - | - | - | D/Tn* | - | - | - | - | - | - | - | - | Khác người lập (CHECK `approved_by <> requested_by`); MFA |
| SCR-0801 | Khởi chạy saga khoá kỳ `khoi-chay` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-08-01 (khác người phê duyệt); BR-08-02 (MFA 15 phút) |
| SCR-0801 | Tạm dừng sau bước hiện tại, chạy lại hồ sơ lỗi `dieu-khien` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-08-02 |
| SCR-0801 | Đề nghị huỷ và bù trừ toàn bộ saga `de-nghi-huy` | - | T/Tn | - | - | - | - | - | - | - | - | - | - | BR-08-02, BR-08-09; MSG-08-05 |
| SCR-0801 | Duyệt thứ hai huỷ và bù trừ `duyet-huy` | - | - | - | - | - | - | - | - | D/Tn | - | - | - | BR-08-09 (hai người duyệt) |

#### QT-09. Tự đánh giá, thẩm định, đề xuất, quyết định xếp loại quý

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-0901 | Xem kết quả hệ thống tính, vết tính, xem trước Mẫu 1B, chuyển luồng `xem` | - | - | - | - | R/Đv | R/B | - | - | - | - | - | - |  |
| SCR-0901 | Lưu nháp phiếu tự đánh giá `luu` | - | - | - | - | - | T/B | - | - | - | - | - | - | Hồ sơ ở `TU_DANH_GIA` |
| SCR-0901 | Ký số và nộp phiếu tự đánh giá `ky-nop` | - | - | - | - | - | T/B | - | - | - | - | - | - | Step-up khi ký (TL-01 Phụ lục C: chủ hồ sơ, AAL2) |
| SCR-0902 | Xem hàng đợi, chi tiết hồ sơ, mở minh chứng (ghi nhật ký truy cập C2) `xem` | - | - | - | - | - | - | R/Cd | - | - | - | - | - | BR-09-14 |
| SCR-0902 | Trả lại hồ sơ bổ sung `tra-lai` | - | - | - | - | - | - | D/Cd | - | - | - | - | - |  |
| SCR-0902 | Ký số kết quả thẩm định và chuyển đề xuất `ky` | - | - | - | - | - | - | D/Cd | - | - | - | - | - | BR-09-14 (không thẩm định hồ sơ mình hoặc đã nghiệm thu), MSG-09-08; AAL2 |
| SCR-0902 | Xuất danh sách hàng đợi (không gồm trường C2) `xuat` | - | - | - | - | - | - | R/Cd | - | - | - | - | - |  |
| SCR-0903 | Xem phiếu xếp loại, vết tính, luồng Nhà nước `xem` | - | - | - | - | R/Đv | R/B | R/Cd | R/Cd | - | - | - | - |  |
| SCR-0903 | Ghi mức đề xuất xếp loại `de-xuat` | - | - | - | - | D/Đv | - | - | - | - | - | - | - | BR-09-14; hồ sơ `DA_THAM_DINH` |
| SCR-0903 | Tải Mẫu 1B (PDF/A) đã ký `tai-mau-1b` | - | - | - | - | R/Đv* | R/B | R/Cd* | R/Cd* | - | - | - | - |  |
| SCR-0903 | Gửi khiếu nại (mở SCR-1201) `khieu-nai` | - | - | - | - | - | T/B | - | - | - | - | - | - | Hồ sơ `DA_KY_SO`, còn hạn |
| SCR-0904 | Xem hồ sơ chờ quyết định, phân bố mức, xuất Mẫu 3 `xem` | - | - | - | - | - | - | - | R/Cd | - | - | - | - |  |
| SCR-0904 | Đổi mức quyết định có lý do `doi-muc` | - | - | - | - | - | - | - | D/Cd | - | - | - | - |  |
| SCR-0904 | Quyết định và ký lô `ky-lo` | - | - | - | - | - | - | - | D/Cd | - | - | - | - | BR-09-14; MFA bước lên khi ký lô |
| SCR-0904 | Theo dõi tiến độ và huỷ lô chưa hoàn tất `theo-doi-huy` | - | - | - | - | - | - | - | D/Cd | - | - | - | - |  |

#### QT-10. Xếp loại năm, liên thông quý–năm, phân nhóm triển vọng

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1001 | Xem tổng hợp bốn quý, mức năm và giải thích, lọc cảnh báo liên thông `xem` | - | - | - | R/Cd | - | - | R/Cd | R/Cd | - | - | - | - |  |
| SCR-1001 | Chuyển thẩm định năm (mở SCR-0902 chế độ năm) `tham-dinh-nam` | - | - | - | - | - | - | D/Cd | - | - | - | - | - | BR-09-14 |
| SCR-1001 | Lập đề xuất bố trí lại `bo-tri-lai` | - | - | - | - | - | - | T/Cd | - | - | - | - | - |  |
| SCR-1002 | Xem danh sách phân nhóm và tiêu chí PL 5 `xem` | - | - | - | R/Đv | - | - | R/Đv | - | - | - | - | - | BR-10-10 (C2, chỉ `leader`, `appraiser` của đơn vị) |
| SCR-1002 | Kéo thả xếp nhóm, ghi lý do, lưu nháp `xep-nhom` | - | - | - | T/Đv | - | - | T/Đv | - | - | - | - | - | BR-10-07, BR-10-09 |
| SCR-1002 | Trình tập thể lãnh đạo `trinh` | - | - | - | T/Đv | - | - | - | - | - | - | - | - |  |

#### QT-11. Kiểm điểm và hồ sơ số

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1101 | Tạo hội nghị kiểm điểm `tao` | - | - | - | - | - | - | T/Đv* | - | - | - | - | - |  |
| SCR-1101 | Gửi dự thảo bản kiểm điểm `gui-du-thao` | - | - | - | - | - | T/B | - | - | - | - | - | - | BR-11-01 |
| SCR-1101 | Tải dự thảo Mẫu 1B, gửi góp ý `gop-y` | - | - | - | T/Gn* | T/Gn* | T/Gn | T/Gn* | - | - | - | - | - | BR-11-06 (góp ý C2, ẩn danh nếu tenant bật) |
| SCR-1101 | Ghi ý kiến tại hội nghị, sinh biên bản (thư ký) `thu-ky` | - | - | - | - | - | T/Gn | T/Gn | - | - | - | - | - |  |
| SCR-1101 | Kết thúc hội nghị, khởi động ký biên bản (chủ trì) `ket-thuc` | - | - | - | D/Gn* | - | - | - | - | - | - | - | - | Step-up khi ký biên bản (TL-01: thư ký, AAL2) |
| SCR-1102 | Theo dõi sinh biểu mẫu, sinh lại mẫu lỗi, xem biểu mẫu `chuan-bi` | - | - | - | - | - | - | T/Đv | R/Đv | - | - | - | - |  |
| SCR-1102 | Tải gói ZIP và manifest `tai-goi` | - | - | - | - | - | - | R/Đv | R/Đv | - | - | - | - |  |
| SCR-1102 | Ký số gói và nộp cấp trên `ky-goi` | - | - | - | - | - | - | - | D/Đv | - | - | - | - | MFA bước lên khi ký; BR-11-10 |
| SCR-1102 | Gửi gói con diện Trung ương `goi-con` | - | - | - | - | - | - | T/Đv* | - | - | - | H | - |  |

#### QT-12. Khiếu nại, giải trình

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1201 | Soạn, lưu nháp, đính kèm khiếu nại (mã hoá nội dung) `soan` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-1201 | Ký số và gửi khiếu nại `ky-gui` | - | - | - | - | - | T/B | - | - | - | - | - | - | Ký số (step-up theo TL-01) |
| SCR-1201 | Rút khiếu nại, xác nhận bảo lưu ý kiến `rut-bao-luu` | - | - | - | - | - | T/B | - | - | - | - | - | - |  |
| SCR-1202 | Tiếp nhận hoặc không thụ lý khiếu nại `tiep-nhan` | - | - | - | - | - | - | D/Gn | - | - | - | - | - | BR-12-04, MSG-12-05 |
| SCR-1202 | Yêu cầu giải trình; bên liên quan gửi giải trình `giai-trinh` | - | - | - | - | T/Gn* | - | T/Gn | - | - | - | - | - |  |
| SCR-1202 | Tính thử điều chỉnh, xem văn bản trả lời `tinh-thu` | - | - | - | - | - | - | R/Gn | - | - | - | - | - |  |
| SCR-1202 | Ký số văn bản trả lời khiếu nại `ky-tra-loi` | - | - | - | - | - | - | - | D/Gn | - | - | - | - | BR-12-04; step-up khi ký |
| SCR-1202 | Tạo yêu cầu mở lại kỳ từ kết luận khiếu nại `mo-lai` | - | - | - | - | - | - | T/Gn | - | - | - | - | - | BR-12-07 |

#### QT-13. Hậu kiểm, mở lại kỳ (hai người duyệt)

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1301 | Tạo yêu cầu mở lại kỳ, tính thử ảnh hưởng `tao` | - | - | - | - | - | - | T/Cd | - | - | T/Tn | - | - | AAL2 (TL-01 Phụ lục C) |
| SCR-1301 | Phê duyệt thứ nhất (step-up MFA, ký số) `duyet-1` | - | - | - | - | - | - | - | D/Gn | - | - | - | - | BR-13-02, BR-13-03, BR-13-04; MSG-13-01, MSG-13-02 |
| SCR-1301 | Phê duyệt thứ hai (step-up MFA, ký số), mở lại kỳ `duyet-2` | - | - | - | - | - | - | - | - | D/Gn | - | - | - | BR-13-02, BR-13-03, BR-13-04 |
| SCR-1301 | Từ chối yêu cầu mở lại kỳ `tu-choi` | - | - | - | - | - | - | - | D/Gn | D/Gn | - | - | - |  |
| SCR-1301 | Huỷ yêu cầu trước khi đủ phê duyệt `huy` | - | - | - | - | - | - | T/B | - | - | T/B | - | - |  |
| SCR-1302 | Tra cứu sổ cái, xem bản ghi, niêm phong, bản neo `tra-cuu` | - | - | - | - | - | - | R/Đv | - | - | R/Tn | - | - | Mọi lần tra cứu được ghi sổ cái |
| SCR-1302 | Chạy kiểm tra toàn vẹn chuỗi băm `kiem-tra` | - | - | - | - | - | - | - | - | - | X/Tn | - | - |  |
| SCR-1302 | Xuất bằng chứng (JSON và chữ ký) `xuat` | - | - | - | - | - | - | - | - | - | R/Tn | - | - |  |

#### QT-14. Cảnh báo bất thường

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1401 | Xem, lọc cảnh báo, xem chuỗi bằng chứng, mở sổ cái `xem` | - | - | - | - | - | - | R/Cd | - | - | R/Cd | - | - | BR-14-03 (ẩn cảnh báo mà người xem là đối tượng) |
| SCR-1401 | Xác nhận hợp lệ hoặc giao kiểm tra `xu-ly` | - | - | - | - | - | - | D/Cd | - | - | D/Cd* | - | - | BR-14-08 (người xử lý không là đối tượng) |
| SCR-1401 | Kết luận kiểm tra (có vi phạm thì mở SCR-1301) `ket-luan` | - | - | - | - | - | - | - | - | - | D/Cd | - | - | BR-14-08 |
| SCR-1401 | Cấu hình ngưỡng phát hiện `nguong` | - | - | X/Tn | - | - | - | - | - | - | - | - | - | BR-14-09 |

#### QT-15. Soạn, kiểm thử và phát hành Policy Pack

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1501 | Soạn công thức, lưu nháp, xem trước, so sánh phiên bản `soan` | - | R/Tn* | X/Tn | - | - | - | - | - | - | - | - | - |  |
| SCR-1501 | Gửi phê duyệt phát hành Policy Pack `gui-duyet` | - | - | T/Tn | - | - | - | - | - | - | - | - | - |  |
| SCR-1502 | Soạn bảng quyết định DMN, lưu nháp, xuất DMN XML `soan` | - | - | X/Tn | - | - | - | - | - | - | - | - | - |  |
| SCR-1503 | Thêm ca kiểm thử, chạy lại bộ test case vàng `chay` | - | - | X/Tn | - | - | - | - | - | - | - | - | - |  |
| SCR-1503 | Xem kết quả test khi phê duyệt `xem-duyet` | - | - | - | - | - | - | - | R/Tn | - | - | - | - |  |
| SCR-1503 | Ký số phê duyệt phát hành Policy Pack `ky-duyet` | - | - | - | - | - | - | - | D/Tn | - | - | - | - | BR-15-09 (khác người soạn); AAL2 |
| SCR-1504 | Chọn mẫu luồng, mô phỏng, lưu tham số luồng duyệt `cau-hinh` | - | - | X/Tn | - | - | - | - | - | - | - | - | - |  |
| SCR-1505 | Xem thư viện mẫu luồng: phiên bản, trạng thái, số tenant đang dùng, số test `thu-vien` | R/N | - | - | - | - | - | - | - | R/N* | - | - | - |  |
| SCR-1505 | Soạn, sửa mẫu luồng bằng bpmn-js, khai báo tham số; linter bpmnlint chạy ngay trên trình duyệt `soan` | T/N | - | - | - | - | - | - | - | - | - | - | - | 4 quy tắc chống mạng nhện (TL-01), máy chủ chạy lại linter khi lưu |
| SCR-1505 | Chạy bộ test kịch bản và thử tải rút gọn trên engine kiểm thử riêng `chay-test` | T/N | - | - | - | - | - | - | - | - | - | - | - | ≤ 20 câu SQL mỗi lần hoàn thành user task; ≤ 12 user task mỗi nhánh; ≤ 50 phần tử đa thể hiện |
| SCR-1505 | Đề nghị phát hành: tạo merge request vào repo mẫu luồng kèm BPMN, tham số, test `de-nghi-phat-hanh` | T/N | - | - | - | - | - | - | - | - | - | - | - | Chỉ khi DAT_KIEM_THU |
| SCR-1505 | Phê duyệt phát hành mẫu luồng (bốn mắt), ký số gói mẫu `phe-duyet` | - | - | - | - | - | - | - | - | D/N | - | - | - | Bốn mắt: người duyệt khác người soạn; AAL2, step-up |
| SCR-1505 | Theo dõi saga triển khai cuốn chiếu lên engine từng cell, bù trừ về phiên bản trước `trien-khai-cell` | X/N | - | - | - | - | - | - | - | - | - | - | - | Ngoài giờ cao điểm, không trong 3 ngày cuối kỳ; CELL-NB trước |
| SCR-1506 | Xem tiến trình đang chạy theo mẫu, phát hiện tiến trình kẹt `xem` | R/N | R/Tn | - | - | - | - | - | - | - | - | - | - | Không truy vấn ACT_* lúc cao điểm |
| SCR-1506 | Chuyển người xử lý của user task khi cán bộ nghỉ hoặc chuyển công tác `chuyen-nguoi-xu-ly` | T/N* | T/Tn | - | - | - | - | - | - | - | - | - | - | Ghi sổ cái |
| SCR-1506 | Gia hạn thời hạn của một bước `gia-han-buoc` | T/N* | T/Tn | - | - | - | - | - | - | - | - | - | - | Ghi sổ cái; không sửa biến, không nhảy bước |
| SCR-1506 | Xem sơ đồ tiến trình với bước hiện tại `so-do` | R/N | R/Tn | - | - | - | - | - | - | - | - | - | - |  |

#### QT-16. Bảng điều hành và báo cáo

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1601 | Xem bảng điều hành, so với quý trước, xuất báo cáo `xem` | - | - | - | R/Cd | R/Đv | - | - | - | - | R/Cd* | - | - | BR-16-01 (lọc theo cây tổ chức) |
| SCR-1601 | Nhắc người chủ trì việc chậm `nhac` | - | - | - | T/Cd | T/Đv | - | - | - | - | - | - | - | BR-16-07 (dùng quyền của người bấm) |
| SCR-1601 | Giao lại nhiệm vụ trong phạm vi `giao-lai` | - | - | - | T/Cd* | T/Đv | - | - | - | - | - | - | - | BR-16-07; người bấm có quyền giao |
| SCR-1601 | Xem dữ liệu AI đã dùng cho tóm tắt `nguon-ai` | - | - | - | R/Cd | R/Đv | - | - | - | - | - | - | A |  |
| SCR-1602 | Dựng, xem trước, lưu dashboard `dung` | - | - | - | T/Cd | T/Đv | - | - | - | - | - | - | - | Dữ liệu theo phạm vi của người xem |
| SCR-1602 | Chia sẻ dashboard (`RIENG`, `DON_VI`, `TENANT`) `chia-se` | - | - | - | T/Cd | T/Đv | - | - | - | - | - | - | - |  |
| SCR-1603 | Xem chuỗi truy vết từ điểm tới minh chứng và ngược lên mục tiêu `xem` | - | - | - | R/Cd | R/Đv | - | R/Cd | - | - | R/Cd | - | - | Mỗi mắt xích kiểm quyền riêng; MSG-16-06 |
| SCR-1603 | Mở tệp minh chứng (ghi nhật ký truy cập) `mo-c2` | - | - | - | R/Cd | R/Đv | - | R/Cd | - | - | R/Cd | - | - | Có quyền C2 |
| SCR-1603 | Xuất chuỗi bằng chứng JSON và PDF `xuat` | - | - | - | R/Cd | R/Đv | - | R/Cd | - | - | R/Cd | - | - |  |

#### QT-17. Trợ lý AI

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1701 | Hỏi trợ lý AI, nhận gợi ý OKR, cảnh báo KR mơ hồ `hoi` | - | - | - | - | T/Đv | T/B | - | - | - | - | - | A | BR-17-03, BR-17-07; MSG-17-02 |
| SCR-1701 | Dùng hoặc bỏ gợi ý, lưu nháp, xem lịch sử nháp `dung-goi-y` | - | - | - | - | T/Đv | T/B | - | - | - | - | - | - | BR-02-11, BR-17-08 |
| SCR-1701 | Trình phê duyệt OKR từ trợ lý `trinh` | - | - | - | - | T/Đv | T/B | - | - | - | - | - | - |  |

#### QT-18. Cấu hình giao diện tenant (UI Pack)

| SCR | Chức năng (khoá) | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ràng buộc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCR-1801 | Soạn token, bố cục, chế độ cao tuổi, thêm widget, xem trước `soan` | - | X/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-1801 | Gửi duyệt UI Pack `gui-duyet` | - | T/Tn | - | - | - | - | - | - | - | - | - | - | BR-18-01, BR-18-08 |
| SCR-1801 | Duyệt và phát hành UI Pack `duyet` | - | D/Tn* | - | - | - | - | - | - | - | - | - | - | Người duyệt khác người soạn; AAL2 (TL-01 Phụ lục C) |
| SCR-1801 | Quay lui về phiên bản `THAY_THE` tương thích `quay-lui` | - | X/Tn | - | - | - | - | - | - | - | - | - | - | BR-18-10; MSG-18-04 |
| SCR-1802 | Dựng biểu mẫu, lưu nháp, chạy bộ test biểu mẫu `dung` | - | X/Tn | T/Tn | - | - | - | - | - | - | - | - | - | BR-18-04, BR-18-05, BR-18-06, BR-18-11 |
| SCR-1802 | Đưa biểu mẫu vào UI Pack nháp `dua-vao-pack` | - | X/Tn | - | - | - | - | - | - | - | - | - | - |  |
| SCR-1803 | [Di động] Chế độ cao tuổi: xác nhận sinh hoạt, tự đánh giá rút gọn, nhập giọng nói `dd` | - | - | - | - | - | T/B | - | - | - | - | - | A* |  |
<!-- END GEN:ma-tran-chinh -->

### Ghi chú về ô suy luận và điểm lệch

<!-- BEGIN GEN:ghi-chu -->
| Khoá | Ghi chú về ô suy luận hoặc điểm lệch |
|---|---|
| `SCR-0001.dang-nhap` | AC-11 là VNeID, IdP cơ quan qua môi giới danh tính. |
| `SCR-0001.khoa-thiet-bi` | Bổ sung ngoài TL-02 cùng CORE-07-06: thiết bị tự đăng ký khoá sau lần đăng nhập có MFA đầu tiên, không có nút riêng; chỉ thu hồi khoá của chính mình. |
| `SCR-0002.xem` | Vai trò khác AC-04..AC-07 suy luận từ "vai trò bất kỳ". |
| `SCR-0102.buoc-4-5` | AC-01 xem tiến độ bước 4, 5 là suy luận. |
| `SCR-0103.xem` | Phạm vi Cd của AC-07 và Tn của AC-10 là suy luận từ phạm vi mặc định của vai trò. |
| `SCR-0103.khoa-phuc-hoi` | Bổ sung cùng CORE-07-06; TL-02 chưa có màn hình riêng, tạm gắn SCR-0103 như hợp đồng. |
| `SCR-0103.nhat-ky-phuc-hoi` | Bổ sung cùng CORE-07-06: cơ quan kiểm tra đối chiếu mọi lần khôi phục. |
| `SCR-0104.xem` | AC-10 đọc nhật ký đồng bộ theo bảng tác nhân TL-02 nhưng SCR-0104 chỉ ghi AC-02; AC-11 là CSDL CBCCVC. |
| `SCR-0104.giao-xu-ly` | Người nhận "cán bộ tổ chức đơn vị" ánh xạ sang AC-07 là suy luận. |
| `SCR-0105.xem` | Quyền xem của AC-04, AC-10 là suy luận từ phạm vi mặc định của vai trò. |
| `SCR-0105.duyet` | Người duyệt thứ hai là một AC-02 khác người cấp hoặc AC-04 có thẩm quyền trên đơn vị phạm vi (quyết định điểm 3 của WEB-058). |
| `SCR-0106.xem` | Người dùng tự xem quyền của mình chưa làm ở màn hình quản trị (giữ menu của AC-06 không đổi). |
| `SCR-0107.xem` | AC-02, AC-10 xem để tra cứu là suy luận. |
| `SCR-0201.xem` | AC-04 xem cây cấp dưới suy luận từ phạm vi lãnh đạo. |
| `SCR-0301.check-in` | Người được giao check-in thay có phạm vi Gn trên KR đó. |
| `SCR-0301.lich-su` | AC-04, AC-05 xem lịch sử check-in của cấp dưới suy luận từ SCR-0201 (thành viên đơn vị xem). |
| `SCR-0301.giao-check-in-thay` | Nguồn không có nút trên SCR-0301; thao tác làm ở SCR-0107 (WEB-058) với khoá SCR-0107.tao. |
| `SCR-0302.check-in-dd` | Màn hình ứng dụng Flutter (SVC-18). |
| `SCR-0401.sua` | Phạm vi Tn của AC-03 là suy luận; AC-05 chỉ khi được giao quản lý danh mục (Gn). TL-01 ghi `org_officer` thay cho `policy_author`. |
| `SCR-0501.giao-dong-loat` | Phạm vi Cd (đơn vị cấp dưới) là suy luận; cần OPA quyết định danh sách đơn vị được giao. |
| `SCR-0501.khoa-nguoi-nhan` | Bổ sung cùng CORE-07-06: mọi người dùng soạn hoặc chia sẻ nội dung mật cần đọc khoá công khai của người nhận trong tenant (suy luận). |
| `SCR-0503.dang-ky` | Bước người quản lý duyệt đăng ký chưa có chức năng riêng trên SCR (nhiệm vụ `NHAP` có cờ đăng ký, xử lý ở SCR-0501). |
| `SCR-0601.xem` | Phạm vi của AC-07, AC-10 là suy luận. |
| `SCR-0601.duyet-de-nghi` | AC-04 duyệt khi đề nghị vượt ngưỡng chuyển cấp trên (MSG-06-07) là suy luận. |
| `SCR-0701.xem` | AC-05 xem trước khi mở nghiệm thu là suy luận. |
| `SCR-0801.xem` | AC-04 xem để phê duyệt lệnh; phạm vi Tn là suy luận. |
| `SCR-0801.khoi-chay` | TL-01 Phụ lục C ghi `org_officer` khởi động saga; TL-02 ghi AC-02. |
| `SCR-0903.tai-mau-1b` | Bảng chức năng không ghi người được tải; suy luận theo quyền xem phiếu. |
| `SCR-0904.xem` | "Thư ký tổng hợp hỗ trợ chuẩn bị nhưng không ký": nguồn không nêu vai trò của thư ký. |
| `SCR-1101.tao` | SCR-1101 không có nút tạo; TL-01 ghi `org_officer`, ánh xạ sang AC-07 là suy luận. |
| `SCR-1101.gop-y` | Thành viên hội nghị là phân công trên đối tượng, không gắn với vai trò; các vai trò ngoài AC-06 là suy luận. |
| `SCR-1101.ket-thuc` | Nguồn không nêu vai trò của chủ trì; AC-04 là suy luận. |
| `SCR-1102.goi-con` | Nguồn không nêu ai bấm; AC-07 (người chuẩn bị) là suy luận. |
| `SCR-1202.giai-trinh` | Bên liên quan (người nghiệm thu, đề xuất) ánh xạ AC-05 là suy luận. |
| `SCR-1202.ky-tra-loi` | TL-01 Phụ lục C ghi `decider` cấp trên trả lời; TL-02 ghi AC-07 xử lý, AC-08 ký. |
| `SCR-1301.tao` | TL-01 Phụ lục C ghi `org_officer`. |
| `SCR-1401.xu-ly` | Bảng chức năng chỉ ghi điều kiện trạng thái; AC-10 làm bước này là suy luận. |
| `SCR-1501.soan` | AC-02 xem danh sách gói theo TL-01 Phụ lục C (`GET /policies`). |
| `SCR-1503.ky-duyet` | TL-01 Phụ lục C ghi vai trò `policy_approver` và đường dẫn `/publish`. |
| `SCR-1505.thu-vien` | Chỉ người giữ vai trò con `wf_template_author` của AC-01; AC-09 xem để phê duyệt (suy luận). Tenant không thấy màn hình. |
| `SCR-1505.soan` | Vai trò `wf_template_author`. Phiên bản HIEU_LUC không sửa được. |
| `SCR-1505.phe-duyet` | AC-09 ở phạm vi nền tảng là người của đội sản phẩm Viettel, khác AC-09 trong tenant. |
| `SCR-1506.xem` | AC-01 xem tổng hợp theo cell. |
| `SCR-1506.chuyen-nguoi-xu-ly` | AC-01 thực hiện là suy luận; nguồn nêu thao tác chung cho màn hình. |
| `SCR-1601.xem` | AC-10 có trong TL-01 và menu hiện tại nhưng không có trong tác nhân SCR-1601. |
| `SCR-1801.duyet` | Nguồn không nêu vai trò người duyệt; TL-01 ghi `tenant_admin` nên ánh xạ AC-02 khác người soạn là suy luận. |
<!-- END GEN:ghi-chu -->

## Ma trận phụ: vai trò theo module API

Bảng dưới ghi các phương thức HTTP mà mỗi vai trò có thể gọi trên từng module `/api/v1/<module>`, tổng hợp từ cột API của bảng chức năng TL-02 và cột quyền của TL-01 Phụ lục C. Đây là bảng định hướng cho người viết `@vt/api-client` và kiểm thử, không phải danh sách cho phép ở cổng: APISIX chỉ kiểm token, còn quyết định theo từng endpoint và từng đối tượng nằm ở OPA trong mission-core. AC-11 không gọi các module này mà kết nối qua integration-gateway (SVC-13) bằng client credentials; AC-12 là danh tính kỹ thuật của ai-gateway (SVC-14), đọc dữ liệu dưới danh tính người dùng nhờ trao đổi token.

<!-- BEGIN GEN:api -->
| Module | AC-01 | AC-02 | AC-03 | AC-04 | AC-05 | AC-06 | AC-07 | AC-08 | AC-09 | AC-10 | AC-11 | AC-12 | Ghi chú |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `/api/v1/okr` | - | - | - | GET POST | GET POST PUT | GET POST PUT | - | - | - | GET* | - | GET* | AC-04 POST = approve, return, approve-batch. |
| `/api/v1/tasks` | - | - | - | GET POST | GET POST PUT PATCH DELETE | GET POST | GET* | - | - | GET* | - | GET* | AC-04 POST = reminders, reassign, quyết định đề nghị vượt ngưỡng (suy luận). |
| `/api/v1/plans` | - | - | - | GET POST | GET POST PUT | GET* | GET | - | - | - | - | GET* | AC-04 POST = approve, return. |
| `/api/v1/catalog` | - | - | GET POST PUT* | GET* | GET POST PUT | GET | - | - | - | - | - | GET* | AC-05 ghi khi được giao quản lý danh mục. |
| `/api/v1/products` | - | - | - | - | GET* | GET POST | - | - | - | - | - | - | Khai báo sản phẩm của nhiệm vụ; TL-02 không có SCR riêng. |
| `/api/v1/evidences` | - | - | - | GET | GET POST | GET POST PUT | GET | - | - | GET | - | GET* | AC-05 POST = open-review, accept, request-revision, reject. |
| `/api/v1/appraisals` | - | - | - | GET PUT POST | GET POST | GET PUT POST | GET POST | GET POST | - | GET POST* | - | - | AC-10 POST = recompute (TL-01). AC-04 = phân nhóm triển vọng. |
| `/api/v1/policies` | - | GET* | GET POST PUT | GET* | - | - | GET* | GET POST | - | - | - | - | AC-08 POST = approve (TL-02) hoặc publish (TL-01, `policy_approver`). |
| `/api/v1/dossiers` | - | - | - | GET POST* | GET POST* | GET POST | GET POST | GET POST | - | - | - | - | Góp ý, biên bản, gói; quyền thật theo thành phần hội nghị. |
| `/api/v1/oversight` | - | - | - | - | - | GET POST PUT | GET POST | GET POST | GET POST | GET POST | - | - | AC-06 = khiếu nại của mình. |
| `/api/v1/config` | GET POST PATCH DELETE | GET POST PUT PATCH | GET* PUT | GET POST | GET | GET | GET | GET | GET | GET | - | - | AC-01 = tenants; AC-02 = bước 4, 5, feature-flags, holiday-calendars; AC-03 = anomaly-thresholds (GET suy luận: đọc bản hiện hành trước khi sửa ở SCR-1401, QA-05 23/10/2026); người dùng khác GET lịch ngày làm việc. AC-02, AC-04 POST còn gồm role-bindings, delegations (SCR-0105, SCR-0107). |
| `/api/v1/periods` | - | GET POST | GET* | GET POST | GET | GET | GET | GET | GET POST | GET | - | - | AC-04 POST = approve lệnh; AC-09 POST = duyệt huỷ. AC-03 GET (suy luận, QA-05 23/10/2026): chọn kỳ áp dụng khi gửi phê duyệt bộ quy định (BR-15-10) và kỳ của luồng duyệt, ca thử. |
| `/api/v1/org` | GET* | GET POST PUT | - | GET PUT | GET PUT | GET PUT | GET PUT | GET PUT | GET PUT | GET PUT | - | - | PUT `/org/me/context` cho mọi người dùng; AC-11 đẩy dữ liệu qua SVC-13. |
| `/api/v1/files` | - | - | - | GET | GET POST | GET POST PUT | GET | GET | - | GET | - | - | URL tải xuống ký trước 5 phút theo quyền trên sản phẩm. |
| `/api/v1/ledger` | - | - | - | - | - | - | GET | - | - | GET POST | - | - | AC-10 POST = verify-runs, exports. |
| `/api/v1/esign` | - | - | - | POST GET | - | POST GET | POST GET | POST GET | POST GET* | - | - | - | Chủ thể ký, AAL2. |
| `/api/v1/notifications` | GET POST* | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | - | - | SSE và đánh dấu đã đọc. |
| `/api/v1/analytics` | - | - | - | GET POST PUT | GET POST PUT | - | GET POST | - | - | GET POST | - | - | POST `/analytics/query` là truy vấn đọc. |
| `/api/v1/ai` | - | - | - | GET | GET POST | GET POST | - | - | - | - | - | POST* | AC-12 là danh tính kỹ thuật của SVC-14, không phải người gọi API này. |
| `/api/v1/ui` | GET* | GET POST PUT | GET PUT* | GET | GET | GET | GET | GET | GET | GET | - | - | GET `/ui/packs/current` cho mọi người dùng. |
| `/api/v1/registry` (ngoài danh sách baseline 13) | GET POST | - | - | - | - | - | - | - | - | - | - | - | `/registry/resolve` gọi bởi shell không cần vai trò; chuyển cell cần hai người duyệt. |
| `/api/v1/workflow` (ngoài danh sách baseline 13) | - | GET POST* | GET* | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | GET POST | - | - | Hộp việc cần duyệt; quyền hoàn thành theo người được gán. |
| `/api/v1/jobs` (ngoài danh sách baseline 13) | GET | GET | GET | GET | GET | GET | GET | GET | GET | GET | - | - | Chỉ người khởi tạo tác vụ. |
| `/api/v1/search` (ngoài danh sách baseline 13) | - | - | - | GET | GET | GET | GET | GET | GET | GET | - | - | Kiểm quyền lại ở lõi; không chứa C2, C3. |
<!-- END GEN:api -->

## Ma trận phụ: vai trò theo mức dữ liệu

Bảng dưới cho biết mỗi vai trò được đọc dữ liệu ở mức nào và trong phạm vi nào, theo phân loại C0 đến C3 của ADR-06 và TL-03. Điểm số và mức xếp loại thuộc C1 nhưng chỉ người có thẩm quyền trên đơn vị mới đọc được; nhận xét, giải trình, góp ý kiểm điểm, nội dung khiếu nại, số định danh, ngày sinh thuộc C2; nội dung C3 chỉ người nhận được chỉ định mở được, bất kể vai trò, theo quy tắc `c3_not_recipient` trong ví dụ chính sách của TL-01.

<!-- BEGIN GEN:muc-du-lieu -->
| Vai trò | C0 Công khai | C1 Nội bộ | C2 Nhạy cảm | C3 Hạn chế |
|---|---|---|---|---|
| AC-01 `platform_admin` | R/N | Danh bạ tenant, cell (N); không đọc dữ liệu nghiệp vụ* | - | - |
| AC-02 `tenant_admin` | R/Tn | Cấu hình, tổ chức, kỳ (Tn) | Dữ liệu cá nhân khi đối soát nhân sự (Tn)* | - |
| AC-03 `policy_author` | R/Tn | Policy Pack, danh mục (Tn) | - | - |
| AC-04 `leader` | R/Tn | Cd | Phân nhóm triển vọng (Đv, BR-10-10); minh chứng khi có quyền C2 (Cd) | Chỉ khi là người nhận |
| AC-05 `manager` | R/Tn | Đv | Nhận xét, giải trình của cấp dưới trực tiếp (Đv)* | Chỉ khi là người nhận |
| AC-06 `member` | R/Tn | B, đọc OKR và danh mục của Đv | Hồ sơ, giải trình, khiếu nại của mình (B); góp ý kiểm điểm khi là thành viên (Gn) | Chỉ khi là người nhận |
| AC-07 `appraiser` | R/Tn | Cd | Hồ sơ thẩm định, góp ý, khiếu nại được giao (Cd, ghi nhật ký truy cập) | Chỉ khi là người nhận |
| AC-08 `decider` | R/Tn | Cd | Hồ sơ đến bước quyết định, văn bản trả lời khiếu nại (Cd) | Chỉ khi là người nhận |
| AC-09 `second_approver` | R/Tn | Đối tượng được chỉ định (Gn) | Hồ sơ của yêu cầu mở lại được chỉ định (Gn)* | Chỉ khi là người nhận |
| AC-10 `inspector` | R/Tn | Tn (sổ cái), Cd (cảnh báo) | Kết luận hậu kiểm (Tn) | Chỉ khi là người nhận |
| AC-11 `(client credentials)` | - | Theo hợp đồng tích hợp của client | Dữ liệu nhân sự từ CSDL CBCCVC đi vào (một chiều)* | - |
| AC-12 `ai_readonly` | Theo người dùng | Theo người dùng (token exchange) | Chỉ khi người dùng có quyền, không lưu nhật ký mô hình (BR-17-07) | Không bao giờ (BR-17-07, BR-14-06) |
<!-- END GEN:muc-du-lieu -->

## Ràng buộc tách vai trò, bốn mắt và xác thực bước lên

Bảng dưới gom mọi ràng buộc gắn với chức năng mà giao diện phải biết: tách vai trò theo PL 4 V.1, nguyên tắc bốn mắt (hai người duyệt), xác thực bước lên trước thao tác quan trọng, giới hạn theo mức dữ liệu, ẩn với đối tượng và duyệt thay khi vắng mặt. Cột "Thông báo" ghi mã MSG mà giao diện hiển thị khi máy chủ từ chối; dòng ghi "Chưa có MSG riêng" là chỗ TL-02 chưa cấp mã, giao diện tạm hiển thị thông báo 403 chung và ghi nhận khoảng trống ở [trang 14](14-quyet-dinh-va-cau-hoi-mo.md).

<!-- BEGIN GEN:rang-buoc -->
| STT | Màn hình | Chức năng | Loại | Quy tắc | Nguồn | Kiểm soát ở | Thông báo |
|---|---|---|---|---|---|---|---|
| 1 | SCR-0001 | Chọn phân công | Mức xác thực | Vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin` chỉ dùng trong phiên AAL2; đăng nhập từ thiết bị hoặc vị trí mới phải MFA. | BR-00-01, BR-00-06; SEC-13, SEC-54 | Keycloak, mission-core | MSG-00-05 |
| 2 | SCR-0102 | Kích hoạt tenant | Step-up MFA | Kích hoạt tenant yêu cầu MFA bước lên; mọi bước chạy trong phiên AAL2. | SCR-0102 | Keycloak, mission-core | Chưa có MSG riêng |
| 3 | SCR-0104 | Áp dụng xử lý lệch hàng loạt | Step-up MFA | Áp dụng hàng loạt yêu cầu phiên AAL2. | SCR-0104 Quyền | SVC-04 | MSG-00-05 |
| 4 | SCR-0203 | Phê duyệt OKR | Tách vai trò | Người duyệt không được là người soạn; nút "Phê duyệt" ẩn khi trùng, API trả 403. | BR-02-08; PL 4 V.1 | Giao diện ẩn nút; OPA 403 | MSG-02-11 |
| 5 | SCR-0403 | Duyệt và ký số kế hoạch | Tách vai trò | Người duyệt không được là người lập. | BR-04-08 | OPA 403 | Chưa có MSG riêng |
| 6 | SCR-0403 | Duyệt và ký số kế hoạch | Step-up MFA | MFA trong vòng 15 phút trước thao tác (TL-01: `acr=aal2`, `auth_time` không quá 5 phút). | BR-00-02; TL-01 mục Định danh và xác thực | Máy chủ trả 401 `insufficient_user_authentication` (RFC 9470) | Chưa có MSG riêng |
| 7 | SCR-0501, SCR-0502 | Giao việc | Tách vai trò | Người nghiệm thu khác người chủ trì và người phối hợp. | BR-05-02 | Chặn giao (kiểm tra tức thì và OPA) | MSG-05-02 |
| 8 | SCR-0601 | Duyệt đề nghị gia hạn, điều chỉnh | Tách vai trò | Người duyệt không được là người gửi đề nghị; người duyệt vắng quá 2 ngày làm việc thì tự chuyển người duyệt thay theo `flowable.wf_binding`. | BR-06-08; QT-06 Ngoại lệ | OPA 403; SVC-06 | Chưa có MSG riêng |
| 9 | SCR-0703 | Xác nhận nghiệm thu | Tách vai trò | Người nghiệm thu khác người nộp và người phối hợp; đơn vị quá nhỏ thì Policy Pack cho cấp trên trực tiếp nghiệm thu, luôn ghi vết và hiện trên bảng cảnh báo. | BR-07-06; TL-02 Nguyên tắc tách vai trò | Giao diện ẩn nút; OPA 403; sổ cái | Chưa có MSG riêng |
| 10 | SCR-0703 | Xác nhận nghiệm thu | Step-up MFA | MFA trong vòng 15 phút trước khi xác nhận. | BR-07-11, BR-00-02; TL-01 ví dụ Rego `step_up_required` | OPA `deny step_up_required`; 401 | Chưa có MSG riêng |
| 11 | SCR-0801 | Phê duyệt lệnh và khởi chạy khoá kỳ | Tách vai trò | Lệnh khoá kỳ do AC-04 phê duyệt, khác người lập; người khởi chạy (AC-02) khác người phê duyệt. | BR-08-01; TL-03 CHECK `approved_by <> requested_by` | CSDL CHECK; OPA | Chưa có MSG riêng |
| 12 | SCR-0801 | Khởi chạy, tạm dừng, huỷ saga | Step-up MFA | Yêu cầu MFA trong vòng 15 phút. | BR-08-02 | 401 step-up | Chưa có MSG riêng |
| 13 | SCR-0801 | Huỷ và bù trừ toàn bộ | Bốn mắt | Cần người duyệt thứ hai (AC-09) và chỉ trước khi bước 4 hoàn tất. | BR-08-09 | SVC-06, OPA | MSG-08-05 |
| 14 | SCR-0901 | Ký số và nộp phiếu | Step-up MFA | Chủ hồ sơ nộp tự đánh giá trong phiên AAL2. | TL-01 Phụ lục C (`/appraisals/{id}/self-assessment`) | 401 step-up; esign-service | Chưa có MSG riêng |
| 15 | SCR-0902, SCR-0903, SCR-0904 | Thẩm định, đề xuất, quyết định | Tách vai trò | Với cùng một hồ sơ, người được đánh giá, người nghiệm thu, người thẩm định, người quyết định phải khác nhau. | BR-09-14; baseline mục 10 | OPA khi giao task và khi gọi API ghi | MSG-09-08 |
| 16 | SCR-0904 | Quyết định và ký lô | Step-up MFA | MFA bước lên khi ký lô tới 1.000 hồ sơ. | SCR-0904 Quyền; TL-01 Phụ lục C | 401 step-up | Chưa có MSG riêng |
| 17 | SCR-0902, SCR-1603 | Mở minh chứng, xuất dữ liệu | Mức dữ liệu | Mở minh chứng ghi nhật ký truy cập C2; xuất danh sách không gồm trường C2; xuất dữ liệu C2 cần step-up. | SCR-0902, SCR-1603; TL-01 mục Định danh và xác thực | mission-core ghi nhật ký; OPA | MSG-16-06 |
| 18 | SCR-1002 | Phân nhóm triển vọng | Mức dữ liệu | Kết quả phân nhóm là C2, chỉ `leader` và `appraiser` của đơn vị xem được; không xuất sang kho phân tích ở dạng định danh. | BR-10-10 | OPA; CDC chỉ đẩy số lượng | Chưa có MSG riêng |
| 19 | SCR-1101 | Góp ý kiểm điểm | Mức dữ liệu | Góp ý là C2; nếu tenant bật ẩn danh, người được kiểm điểm thấy nội dung nhưng không thấy người góp ý; chỉ thư ký ghi ý kiến tại hội nghị, chỉ chủ trì kết thúc. | BR-11-06; SCR-1101 Quyền | OPA lọc trường người góp ý | Chưa có MSG riêng |
| 20 | SCR-1102 | Ký số gói và nộp | Step-up MFA | MFA bước lên khi ký; gói đã nộp bị khoá, thay đổi phải qua mở lại kỳ. | SCR-1102; BR-11-10 | 401 step-up; API ghi trả 409 | Chưa có MSG riêng |
| 21 | SCR-1202 | Xử lý khiếu nại | Tách vai trò | Người xử lý không được là người đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ bị khiếu nại; điều chỉnh với kỳ đã khoá chỉ qua mở lại kỳ. | BR-12-04, BR-12-07 | OPA khi giao task và khi gọi API kết luận | MSG-12-05, MSG-12-07 |
| 22 | SCR-1301 | Phê duyệt mở lại kỳ | Bốn mắt | Hai người có thẩm quyền khác nhau cùng phê duyệt, khác người tạo yêu cầu, chưa tham gia thẩm định hoặc quyết định hồ sơ bị mở; phạm vi và thời hạn giới hạn. | BR-13-02, BR-13-03, BR-13-05 | OPA kiểm `approver_1 ≠ approver_2 ≠ requester` | MSG-13-01, MSG-13-04 |
| 23 | SCR-1301 | Phê duyệt mở lại kỳ | Step-up MFA | Mỗi phê duyệt yêu cầu MFA bước lên và ký số. | BR-13-04, BR-00-02 | Keycloak step-up (ACR cao); esign-service | MSG-13-02 |
| 24 | SCR-1401 | Xem và xử lý cảnh báo | Ẩn với đối tượng | Đối tượng của cảnh báo không thấy cảnh báo cho tới khi có kết luận; người xử lý không được là đối tượng. | BR-14-03, BR-14-08 | OPA ẩn `anomaly_alert` | Chưa có MSG riêng |
| 25 | SCR-1503 | Ký số phê duyệt phát hành Policy Pack | Tách vai trò | Người phê duyệt phát hành khác người soạn; ký số trong phiên AAL2. | BR-15-09; TL-01 Phụ lục C | OPA | Chưa có MSG riêng |
| 26 | SCR-1801 | Duyệt và phát hành UI Pack | Tách vai trò | Người duyệt khác người soạn; phát hành trong phiên AAL2. | SCR-1801 Quyền; TL-01 Phụ lục C | OPA (suy luận) | Chưa có MSG riêng |
| 27 | SCR-1601 | Nhắc, giao lại | Quyền của người bấm | Hành động chỉ đạo gọi API của MOD-02 với quyền của người bấm; mọi số liệu lọc theo phạm vi cây tổ chức của người xem. | BR-16-01, BR-16-07 | Cube securityContext; OPA | MSG-16-06 |
| 28 | SCR-0202, SCR-0502, SCR-1701 | Gợi ý AI | AI chỉ đọc | Trợ lý chỉ đọc dữ liệu người dùng đang có quyền đọc, không nhận C3, không lưu C2 trong nhật ký; nội dung chỉ được ghi khi người soạn xác nhận và mang cờ `ai_assisted`. | BR-02-11, BR-17-03, BR-17-07, BR-17-08 | Token exchange ở SVC-14; ACL `doc_chunk` | MSG-17-02, MSG-17-04 |
| 29 | SCR-0105 | Cấp vai trò (`config.role_binding`) | Bốn mắt | Vai trò nhạy cảm `decider`, `second_approver`, `policy_author` cần người duyệt thứ hai khác người cấp; CSDL chặn tự duyệt việc gán vai trò cho chính mình; thay đổi quyền yêu cầu step-up. Vai trò nhạy cảm chưa duyệt không có hiệu lực (BR-01-14). | TL-03 `config.role_binding`; TL-01 mục Định danh và xác thực; SEC-22; WEB-058 BR-01-13, BR-01-14, BR-01-15 | CSDL CHECK `approved_by` khác `granted_by`; OPA | MSG-01-16, MSG-01-17 |
| 30 | SCR-0203, SCR-0601, SCR-0703, SCR-0107 | Duyệt thay khi vắng mặt | Uỷ quyền | Người duyệt hoặc nghiệm thu vắng mặt thì luồng lấy người thay thế (cấp phó được uỷ quyền) từ `flowable.wf_binding` và ghi rõ "duyệt thay", "nghiệm thu thay"; check-in thay ghi rõ "check-in thay". Người nhận uỷ quyền không được uỷ quyền lại (BR-01-17) và vẫn chịu ràng buộc tách vai trò của hồ sơ (BR-01-18). | QT-02, QT-03, QT-06, QT-07 Ngoại lệ và xử lý; WEB-058 | `config.delegation`; OPA; SVC-06; sổ cái | MSG-01-18 |
| 31 | SCR-1505 | Phê duyệt phát hành mẫu luồng | Bốn mắt | Người phê duyệt (`second_approver`) phải khác người soạn (`wf_template_author`); phiên AAL2 và step-up khi ký gói mẫu. | BS-01 SCR-1505 | Repo mẫu luồng (merge request), SVC-06 | Chưa có MSG (cần bổ sung) |
| 32 | SCR-1506 | Chuyển người xử lý, gia hạn bước | Giới hạn thao tác | Chỉ chuyển người xử lý và gia hạn; không sửa biến tiến trình, không nhảy bước; mọi thao tác ghi sổ cái. | BS-01 SCR-1506 | SVC-06, SVC-09 | Chưa có MSG (cần bổ sung) |
<!-- END GEN:rang-buoc -->

## Liên quan

- [Phân quyền](15-phan-quyen.md) (nguyên tắc, vai trò, phạm vi, hiện thực từng tầng)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Quy trình và màn hình QT-00 đến QT-08](05a-quy-trinh-va-man-hinh-qt00-08.md)
- [Quy trình và màn hình QT-09 đến QT-18](05b-quy-trinh-va-man-hinh-qt09-18.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
- Task WEB-054 đến WEB-058 trong [`docs/tasks/README.md`](../docs/tasks/README.md)
