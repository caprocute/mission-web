# WEB-073 · TL-04 cắt tải Xử lý 429, 503 có `Retry-After` và giữ bản nháp

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-073` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – Quy mô quốc gia (sẵn sàng cho cắt tải ở APISIX) |
| Luồng đề xuất | W |
| Cỡ việc | S (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày) |
| Phụ thuộc | WEB-007 |
| Truy vết | TL-04 "Cắt tải và giới hạn tốc độ ở APISIX", "Hành vi khi bão hoà" → mọi `/api/v1/**` qua `@vt/api-client` → phản hồi 429, 503 (`components/responses/TooManyRequests`, `ServiceUnavailable` trong `common.yaml`) → SVC-01; PERF-06; NFR-03 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi hệ thống cắt tải (429 do người dùng hoặc tenant vượt hạn mức, 503 do quá tải, kể cả mức suy giảm 3 chỉ nhận ghi ưu tiên), người dùng không mất dữ liệu đang nhập và không phải tự bấm lại nhiều lần: client tôn trọng `Retry-After`, thử lại có lùi thời gian và nhiễu ngẫu nhiên với các lời gọi an toàn, hiện thông báo xếp hàng bằng lời lẽ nghiệp vụ, và giữ bản nháp để gửi lại.

## 2. Đầu vào bắt buộc đọc

- `TL-04-trien-khai-van-hanh.md` mục "Cắt tải và giới hạn tốc độ ở APISIX" (dòng 1579) và "Hành vi khi bão hoà" (dòng 1649, đoạn về 429, 503, `Retry-After`, backoff kèm nhiễu, giữ bản nháp).
- `wiki/06-quy-uoc-api.md`, `wiki/08-bao-mat-frontend.md` (phân lớp dữ liệu, không lưu C2 dạng rõ), `wiki/09-hieu-nang-va-trai-nghiem.md`.
- `contracts/openapi/common/common.yaml` các phản hồi `TooManyRequests`, `ServiceUnavailable`, header `RetryAfter`.
- `frontend/projects/api-client/src/lib/interceptors.ts`, `problem-presenter.ts`.

## 3. Phạm vi công việc

- Thêm interceptor trong `@vt/api-client`: với 429 và 503 đọc `Retry-After` (giây hoặc ngày giờ HTTP), thử lại tự động tối đa 3 lần với lùi theo cấp số nhân kèm nhiễu ngẫu nhiên, không chờ ít hơn `Retry-After`; chỉ tự thử lại GET và lệnh ghi có `Idempotency-Key` (an toàn nhờ khử trùng phía máy chủ), giữ nguyên khoá khi gửi lại.
- Hết lượt thử lại: trả lỗi có cờ "có thể gửi lại", `problem-presenter` hiện thông báo xếp hàng tiếng Việt nghiêm túc (ví dụ hệ thống đang bận, nội dung đã được giữ, có thể gửi lại sau ít phút), không hiện mã kỹ thuật.
- Giữ bản nháp: cung cấp tiện ích để biểu mẫu đăng ký nội dung đang soạn; khi lệnh ghi thất bại vì cắt tải, nội dung còn nguyên trên màn hình và có nút gửi lại. Điểm lệch cần lưu ý: TL-04 nói "giữ bản nháp cục bộ" còn AGENTS.md cấm lưu C2 dạng rõ; chọn giữ trong bộ nhớ phiên cho mọi trường và chỉ lưu IndexedDB với trường C0, C1 theo `x-classification` (suy luận), ghi vào `wiki/08`.
- Thêm cấu hình giả lập 429 và 503 có `Retry-After` vào `mockApiInterceptor` (tham số `mock-error`) để kiểm thử và trình diễn.
- Phát sự kiện telemetry khi gặp cắt tải (số lần, thời gian chờ) qua `@vt/telemetry`, không kèm dữ liệu biểu mẫu.

## 4. Ngoài phạm vi

- Cấu hình `limit-count`, `limit-req` ở APISIX và mức suy giảm (hạ tầng).
- Ứng dụng di động (MOB).

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/lib/**`, `frontend/projects/api-client/src/mock/mock-api.interceptor.ts`
- `frontend/projects/fe-shell/src/app/app.config.ts` (chỉ đăng ký interceptor)
- `frontend/projects/telemetry/src/lib/**` (chỉ sự kiện cắt tải)
- `wiki/06-quy-uoc-api.md`, `wiki/08-bao-mat-frontend.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh: tôn trọng `Retry-After` dạng giây và dạng ngày giờ; không tự thử lại POST không có khoá; dừng sau 3 lần; giữ nguyên `Idempotency-Key` khi gửi lại.
- Màn hình mở trên trình duyệt với `?mock-error=503` không có lỗi console, nội dung biểu mẫu không mất, gửi lại thành công khi tắt lỗi.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious; thông báo xếp hàng được trình đọc màn hình đọc (vùng `aria-live`).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
