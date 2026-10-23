# Kịch bản kiểm thử chấp nhận: một kỳ đánh giá trọn vẹn tại Sở Thử nghiệm

> Nguồn: task QA-16; TL-02a, TL-02b (QT-00 đến QT-18); `wiki/05a`, `wiki/05b`; `wiki/15a-ma-tran-phan-quyen.md`; danh mục hành trình `frontend/e2e/hanh-trinh/danh-muc.json`. Phần dành cho người dùng (mục 1 đến 18) viết bằng ngôn ngữ nghiệp vụ; ánh xạ sang hành trình kiểm thử tự động nằm ở phụ lục cuối tệp, dành cho tổ kỹ thuật.

## Cách đọc và ghi kết quả

Các kịch bản xếp theo đúng thứ tự công việc của một kỳ đánh giá quý, nên kịch bản sau dùng dữ liệu do kịch bản trước tạo ra. Mỗi kịch bản ghi người thực hiện, những gì cần có trước, các bước làm và kết quả mong đợi; phần "Thử thêm" là các trường hợp hệ thống phải từ chối hoặc cảnh báo, người thực hiện làm sau khi đường chính đã xong. Số liệu cần nhập (số lượng sản phẩm, mức chất lượng, điều kiện hồ sơ) lấy từ `du-lieu-mau/ke-hoach-okr-nhiem-vu.yaml`; tài khoản và người đại diện theo `tham-so.yaml`.

Sau mỗi kịch bản, người thực hiện đánh dấu một trong ba kết quả vào biên bản: **Đạt** (mọi kết quả mong đợi đều đúng), **Đạt có ghi chú** (đúng nhưng có góp ý hoặc lỗi nhỏ đã ghi phiếu), **Không đạt** (có kết quả mong đợi sai; ghi số phiếu lỗi). Người thực hiện không tự sửa dữ liệu bằng cách khác để đi tiếp; nếu bị chặn, báo trưởng tổ.

Kịch bản đánh dấu "tuỳ chọn" chỉ chạy khi chức năng tương ứng đã bật cho tenant; không chạy thì ghi "Chưa kiểm" chứ không ghi "Đạt".

---

## Kịch bản 01. Chuẩn bị tenant, đơn vị và kỳ đánh giá

**Người thực hiện:** Quản trị nền tảng (Tạ Văn Nền), Quản trị tenant (Hoàng Văn Quản Trị).

**Cần có trước:** Tenant Sở Thử nghiệm chưa ở trạng thái đang sử dụng, hoặc đã cấp phát sẵn theo quy trình đưa tenant vào sử dụng.

**Các bước.**
1. Quản trị nền tảng mở danh sách tenant, kiểm tenant Sở Thử nghiệm đã được cấp phát trên đúng cụm máy chủ ghi trong tham số, đã gắn bộ quy định đánh giá gốc và giao diện mặc định, rồi kích hoạt tenant.
2. Quản trị tenant mở cây đơn vị, kiểm có Sở Thử nghiệm và ba đơn vị trực thuộc: Phòng Tổng hợp, Ban Tổ chức, Ủy ban Kiểm tra.
3. Quản trị tenant kiểm phân công của mười người đại diện đúng đơn vị và chức danh trong tệp tài khoản; ghi một thay đổi thử: điều chuyển một cán bộ hư cấu bổ sung sang Ban Tổ chức có hiệu lực từ ngày mai, rồi huỷ thay đổi đó.
4. Quản trị tenant chạy đồng bộ nhân sự và xem báo cáo đối soát.
5. Quản trị tenant tạo kỳ "Quý IV năm 2026 (kỳ rút gọn để diễn tập)" với ngày bắt đầu, ngày kết thúc và các mốc theo tham số, rồi tạo thêm một kỳ phụ cùng tên kèm chữ "diễn tập huỷ khoá kỳ" để dùng ở kịch bản 11.

**Kết quả mong đợi.** Tenant ở trạng thái đang sử dụng; cây đơn vị và phân công khớp tệp tài khoản; thay đổi có hiệu lực từ ngày mai không làm đổi phân công hôm nay; báo cáo đối soát không có sai lệch chưa xử lý; hai kỳ hiện trong danh sách kỳ ở trạng thái đang mở, có lịch mốc đúng.

