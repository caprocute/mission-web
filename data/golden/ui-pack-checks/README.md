# Ca kiểm tra UI Pack dùng chung

`tuong-phan.json` là bộ ca tương phản WCAG 2.1 mà bộ kiểm UI Pack của mission-core (CORE-08-10, `WcagContrastGoldenTest`) và công cụ kiểm tương phản phía web (WEB-004) cùng chạy, để hai bên cho cùng kết quả. Mỗi ca ghi màu chữ, màu nền, tỷ lệ mong đợi (làm tròn 3 chữ số), kết luận ở ngưỡng AA 4,5 : 1 và ở ngưỡng 7 : 1 của chế độ Đảng viên cao tuổi. Tỷ lệ không làm tròn lên trước khi so ngưỡng, nên 4,478 : 1 vẫn không đạt. Thêm ca mới thì tính tỷ lệ bằng công thức WCAG rồi chạy kiểm thử của cả hai phía.
