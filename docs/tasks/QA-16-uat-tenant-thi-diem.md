# QA-16 · Đợt 2 Kiểm thử chấp nhận nghiệp vụ: tenant thí điểm chạy trọn một kỳ đánh giá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-16` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (điều kiện kết thúc đợt 2) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-07 |
| Truy vết | Điều kiện chuyển bước đợt 2 (TL-01 chương "Lộ trình chuyển đổi theo Strangler Fig", chỉ phần tiêu chí đợt 2; phần Strangler không áp dụng) → QT-00..QT-18 → SCR theo wiki 05a, 05b → MOD-01..MOD-08 → EVT-01..EVT-26 → `ledger.verify_run` |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Tổ chức kiểm thử chấp nhận với một tenant thí điểm chạy trọn một kỳ đánh giá trên hệ mới: lập kế hoạch, giao việc, OKR, cập nhật tiến độ, minh chứng, tự đánh giá, đánh giá, khoá kỳ, hồ sơ số, khiếu nại. Trong task này, kỳ đánh giá chạy với tenant thí điểm giả lập và người dùng thử trên staging. Khi xong, có bộ kế hoạch, kịch bản và biên bản diễn tập theo mẫu nghiệm thu, `ledger-verify` báo hợp lệ trên kỳ hoàn chỉnh, và danh sách lỗi đã phân loại theo mức sự cố; bộ này sẵn sàng chạy lại với tenant thí điểm thật khi có.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Lộ trình chuyển đổi theo Strangler Fig" (dòng 3140–3184: điều kiện chuyển bước đợt 2 "tenant thí điểm hoàn thành một kỳ đầy đủ trên hệ mới", "ca vàng đạt 100%", "`ledger-verify` báo hợp lệ trên một kỳ hoàn chỉnh"). Chỉ dùng tiêu chí thí điểm của đợt 2; phần Strangler Fig không áp dụng vì Mission là hệ mới độc lập (quyết định ngày 20/10/2026).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 12.6 "Đợt 2" (dòng 3946), mục 11.4 (OPS-07 onboarding), mục 11.8 (OPS-17 chuẩn bị trước mốc hạn và saga khoá kỳ).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, `TL-02b-quy-trinh.md` (QT-00..QT-18); `wiki/05a`, `wiki/05b`, `wiki/15a-ma-tran-phan-quyen.md`.
- `frontend/e2e/hanh-trinh/danh-muc.json` (QA-01) làm khung kịch bản.

## 3. Phạm vi công việc

- Kế hoạch UAT `docs/uat/<tenant>/ke-hoach.md` (suy luận về vị trí): phạm vi QT, lịch theo mốc của kỳ (`config.period`), vai trò AC-01..AC-10 và người đại diện, tiêu chí chấp nhận, cách báo lỗi.
- Kịch bản UAT bằng ngôn ngữ nghiệp vụ, mỗi kịch bản ánh xạ một hành trình HT-xx của danh mục; không ghi mã kỹ thuật trong phần người dùng đọc.
- Diễn tập trước trên staging với người dùng thử đóng vai các AC, dữ liệu đã che (tác vụ `anonymize`, SEC-52), để kịch bản và tài liệu chạy trơn trước khi có người dùng thật.
- Chuẩn bị tenant thí điểm qua OPS-07 (VH-04 đã diễn tập), cờ tính năng bật cho tenant thí điểm (OPS-04).
- Theo dõi trong kỳ: tổng hợp phiếu lỗi theo SEV-1..SEV-4, chỉ số SLO của cell thí điểm, phản hồi người dùng về chế độ Đảng viên cao tuổi và số chạm.
- Cuối kỳ: kiểm khoá kỳ, điểm và xếp loại so với tính tay trên mẫu, ca vàng 100%, `createLedgerVerifyRun` hợp lệ cho cả kỳ; lập biên bản nghiệm thu `docs/uat/<tenant>/bien-ban.md`.
- Gợi ý tách: (a) kế hoạch, kịch bản và diễn tập staging; (b) chạy kỳ thật và theo dõi; (c) nghiệm thu cuối kỳ.

## 4. Ngoài phạm vi

- Mở rộng theo nhóm tenant sau thí điểm (VH-08); sửa lỗi (task theo phân hệ); tài liệu hướng dẫn sử dụng (VH-05, dùng làm đầu vào).
- Nối và kiểm thử với hệ thật khi có môi trường: chạy kỳ thật với tenant thí điểm và người dùng thật, lập biên bản nghiệm thu có chữ ký đại diện tenant; khi đó chỉ đổi tenant đích và tài khoản người dùng, không đổi kịch bản.

## 5. Tệp được phép sửa

- `docs/uat/**`, `frontend/e2e/hanh-trinh/danh-muc.json` (cột liên kết kịch bản UAT).

## 6. Tiêu chí hoàn thành

- Diễn tập trên staging: mọi kịch bản UAT chạy hết với người dùng thử; `E2E_MODE=he-thong npm run e2e:he-thong` xanh trên staging trước buổi UAT.
- Với tenant thí điểm giả lập trên staging chạy trọn một kỳ rút gọn: biên bản diễn tập theo mẫu biên bản nghiệm thu; 0 lỗi SEV-1, SEV-2 còn mở; ca vàng 100%; `ledger-verify` hợp lệ cho kỳ. Tenant đích và tài khoản là tham số của kế hoạch, nên chạy với tenant thật chỉ đổi cấu hình.

## 7. Báo cáo

Theo AGENTS.md mục 6.