**Thử thêm.** Quản trị tenant không mở được danh sách tenant của nền tảng. Hai người cùng sửa một đơn vị: người lưu sau được báo dữ liệu đã thay đổi và được mời tải lại, không ghi đè mất thay đổi của người trước.

## Kịch bản 02. Đăng nhập, chọn vai trò và xác thực bậc hai

**Người thực hiện:** Cả mười người đại diện, mỗi người một lượt.

**Các bước.**
1. Mở trang đăng nhập của tenant, đăng nhập bằng tài khoản cơ quan.
2. Người có nhiều phân công (ví dụ Lê Văn Lãnh Đạo vừa là lãnh đạo vừa là cán bộ) chọn đơn vị và vai trò làm việc.
3. Người giữ vai trò cần xác thực bậc hai (thẩm định, quyết định, duyệt thứ hai, quản trị) nhập mã OTP từ ứng dụng tạo mã.
4. Mở trang "Việc của tôi hôm nay".

**Kết quả mong đợi.** Mỗi người vào được trang chủ với đúng tên, đơn vị và vai trò; trình đơn chỉ hiện các chức năng của vai trò đang chọn; trang "Việc của tôi hôm nay" mở trong vòng vài giây.

**Thử thêm.** Nhập sai mật khẩu năm lần thì tài khoản bị khoá tạm và có thông báo rõ ràng. Chọn một đơn vị không thuộc phân công của mình bị từ chối. Bấm phê duyệt một việc nhạy cảm khi đã quá thời gian xác thực lại thì hệ thống yêu cầu nhập lại mã OTP trước khi cho làm tiếp.

## Kịch bản 03. Ghim bộ quy định đánh giá và tham số luồng duyệt cho kỳ

**Người thực hiện:** Quản trị nghiệp vụ (Đỗ Thị Chính Sách), Cấp quyết định (Vũ Văn Quyết Định).

**Các bước.**
1. Quản trị nghiệp vụ mở bộ quy định đánh giá của luồng Đảng và luồng Nhà nước được ghim cho kỳ, xem bộ ca mẫu và chạy toàn bộ ca mẫu.
2. Mở cấu hình luồng duyệt của kế hoạch quý, chọn mẫu luồng hai cấp, chạy mô phỏng với một kế hoạch thử của Phòng Tổng hợp và xem danh sách người duyệt dự kiến.
3. Lưu tham số luồng và gửi phát hành; cấp quyết định duyệt phát hành.

**Kết quả mong đợi.** Bộ ca mẫu đạt 100%; mô phỏng chỉ ra người duyệt là trưởng phòng rồi lãnh đạo Sở; tham số luồng có hiệu lực cho kỳ sau khi được duyệt.

**Thử thêm.** Sửa một ngưỡng trong bộ quy định làm một ca mẫu không đạt thì không gửi duyệt được. Người soạn không tự phê duyệt phát hành của chính mình.

## Kịch bản 04. Lập và phê duyệt kế hoạch quý

**Người thực hiện:** Cán bộ quản lý (Trần Thị Quản Lý), Lãnh đạo (Lê Văn Lãnh Đạo).

**Cần có trước:** Kỳ đang mở; danh mục công việc mẫu của tenant.

**Các bước.**
1. Trưởng phòng lập kế hoạch quý của Phòng Tổng hợp từ danh mục công việc, đưa vào các nhiệm vụ NV05 đến NV08 và NV11 trong dữ liệu mẫu (thiếu NV12 có chủ ý), rồi trình duyệt.
2. Lãnh đạo mở kế hoạch, trả lại với lý do "Bổ sung nhiệm vụ then chốt về số hoá hồ sơ lưu trữ".
3. Trưởng phòng bổ sung NV12, trình lại; lãnh đạo phê duyệt có ký số.
4. Lãnh đạo lập kế hoạch quý của Sở cho các nhiệm vụ do lãnh đạo giao trực tiếp (NV01 đến NV04, NV09, NV10, NV13, NV14) và phê duyệt theo phân cấp của tenant.

**Kết quả mong đợi.** Kế hoạch có lịch sử hai lần trình, một lần trả lại kèm lý do; phiên bản được duyệt có đủ sáu nhiệm vụ của phòng; người trình nhận thông báo khi bị trả lại và khi được duyệt.

