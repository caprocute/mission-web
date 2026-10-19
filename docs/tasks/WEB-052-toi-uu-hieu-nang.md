# WEB-052 · Kiểm thử và tối ưu hiệu năng frontend

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-052` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ4 – Điều hành, cấu hình, mở rộng |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-003, WEB-011 |
| Truy vết | NFR-03, TL-04 PERF liên quan trang |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đo và tối ưu toàn bộ remote theo ngân sách; lập báo cáo LCP/INP/kích thước.

## 2. Đầu vào bắt buộc đọc

- wiki/09-hieu-nang-va-trai-nghiem.md
- TL-04 "Danh mục kịch bản thử tải PERF"

## 3. Phạm vi công việc

- Lighthouse CI giả lập 4G cho các trang chính, k6 browser cho luồng "cập nhật tiến độ".
- Tối ưu: nạp lười, preconnect, chia nhỏ chunk, cache UI Pack.

## 4. Ngoài phạm vi

- (không có)

## 5. Tệp được phép sửa

- frontend/**

## 6. Tiêu chí hoàn thành

- Báo cáo số liệu trước/sau trong wiki/09.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-052 trong `docs/tasks/README.md`.
