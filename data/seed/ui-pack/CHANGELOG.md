# Nhật ký thay đổi UI Pack mặc định

UI Pack mặc định nạp cho tenant mới (DL-06, QT-18). Mỗi phiên bản là một tệp `mac-dinh-<phiên bản>.json` theo thân `UiPackVersionUpdate` cộng trường `version`; tệp cũ giữ nguyên để tenant quay lui được.

## 1.0.0 (22/10/2026)

Phiên bản đầu, dùng registry thành phần 2026.3.

- Token màu theo nhận diện Mission: màu nhấn #EE0033, `primary-strong` #D1002D cho chữ màu nhấn và nền nút có chữ trắng, chữ #0F172A, chữ phụ #475569 trên nền #F1F5F9. Chữ thân 14px, bo góc thẻ 12px, cỡ chạm tối thiểu 40px.
- Chế độ Đảng viên cao tuổi (`tokens.$extensions.modes.elderly`): `primary-strong` #A30023, chữ #000000, chữ phụ #334155, chữ thân 18px, viền 2px, cỡ chạm 48px; mọi cặp chữ trên nền đạt từ 7 : 1.
- Bố cục trang chủ: lưới gồm "Việc của tôi hôm nay" (7 việc, bắt buộc hiện), "Tiến độ OKR của tôi", "Tiến độ OKR của đơn vị" (cho lãnh đạo, quản lý) và "Việc chờ tôi duyệt" (cho quản lý). Widget thông báo chưa đưa vào vì UI Pack phía máy chủ chưa có khối i18n cho khoá văn bản.
- Kích thước tài liệu UI Pack 1.0 nén gzip: **820 byte** (khoảng 0,8 KB trên ngân sách 30 KB, BR-18-08), đo bằng `kiem_ui_pack.py`.
