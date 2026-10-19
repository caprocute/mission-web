# WEB-074 · NFR-10 Gắn `traceparent` mọi lời gọi và liên kết telemetry

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-074` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c – Khung quan sát |
| Luồng đề xuất | W |
| Cỡ việc | S |
| Phụ thuộc | WEB-011 |
| Truy vết | NFR-10, ADR-12 → mọi `/api/v1/**` qua `@vt/api-client` → SVC-01 (`traceparent`, `X-Trace-Id`) → SVC-05 và các service; tiêu chí B2, B8 của TL-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mọi lời gọi API từ shell và từ mọi remote mang `traceparent` W3C hợp lệ nối với span trình duyệt của Faro, để từ một lỗi người dùng báo hay một phiên RUM chậm, người trực tra được đúng trace phía máy chủ; người dùng nhìn thấy "mã tra cứu" khi gặp lỗi để báo cho bộ phận hỗ trợ. Khi xong, NFR-10 vế "độ đầy đủ ngữ cảnh" được đáp ứng từ phía trình duyệt.

## 2. Đầu vào bắt buộc đọc

- `TL-04-trien-khai-van-hanh.md` chương "Quan sát hệ thống" (dòng 3253): mục "Trace: OpenTelemetry và lấy mẫu theo đuôi" (lan truyền W3C Trace Context, Baggage `tenant`, `X-Trace-Id`, `X-Debug-Trace`, cách hiểu NFR-10) và "Dashboard và giám sát phía người dùng" (Faro, dòng 3559).
- `TL-01-thiet-ke-kien-truc.md` chương "Quan sát và nhật ký" (dòng 2923), mục "Giám sát phía người dùng".
- `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/02-kien-truc-frontend.md` (chia sẻ phụ thuộc giữa shell và remote).
- `frontend/projects/telemetry/src/lib/traceparent.interceptor.ts`, `web-vitals.ts`; `frontend/projects/api-client/src/lib/problem-presenter.ts`; thẻ WEB-011.

## 3. Phạm vi công việc

- Bảo đảm remote dùng chung `HttpClient` và chuỗi interceptor của shell qua Native Federation (không tự `provideHttpClient` riêng làm mất interceptor); viết kiểm thử phát hiện remote nào gọi API không có `traceparent`.
- Khi Faro (WEB-011) bật, lấy trace id và span id từ span trình duyệt hiện hành thay vì sinh ngẫu nhiên, để span trình duyệt là cha của span APISIX; cờ lấy mẫu theo `telemetry.sampleRatio` của `RuntimeConfig`; giữ nguyên `traceparent` nếu request đã có.
- Thêm W3C Baggage `tenant` theo TL-04 (chỉ mã tenant, không dữ liệu cá nhân); cho phép bật `X-Debug-Trace` bằng tham số trang dành cho hỗ trợ kỹ thuật (suy luận cách bật, ghi wiki).
- Đọc `X-Trace-Id` của phản hồi hoặc `traceId` trong thân lỗi RFC 9457 và hiển thị "Mã tra cứu" trong thông báo lỗi, có nút sao chép; không hiện thông tin kỹ thuật khác.
- Ghi sự kiện telemetry lỗi API kèm trace id, không kèm thân yêu cầu hay token.

## 4. Ngoài phạm vi

- Cấu hình OTel Collector, Tempo, Faro receiver (hạ tầng).

## 5. Tệp được phép sửa

- `frontend/projects/telemetry/src/lib/**`
- `frontend/projects/api-client/src/lib/problem-presenter.ts`, `frontend/projects/api-client/src/lib/interceptors.ts`
- `frontend/projects/fe-*/src/app/app.config.ts` (chỉ khi phải bỏ cấu hình HttpClient riêng của remote)
- `wiki/09-hieu-nang-va-trai-nghiem.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh: định dạng `traceparent` đúng W3C, dùng span hiện hành khi có, không ghi đè header có sẵn.
- Kịch bản Playwright mở từng remote và xác nhận 100% request `/api/` có `traceparent` hợp lệ; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được với thông báo lỗi có mã tra cứu; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