**Thử thêm.** Trình duyệt một kế hoạch còn lỗi kiểm tra (ví dụ nhiệm vụ không có hạn hoàn thành) bị chặn và chỉ rõ dòng lỗi. Điều chỉnh kế hoạch đã duyệt tạo phiên bản mới, phiên bản cũ vẫn xem được.

## Kịch bản 05. Lập và phê duyệt mục tiêu, kết quả then chốt

**Người thực hiện:** Cán bộ (Nguyễn Văn Cán Bộ), Cán bộ quản lý (Trần Thị Quản Lý), Lãnh đạo.

**Các bước.**
1. Trưởng phòng lập mục tiêu MT1 của phòng với hai kết quả then chốt KR1.1 và KR1.2, gửi lãnh đạo duyệt; lãnh đạo phê duyệt.
2. Cán bộ lập mục tiêu MT2 với kết quả then chốt KR2.1 (số hoá 200 hồ sơ), gửi trưởng phòng duyệt.
3. Trưởng phòng trả lại với yêu cầu ghi rõ người xác nhận kết quả; cán bộ sửa, gửi lại; trưởng phòng phê duyệt.

**Kết quả mong đợi.** Hai mục tiêu ở trạng thái đã duyệt, mỗi kết quả then chốt có chỉ tiêu, đơn vị tính và người xác nhận đúng dữ liệu mẫu; mục tiêu của cán bộ liên kết với mục tiêu của phòng.

**Thử thêm.** Một cán bộ không có quyền phê duyệt bấm phê duyệt mục tiêu của đồng nghiệp thì bị từ chối.

## Kịch bản 06. Giao việc và nhận việc

**Người thực hiện:** Cán bộ quản lý, Lãnh đạo, Cán bộ và những người được giao việc trong dữ liệu mẫu.

**Các bước.**
1. Từ kế hoạch đã duyệt, trưởng phòng giao NV11 và NV12 cho Nguyễn Văn Cán Bộ; lãnh đạo giao các nhiệm vụ còn lại cho người chủ trì như dữ liệu mẫu, mỗi nhiệm vụ kế hoạch 10 sản phẩm và hạn hoàn thành là ngày D5.
2. Trưởng phòng tạo thêm một nhiệm vụ từ văn bản chỉ đạo đến (văn bản thử của hệ thống văn bản giả lập), giao cho chính mình, rồi huỷ nhiệm vụ đó vì trùng NV06.
3. Mỗi người chủ trì mở "Việc của tôi", xem nhiệm vụ mới và nhận việc.
4. Cán bộ đăng ký thêm một nhiệm vụ tự đề xuất; trưởng phòng từ chối với lý do "đã có trong kế hoạch".

**Kết quả mong đợi.** Mười bốn nhiệm vụ ở trạng thái đang thực hiện, đúng người chủ trì, người giao, nhóm thường xuyên hoặc then chốt và kế hoạch; nhiệm vụ từ văn bản đến giữ liên kết tới văn bản; người được giao nhận thông báo.

**Thử thêm.** Giao việc cho một người ngoài đơn vị mình quản lý bị từ chối.

## Kịch bản 07. Cập nhật tiến độ, check-in kết quả then chốt, điểm nghẽn và gia hạn

**Người thực hiện:** Cán bộ và những người chủ trì; Cán bộ quản lý.

**Các bước.**
1. Từ trang chủ, mỗi người chủ trì cập nhật tiến độ nhiệm vụ của mình (ví dụ 40% ở D3, 80% ở D4); đếm số chạm từ trang chủ tới khi lưu xong.
2. Cán bộ check-in kết quả then chốt KR2.1 (ví dụ 120/200 hồ sơ ở D4) và bấm gửi hai lần liên tiếp do mạng chậm.
3. Cán bộ báo điểm nghẽn ở NV12 "Thiếu máy quét tài liệu khổ A3"; trưởng phòng ghi hướng xử lý và báo cáo lên lãnh đạo.
4. Cán bộ đề nghị gia hạn NV12 thêm hai ngày; trưởng phòng từ chối với lý do "đã bố trí máy quét", để giữ dữ liệu mẫu đúng hạn.

