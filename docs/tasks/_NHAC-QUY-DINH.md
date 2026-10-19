=== NHẮC QUY ĐỊNH CHUNG DỰ ÁN MISSION (tự động hiện khi bắt đầu phiên) ===
Trước khi làm task: đọc AGENTS.md (12 quy tắc bắt buộc) và các trang wiki mà task yêu cầu.
1. Bám TL-01..TL-04 + baseline; không tự đổi kiến trúc, framework, thêm thư viện lớn.
2. Giữ nguyên mã ADR/SVC/MOD/EVT/QT/SCR/BR/MSG/NFR/SEC và tên trạng thái.
3. Chỉ sửa tệp trong phạm vi task.   4. Không commit bí mật (.env).
5. Không innerHTML / bypassSecurityTrust / eval; SDUI chỉ dùng component trong registry.
6. Máy chủ là nguồn sự thật cho quyền và quy tắc; ẩn/hiện theo ma trận wiki/15a.   7. API qua @vt/api-client theo quy ước mục 13.
8. Angular hiện đại: standalone, signals, inject(), @if/@for; không ghi signal trong computed.
9. WCAG 2.1 AA, ≤ 3 chạm, chế độ Đảng viên cao tuổi.   10. Giữ ngân sách hiệu năng.
11. Build + test + mở màn hình thật trước khi báo xong.   12. Báo cáo trung thực theo mẫu AGENTS.md mục 6.
Không đụng container ngoài project mission-dev trên server dev.
===========================================================================
