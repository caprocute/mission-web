# Dữ liệu đầu vào của kiểm thử đầu cuối trên hệ thống thật (QA-03)

`he-thong.json` mô tả dữ liệu mà các hành trình trong `frontend/e2e/hanh-trinh/` dựng qua API thật
(APISIX → mission-core) trước khi chạy, bằng chính danh tính của tài khoản thử theo vai trò:

- **Kỳ** (`ky`): dùng kỳ đang mở bao trùm hôm nay; nếu chưa có thì tài khoản quản trị đơn vị tạo kỳ
  tháng hiện tại với mã `E2E-<năm-tháng>`.
- **Nhiệm vụ** (`nhiemVu`): nhiệm vụ đột xuất do người giao (`quanly`) soạn cho người chủ trì (`canbo`)
  rồi giao; tiêu đề bắt đầu bằng tiền tố của lần chạy (`{MA}`, lấy từ `E2E_RUN_ID` hoặc sinh theo thời
  gian) để dọn và để hai lần chạy không đụng nhau.
- **Tiến độ** (`tienDo`): số nấc tăng trên thanh trượt và nội dung "Kết quả đã làm".

Đơn vị, hồ sơ cán bộ và claim tenant của tài khoản thử không tạo ở đây mà lấy từ seed realm và tổ chức
(QA-01). Danh mục công việc không cần cho hành trình mẫu nên chưa dựng. Tệp chỉ chứa dữ liệu thử, không
có họ tên, số định danh hay thông tin cá nhân thật, không chứa địa chỉ máy chủ hay mật khẩu.

## Hành trình kế hoạch, giao việc, tiến độ, minh chứng (QA-05)

`qt04-07.json` là dữ liệu của các hành trình trong `frontend/e2e/hanh-trinh/qt04-07/`, dùng chung cho
chế độ giả lập và hệ thống thật:

- **Kế hoạch** (`keHoach`): dòng việc ngoài danh mục, nội dung sửa sau khi bị trả lại, ý kiến trả lại,
  ý kiến duyệt và lý do điều chỉnh.
- **Giao việc** (`giaoViec`): tên, sản phẩm, minh chứng yêu cầu và tiêu chuẩn chất lượng của nhiệm vụ
  giao từ kế hoạch.
- **Văn bản chỉ đạo** (`vanBan`): trích yếu của văn bản đến dùng để tạo nhiệm vụ.
- **Tiến độ** (`tienDo`): ghi chú cập nhật, nội dung điểm nghẽn và cách xử lý, lý do gia hạn, số ngày
  gia hạn thường và số ngày vượt ngưỡng (đi qua luồng duyệt nhiều bước).
- **Minh chứng** (`minhChung`): tên tệp, ý kiến yêu cầu bổ sung, nhận xét nghiệm thu.
- **Tệp thử mã độc** (`eicar`): chuỗi thử EICAR chuẩn tách ba phần để công cụ quét trên máy làm việc
  không cách ly chính tệp dữ liệu; hành trình ghép lại lúc chạy.

Chạy ở chế độ giả lập: `npx playwright test -c e2e/hanh-trinh/qt04-07/playwright.gia-lap.config.ts`
(sau `npm run e2e:prepare`); trên hệ thống thật các tệp này chạy cùng bộ QA-03 với `E2E_MODE=he-thong`.

## Điều kiện để chạy thật QA-05 và QA-06 trên bộ hạ tầng dev

Rà tĩnh ngày 23/10/2026 (B4) cho thấy lời gọi API của hai bộ hành trình khớp hợp đồng. Mã người dùng Keycloak
của tài khoản thử trùng mã cán bộ của `infra/dev/postgres/seed/org-du-lieu-thu.sql`, nên kịch bản dùng `sub`
làm mã người giao và mã người chủ trì là đúng. Trước khi chạy, môi trường cần đủ các điều kiện sau. Nếu thiếu,
hành trình sẽ hỏng hoặc ghi "chờ" dù kịch bản đúng:

- **Bản sao tổ chức đã được phát lại.** Nạp `org-du-lieu-thu.sql` bằng SQL không phát sự kiện, nên
  `org_replica` của mission-core và workflow-service vẫn rỗng. Sau khi nạp, chạy `org-republish` của
  org-service cho tenant thử (hồ sơ Spring `republish`, xem `ops/runbooks/org-republish.md`).
- **Minh chứng được quét mã độc.** evidence-service cần clamd và `EVIDENCE_SCAN_ENABLED=true`. Nếu không có,
  tệp tải lên mãi chờ quét, HT-20 và HT-21 không qua được.
- **Hồ sơ đánh giá mở được phiếu tự đánh giá (QA-06).** Cần đủ bốn điều kiện:
  - kỳ quý dài đúng ba tháng đã khoá;
  - cán bộ thử là đảng viên, vì luồng Nhà nước còn giữ trạng thái chưa lập theo wiki trang 14 mục 59(e);
  - có Policy Pack đã phát hành cho luồng Đảng và khối đối tượng của đơn vị thử;
  - scoring-service đang chạy.
- **Biến môi trường.** `E2E_KC_URL` phải trùng từng ký tự với `KEYCLOAK_PUBLIC_URL` của hạ tầng, nếu không
  máy chủ từ chối token vì `iss` khác. Bước kiểm sự kiện cần `E2E_KAFKA_UI_URL`; compose dev hiện chưa có
  kafka-ui.