**Kết quả mong đợi.** Cập nhật tiến độ và check-in làm được trong không quá ba chạm từ trang chủ; gửi hai lần chỉ tạo một lần check-in; điểm nghẽn hiện ở trang của trưởng phòng và lãnh đạo; đề nghị gia hạn có kết quả và lý do, hạn nhiệm vụ không đổi.

**Thử thêm.** Hai thiết bị cùng cập nhật một nhiệm vụ: thiết bị lưu sau được báo dữ liệu đã thay đổi. Đề nghị gia hạn vượt ngưỡng cho phép đi qua luồng duyệt nhiều bước.

## Kịch bản 08. Nộp minh chứng

**Người thực hiện:** Mỗi người chủ trì nhiệm vụ.

**Các bước.**
1. Ở D5, mỗi người chủ trì mở nhiệm vụ, tải lên tệp minh chứng (tệp PDF thử, nội dung hư cấu) cho sản phẩm của nhiệm vụ, chờ hệ thống quét xong, khai số lượng sản phẩm đã làm như cột "so_luong_nghiem_thu" của dữ liệu mẫu, xác nhận cam kết và nộp.
2. Với một nhiệm vụ, người chủ trì chọn văn bản đã ban hành từ hệ thống văn bản giả lập làm minh chứng thay cho tải tệp.

**Kết quả mong đợi.** Mỗi nhiệm vụ có minh chứng đã nộp và chuyển sang chờ nghiệm thu; người giao nhận thông báo; minh chứng hiện trong "Minh chứng của tôi".

**Thử thêm.** Tải lên tệp thử mã độc (tệp kiểm thử chuẩn của phần mềm diệt virus) thì tệp bị đánh dấu nhiễm và không nộp được. Nộp lại cùng một tệp cho một nhiệm vụ khác thì hệ thống cảnh báo nghi trùng và yêu cầu giải trình.

## Kịch bản 09. Nghiệm thu minh chứng

**Người thực hiện:** Người giao việc (Trần Thị Quản Lý cho NV11, NV12; Lê Văn Lãnh Đạo cho các nhiệm vụ còn lại).

**Các bước.**
1. Người giao mở hàng chờ nghiệm thu, mở minh chứng NV12 lần đầu, yêu cầu bổ sung "Bổ sung danh sách hồ sơ đã số hoá"; cán bộ nộp lại phiên bản hai.
2. Người giao nghiệm thu từng minh chứng: chọn số lượng sản phẩm được xác nhận theo cột "so_luong_nghiem_thu" và mức chất lượng theo cột "chat_luong" của dữ liệu mẫu (1 là mức cao nhất, 0,75 là mức kế tiếp, 0,5 là mức trung bình theo thang của bộ quy định), xác thực lại bằng mã OTP khi được hỏi, rồi xác nhận nghiệm thu.

**Kết quả mong đợi.** Mười bốn nhiệm vụ hoàn thành; minh chứng NV12 có hai phiên bản, phiên bản một ở trạng thái yêu cầu bổ sung; số lượng và mức chất lượng lưu đúng dữ liệu mẫu; người chủ trì nhận thông báo kết quả nghiệm thu.

**Thử thêm.** Người tự khởi tạo nhiệm vụ không tự nghiệm thu được minh chứng của nhiệm vụ đó. Xác nhận nghiệm thu khi chưa xác thực bậc hai bị chặn.

## Kịch bản 10. Theo dõi trên bảng điều hành

**Người thực hiện:** Lãnh đạo, Cán bộ quản lý.

**Các bước.** Ở D6, lãnh đạo mở bảng điều hành của Sở, xem tỷ lệ hoàn thành nhiệm vụ theo đơn vị, chọn Phòng Tổng hợp, chọn nhiệm vụ NV12 và mở tới minh chứng đã nghiệm thu. Trưởng phòng mở bảng điều hành của phòng và lưu một bộ lọc riêng.

**Kết quả mong đợi.** Số liệu trên bảng điều hành khớp số nhiệm vụ và trạng thái trong kỳ; đi xuống từ tổng hợp tới minh chứng không quá ba lần chọn; bộ lọc đã lưu mở lại được.

## Kịch bản 11. Khoá kỳ

**Người thực hiện:** Quản trị tenant, Lãnh đạo, Người duyệt thứ hai (Bùi Thị Duyệt Hai), Cơ quan kiểm tra (theo dõi).

