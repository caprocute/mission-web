# Mở web Mission trên máy chủ dev

Gói này mở ứng dụng web Mission đang chạy trên máy chủ dev về trình duyệt trên máy của bạn bằng một lệnh. Toàn bộ gói nằm trong thư mục này; có thể chép riêng thư mục sang máy khác (macOS hoặc Linux) mà không cần mã nguồn của dự án.

## Chuẩn bị (một lần)

1. Máy cần có `ssh` và `bash` (macOS và Linux đều có sẵn). Nếu đăng nhập máy chủ bằng mật khẩu thì cài thêm `sshpass` (macOS: `brew install sshpass`, Ubuntu: `sudo apt install sshpass`).
2. Chép `cau-hinh.env.example` thành `cau-hinh.env` trong cùng thư mục rồi điền địa chỉ máy chủ, cổng SSH, tên đăng nhập và khoá SSH hoặc mật khẩu. Người phụ trách dự án cung cấp các thông tin này; không đưa tệp đã điền lên kho mã hay kênh công khai.
3. Cấp quyền chạy: `chmod +x mo-web.sh`.

## Sử dụng

```bash
./mo-web.sh             # mở đường hầm và trình duyệt tại http://127.0.0.1:35000
./mo-web.sh trang-thai  # xem các cổng đã thông chưa
./mo-web.sh dung        # đóng đường hầm
```

Đường hầm chạy nền và tự nối lại sau khoảng 5 giây nếu mạng chập chờn. Cổng được mở về máy bạn: 35000 (web), 35080 (đăng nhập), 35900 (tải tệp), 35088 (báo cáo nhúng); các cổng này phải còn trống trên máy. Nếu không mở được, xem nhật ký ở `/tmp/mission-mo-web.log` và `/tmp/mission-mo-web-ssh.log`.
