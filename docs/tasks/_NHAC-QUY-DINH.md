=== NHẮC QUY ĐỊNH CHUNG DỰ ÁN MISSION (tự động hiện khi bắt đầu phiên) ===
Trước khi làm task: đọc AGENTS.md (12 quy tắc bắt buộc) và các trang wiki mà task yêu cầu.
1. Bám TL-01..TL-04 + baseline; không tự đổi kiến trúc, framework, thêm thư viện lớn.
2. Giữ nguyên mã ADR/SVC/MOD/EVT/QT/SCR/BR/MSG/NFR/SEC và tên trạng thái.
3. Chỉ sửa tệp trong phạm vi task.   4. Không commit bí mật (.env).
5. Không innerHTML / bypassSecurityTrust / eval; SDUI chỉ dùng component trong registry.
6. Máy chủ là nguồn sự thật cho quyền và quy tắc; ẩn/hiện theo ma trận wiki/15a.   7. API qua @vt/api-client theo quy ước mục 13.
8. Angular hiện đại: standalone, signals, inject(), @if/@for; không ghi signal trong computed.
9. WCAG 2.1 AA, ≤ 3 chạm, chế độ Đảng viên cao tuổi.   10. Giữ ngân sách hiệu năng.
10. 11. Build + test + mở màn hình thật trước khi báo xong.   12. Cập nhật tiến độ vào `TIEN-DO-HE-THONG.md` (`python3 scripts/cap-nhat-tien-do.py set <MÃ> ...`) và báo cáo trung thực theo mẫu AGENTS.md mục 6.
11. Task máy chủ, hạ tầng: đọc thêm docs/quy-dinh/BACKEND.md, HA-TANG.md (API-first, outbox, RLS, Idempotency-Key, If-Match 409, Testcontainers).
12. Không đụng container ngoài project mission-dev trên server dev. Mọi agent BẮT BUỘC cập nhật tiến độ vào tệp duy nhất `TIEN-DO-HE-THONG.md` khi nhận việc, khi xong trên nhánh và khi gộp main.
===========================================================================