**Phần A, diễn tập huỷ trên kỳ phụ.**
1. Quản trị tenant lập lệnh khoá kỳ phụ và trình; lãnh đạo phê duyệt; quản trị tenant khởi chạy.
2. Khi tiến trình đang ở bước tính điểm, quản trị tenant đề nghị huỷ; người duyệt thứ hai duyệt huỷ.

Kết quả mong đợi của phần A: tiến trình dừng, các bước đã làm được hoàn tác, kỳ phụ trở về đang mở; lịch sử ghi hai người đề nghị và duyệt huỷ.

**Phần B, khoá kỳ chính.**
1. Ở D7, quản trị tenant kiểm danh sách tiền điều kiện (mọi minh chứng đã nghiệm thu), lập lệnh khoá kỳ chính và trình; lãnh đạo phê duyệt; quản trị tenant khởi chạy.
2. Mọi người theo dõi tiến trình qua sáu bước tới khi hoàn tất; cơ quan kiểm tra mở xem tiến trình ở chế độ chỉ đọc.

Kết quả mong đợi của phần B: kỳ chính ở trạng thái đã khoá; năm hồ sơ đánh giá có điểm tính chính thức; biểu mẫu và bảng chấm điểm được sinh và ký số lô; sau khi khoá, không ai sửa được tiến độ hay minh chứng của kỳ.

**Thử thêm.** Người lập lệnh không tự phê duyệt lệnh của mình. Sau khi bước ký số lô đã xong, màn hình chỉ cho thử lại bước lỗi, không cho huỷ.

## Kịch bản 12. Tự đánh giá

**Người thực hiện:** Năm cán bộ được đánh giá (Ngô Văn Kiểm Tra, Trần Thị Quản Lý, Phạm Thị Thẩm Định, Nguyễn Văn Cán Bộ, Lê Văn Lãnh Đạo).

**Các bước.**
1. Mỗi người mở phiếu tự đánh giá của kỳ, xem điểm tạm tính và kết quả từng nhiệm vụ, ghi nội dung tự nhận xét, chọn mức tự xếp loại, lưu nháp, rồi ký số và nộp.
2. Người thẩm định trả lại phiếu của Nguyễn Văn Cán Bộ vì "Bổ sung nhận xét về nhiệm vụ số hoá hồ sơ"; cán bộ sửa và nộp lại.

**Kết quả mong đợi.** Điểm tạm tính trên phiếu của từng người khớp cột "diem" của `du-lieu-mau/ket-qua-mong-doi.yaml`; nội dung tự nhận xét lưu được và mở lại đúng; phiếu bị trả lại có lý do; năm phiếu ở trạng thái chờ thẩm định.

## Kịch bản 13. Thẩm định và đề xuất mức xếp loại

**Người thực hiện:** Người thẩm định (Phạm Thị Thẩm Định; với hồ sơ của chính người thẩm định, người xử lý theo phân cấp của tenant).

**Các bước.** Người thẩm định mở từng hồ sơ, xem kết quả nhiệm vụ, minh chứng và phiếu tự đánh giá, xác nhận điều kiện hồ sơ như dữ liệu mẫu (tỷ lệ đáp ứng tiêu chí chung, giải pháp đột phá, điều kiện của người đứng đầu), ghi nhận xét và đề xuất mức xếp loại.

**Kết quả mong đợi.** Mức đề xuất của hệ thống khớp mức xếp loại cuối (`muc_cuoi`) trong `du-lieu-mau/ket-qua-mong-doi.yaml`; hồ sơ Trần Thị Quản Lý hiện câu giải thích vì sao đủ điểm mức xuất sắc nhưng chỉ đạt mức hoàn thành tốt (tỷ lệ nhiệm vụ vượt kế hoạch 25%, chưa đạt 30%); hồ sơ Phạm Thị Thẩm Định hiện tiêu chí chung mức Đảm bảo; năm hồ sơ chuyển sang chờ quyết định.

**Thử thêm.** Người thẩm định không mở được chức năng thẩm định trên hồ sơ của chính mình.

## Kịch bản 14. Quyết định xếp loại hàng loạt có ký số

**Người thực hiện:** Cấp quyết định (Vũ Văn Quyết Định); năm cán bộ xem kết quả.

