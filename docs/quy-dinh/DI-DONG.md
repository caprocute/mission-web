# Quy định riêng cho ứng dụng di động

> Tệp này bổ sung `AGENTS.md` cho mọi task có mã `MOB-`. Nguồn: TL-01 (kênh di động, SVC-17/SVC-18 phía khách), TL-02 (các màn hình dùng chung web và di động), TL-04 (SEC về thiết bị di động).

Ứng dụng di động viết bằng Flutter (bản ổn định mới nhất tại thời điểm làm task, ghi trong `mobile/pubspec.yaml`), đặt trong thư mục `mobile/`, gọi đúng các API trong `contracts/openapi/` qua lớp client sinh từ hợp đồng, không tự đặt đường dẫn. Đăng nhập dùng OIDC Code + PKCE với Keycloak qua trình duyệt hệ thống (AppAuth), lưu token trong Keychain/Keystore, không lưu dữ liệu C2 dạng rõ trên máy, bật chặn chụp màn hình ở màn hình có dữ liệu C2, khoá ứng dụng bằng sinh trắc học khi được bật. Văn bản giao diện tiếng Việt có dấu, cùng thuật ngữ với web, chế độ chữ lớn cho người cao tuổi, đạt WCAG 2.1 AA tương đương trên di động. Tiêu chí hoàn thành mặc định: `flutter analyze` sạch, `flutter test` xanh, chạy được trên trình giả lập Android và iOS (hoặc ghi rõ nền tảng chưa chạy được), **bắt buộc cập nhật trạng thái task vào `TIEN-DO-HE-THONG.md`** bằng `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> ...`, báo cáo theo `AGENTS.md` mục 6.

## Liên quan

- `AGENTS.md`, `TIEN-DO-HE-THONG.md`, `docs/KE-HOACH-TONG-THE-HE-THONG.md`
