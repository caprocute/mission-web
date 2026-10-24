# DV-12-02 · SVC-12 Thông báo thời gian thực SSE `/notifications/stream`, bus Valkey Streams giữa các pod

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-12-02` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-12-01 |
| Truy vết | QT-00 (SCR-0002), QT-08 (SCR-0801), QT-09 (SCR-0904) → API `GET /api/v1/notifications/stream` → SVC-12 → Valkey Streams, bảng `notify.notification` → PERF-22 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho mỗi người dùng một kết nối SSE dài nhận thông báo mới, cập nhật tác vụ dài của chính mình và số chưa đọc, với Valkey Streams làm bus giữa các pod để người dùng kết nối vào pod nào cũng nhận được. Khi xong, 60.000 kết nối SSE với 500 thông báo/giây phân phát p95 ≤ 2 giây, nối lại p95 ≤ 10 giây (PERF-22), mỗi pod giữ tới 20.000 kết nối.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-12 notification-service" (L1156–1168, SSE, Valkey Streams, HPA theo kết nối).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Bộ nhớ đệm Valkey" (L4476–4505: quy ước khoá, stream thông báo 200 phần tử `XTRIM`).
- `contracts/openapi/modules/platform.yaml` `streamNotifications` (định dạng sự kiện `notification`, `job`, `unread`, `: ping`, `retry`, `Last-Event-ID`).
- `TL-04-trien-khai-van-hanh.md` "Danh mục kịch bản thử tải PERF" (L1743, PERF-22) và values SVC-12 (đóng SSE dần trong 30 giây).
- `wiki/06-quy-uoc-api.md` mục tác vụ dài và SSE.

## 3. Phạm vi công việc

- Hiện thực `streamNotifications`: xác thực Bearer, gửi `retry: 5000` đầu luồng, sự kiện `notification`, `job`, `unread` với `id` là mã của Valkey Streams, chú thích `: ping` mỗi 25 giây, `Cache-Control: no-cache`; giới hạn số kết nối đồng thời mỗi người dùng (trả 429 khi vượt, suy luận ngưỡng).
- Mỗi người nhận một stream khoá `t:<tenant>:notify:stream:<user>` sinh qua tiện ích khoá tenant; consumer DV-12-01 `XADD` sau khi ghi CSDL; pod giữ kết nối đọc `XREAD BLOCK` gộp nhiều người dùng theo lô để tiết kiệm kết nối Valkey.
- Hỗ trợ `Last-Event-ID`: phát lại phần còn trong stream; mã quá cũ thì gửi một sự kiện `unread` để client tải lại hộp thông báo.
- Nhận cập nhật tác vụ dài (`job`) của người dùng từ các service qua một luồng nội bộ thống nhất (suy luận: topic kỹ thuật tiền tố `sys.` hoặc Valkey Streams dùng chung; chốt với người phụ trách, không đặt mã EVT mới) và đẩy xuống đúng người khởi tạo.
- Tắt êm: khi nhận SIGTERM, ngừng nhận kết nối mới, đóng dần kết nối hiện có trong 30 giây; xuất metric số kết nối/pod cho HPA (ngưỡng 20.000).
- Điểm lệch cần lưu ý: TL-03 (L4492–4501) đặt stream thông báo giữ 200 phần tử bằng `XTRIM`, hợp đồng ghi "giữ tối thiểu 10 phút (suy luận)"; task cắt theo cả hai điều kiện (`MAXLEN ~ 200` và `MINID` 10 phút, giữ phần nhiều hơn) và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Phía web đọc SSE qua `@vt/api-client` (WEB tương ứng) và kênh đẩy di động (DV-12-03).
- Cấu hình HPA theo metric tuỳ chỉnh (INF-16).

## 5. Tệp được phép sửa

- `services-go/notification/**`
- `infra/dev/` (Valkey nếu NEN-020 chưa có)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/notification && go test ./...` xanh, gồm kiểm thử tích hợp Testcontainers Valkey + PostgreSQL: hai pod, người dùng kết nối pod A, thông báo sinh ở pod B vẫn tới; ngắt kết nối và nối lại với `Last-Event-ID` nhận đủ phần bị lỡ.
- Kiểm thử tải cục bộ (k6 hoặc công cụ Go) với ≥ 5.000 kết nối trên một pod: độ trễ phân phát p95 ≤ 2 giây, bộ nhớ mỗi kết nối ghi vào báo cáo để ngoại suy 20.000 kết nối/pod; PERF-22 đầy đủ chạy ở QA-11.
- Tắt pod trong lúc có kết nối: mọi kết nối đóng trong ≤ 30 giây và client nối lại sang pod khác.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