**Các bước.**
1. Cấp quyết định mở danh sách hồ sơ chờ quyết định, chọn cả năm hồ sơ, xem lại mức đề xuất, quyết định và ký số lô.
2. Mỗi cán bộ mở phiếu xếp loại của mình, đọc kết quả và câu giải thích.

**Kết quả mong đợi.** Năm hồ sơ ở trạng thái đã ký số; mức xếp loại trên phiếu của từng người khớp dữ liệu mẫu; câu giải thích dùng ngôn ngữ nghiệp vụ, nêu điều khoản căn cứ và phiên bản bộ quy định; cán bộ nhận thông báo kết quả.

**Thử thêm.** Người đã thẩm định một hồ sơ không được quyết định chính hồ sơ đó.

## Kịch bản 15. Khiếu nại kết quả xếp loại

**Người thực hiện:** Cán bộ (Nguyễn Văn Cán Bộ), Cơ quan kiểm tra (Ngô Văn Kiểm Tra), Người thẩm định, Cấp quyết định.

**Các bước.**
1. Cán bộ gửi khiếu nại với nội dung trong dữ liệu mẫu, đính kèm một tệp giải trình.
2. Cơ quan kiểm tra tiếp nhận, yêu cầu người thẩm định giải trình; người thẩm định gửi giải trình.
3. Cơ quan kiểm tra kết luận "giữ nguyên kết quả"; cấp quyết định ký số văn bản trả lời.
4. Cán bộ mở và đọc văn bản trả lời.

**Kết quả mong đợi.** Hồ sơ của cán bộ hiện đang giải quyết khiếu nại trong suốt quá trình và trở về đã ký số khi có văn bản trả lời; mức xếp loại không đổi; mọi bước có thời điểm và người thực hiện trong lịch sử khiếu nại.

**Thử thêm.** Gửi khiếu nại sau thời hạn cho phép bị từ chối với thông báo rõ thời hạn. Người bị khiếu nại không được xử lý khiếu nại liên quan tới chính mình.

## Kịch bản 16. Hội nghị kiểm điểm và gói hồ sơ số

**Người thực hiện:** Lãnh đạo, Người thẩm định, Cấp quyết định, Cán bộ.

**Các bước.**
1. Lãnh đạo mở hội nghị kiểm điểm của Phòng Tổng hợp, ghi ý kiến cho từng cán bộ, kết thúc hội nghị và lập biên bản.
2. Người thẩm định lập gói hồ sơ số của kỳ cho năm cán bộ, kiểm danh mục tài liệu, gửi ký; cấp quyết định ký số gói; người thẩm định xuất gói.

**Kết quả mong đợi.** Biên bản hội nghị có đủ ý kiến và người dự; gói hồ sơ số có đủ phiếu tự đánh giá, phiếu xếp loại, biên bản và minh chứng tham chiếu; chữ ký số trên gói kiểm tra được là hợp lệ.

## Kịch bản 17. Kiểm tra toàn vẹn, cảnh báo và mở lại kỳ

**Người thực hiện:** Cơ quan kiểm tra (Ngô Văn Kiểm Tra), Cấp quyết định, Người duyệt thứ hai.

**Các bước.**
1. Cơ quan kiểm tra mở sổ cái của kỳ, tra một vài bản ghi (kết quả nghiệm thu NV12, quyết định xếp loại của Nguyễn Văn Cán Bộ), rồi chạy kiểm tra toàn vẹn cho cả kỳ.
2. Cơ quan kiểm tra mở danh sách cảnh báo bất thường của kỳ (nếu có), giao kiểm tra và ghi kết luận.
3. Sau khi trưởng tổ UAT đã ghi xong số liệu nghiệm thu vào biên bản: cơ quan kiểm tra đề nghị mở lại kỳ để hậu kiểm, xem mô phỏng tác động; cấp quyết định duyệt lượt một, người duyệt thứ hai duyệt lượt hai.

**Kết quả mong đợi.** Kiểm tra toàn vẹn báo hợp lệ cho cả kỳ, kèm số bản ghi đã kiểm và thời điểm; mỗi bản ghi tra cứu có thời điểm, người thực hiện và dấu niêm phong; cảnh báo có kết luận; yêu cầu mở lại kỳ chỉ có hiệu lực sau đủ hai người duyệt khác nhau.

