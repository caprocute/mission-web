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