**Thử thêm.** Cùng một người duyệt cả hai lượt bị từ chối. Duyệt khi chưa xác thực bậc hai bị yêu cầu xác thực lại.

## Kịch bản 18. Chế độ Đảng viên cao tuổi

**Người thực hiện:** Cán bộ (Nguyễn Văn Cán Bộ); có thể mời thêm một người dùng thử lớn tuổi.

**Các bước.** Từ D3, cán bộ bật chế độ Đảng viên cao tuổi trong phần cài đặt cá nhân và làm các việc thường ngày trong chế độ này: xem việc của tôi, cập nhật tiến độ, check-in kết quả then chốt, nộp minh chứng, tự đánh giá, đọc phiếu xếp loại.

**Kết quả mong đợi.** Chữ lớn và tương phản cao trên mọi màn hình kể trên; các thao tác thường ngày vẫn không quá ba chạm; không có chức năng nào bị che hoặc tràn khung; người dùng tự làm được mà không cần hướng dẫn. Người dùng ghi nhận xét vào checklist của vai trò cán bộ.

## Kịch bản tuỳ chọn

- **Trợ lý AI gợi ý kết quả then chốt** (khi bật trợ lý AI): ở kịch bản 05, cán bộ xin gợi ý kết quả then chốt, chấp nhận một gợi ý và bỏ qua một gợi ý. Hệ thống không tự ghi dữ liệu khi người dùng chưa quyết định.
- **Di động** (khi có ứng dụng di động cho tenant): ở kịch bản 07 và 08, cán bộ check-in kết quả then chốt, cập nhật tiến độ và chụp văn bản giấy để nộp minh chứng trên điện thoại.
- **Giao diện và biểu mẫu riêng của tenant** (khi tenant có UI Pack riêng): quản trị tenant soạn, xem trước và phát hành; người soạn không tự phát hành phiên bản của mình.

---

## Phụ lục: ánh xạ kịch bản với hành trình kiểm thử tự động (dành cho tổ kỹ thuật)

Mỗi kịch bản UAT ánh xạ tới các hành trình đầu cuối cùng nội dung trong danh mục của QA-01. Danh mục `frontend/e2e/hanh-trinh/danh-muc.json` có trường `kichBanUat` ghi ngược lại kịch bản của từng hành trình. Trước buổi UAT, các hành trình đã viết phải chạy xanh trên staging (`E2E_MODE=he-thong npm run e2e:he-thong`); hành trình chưa viết thì kịch bản UAT là lần kiểm duy nhất, và biên bản ghi rõ điều đó.

| Kịch bản | Hành trình | Quy trình |
|---|---|---|
| UAT-01 | HT-05, HT-06, HT-07 | QT-01 |
| UAT-02 | HT-01, HT-02, HT-03 | QT-00 |
| UAT-03 | HT-37, HT-38 | QT-15 |
| UAT-04 | HT-11, HT-12 | QT-04 |
| UAT-05 | HT-08 (tuỳ chọn HT-40) | QT-02, QT-17 |
| UAT-06 | HT-13, HT-14, HT-15 | QT-05 |
| UAT-07 | HT-09, HT-17, HT-18, HT-19 (tuỳ chọn HT-10, HT-16) | QT-03, QT-05, QT-06 |
| UAT-08 | HT-20 (tuỳ chọn HT-22) | QT-07 |
| UAT-09 | HT-21 | QT-07 |
| UAT-10 | HT-39 | QT-16 |
| UAT-11 | HT-23, HT-24 | QT-08 |
| UAT-12 | HT-25 | QT-09 |
| UAT-13 | HT-26 | QT-09 |
| UAT-14 | HT-27 | QT-09 |
| UAT-15 | HT-32, HT-33 | QT-12 |
| UAT-16 | HT-30, HT-31 | QT-11 |
| UAT-17 | HT-34, HT-35, HT-36 | QT-13, QT-14 |
| UAT-18 | HT-42 và cờ chế độ cao tuổi của các hành trình trên | QT-18 |
| Tuỳ chọn | HT-41 | QT-18 |

Hành trình không có kịch bản UAT trong kỳ thí điểm đầu: HT-04 (đăng nhập VNeID, đang tạm hoãn), HT-28 và HT-29 (xếp loại năm và phân nhóm triển vọng, cần đủ bốn quý).
