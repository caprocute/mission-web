# MOB-02 · SVC-18 Lưu trữ ngoại tuyến SQLCipher, khoá trong Keystore/Keychain, hàng đợi đồng bộ có Idempotency-Key

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-01 |
| Truy vết | SVC-18; ADR-06 (phân lớp dữ liệu); QT-03, QT-05, QT-06, QT-07 (MSG-03-06, MSG-03-07) → mọi lệnh ghi của ứng dụng (ví dụ `POST /api/v1/okr/key-results/{id}/checkins`, `POST /api/v1/tasks/{id}/progress`) với `Idempotency-Key` → MOD-01, MOD-02, MOD-03 → bảng phía máy chủ `idempotency_key` của từng schema (48 giờ) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Ứng dụng làm việc được khi mất sóng: dữ liệu nhập được lưu trong SQLite mã hoá bằng SQLCipher với khoá bọc trong Android Keystore hoặc iOS Keychain, lệnh ghi xếp vào hàng đợi và tự gửi khi có mạng, mỗi lệnh giữ nguyên `Idempotency-Key` sinh từ lúc người dùng bấm gửi để gửi lại không tạo bản ghi trùng. Đây là nền cho check-in, cập nhật tiến độ, nộp minh chứng ở vùng sóng yếu (TL-01 SVC-18, TL-02a "Thiết bị di động").

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-18 mobile-app" (ngoại tuyến trước, SQLCipher, hàng đợi đồng bộ).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, mục "Thiết bị di động"; đặc tả SCR-0302 (trường "Khoá chống ghi trùng", MSG-03-06, MSG-03-07) và SCR-0504.
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, mục bảng `idempotency_key` (thời hạn 48 giờ) và phân loại dữ liệu C0..C3.
- Hợp đồng `contracts/openapi/common/common.yaml` (header `Idempotency-Key`, phản hồi phát lại); `wiki/06-quy-uoc-api.md`, `wiki/08-bao-mat-frontend.md`.

## 3. Phạm vi công việc

- Tạo CSDL cục bộ SQLite mã hoá bằng SQLCipher (gói Flutter chọn và nêu lý do trong PR), khoá ngẫu nhiên 256 bit sinh lần đầu, bọc trong Keystore/Keychain; mỗi cặp người dùng và tenant một CSDL; xoá CSDL khi đăng xuất hoặc đổi tenant.
- Lưu bản nháp và dữ liệu cần hiển thị ngoại tuyến (danh sách việc, KR cần check-in); dữ liệu C2 chỉ nằm trong CSDL mã hoá, không ghi ra tệp tạm, nhật ký hay bộ nhớ đệm hình ảnh; không lưu dữ liệu C3.
- Viết hàng đợi đồng bộ: mỗi mục có phương thức, đường dẫn, thân, `Idempotency-Key` UUIDv7 sinh khi bấm gửi, phiên bản `If-Match` nếu có, số lần thử, trạng thái; gửi theo thứ tự FIFO cho cùng một tài nguyên, thử lại lùi dần có giới hạn.
- Phân loại phản hồi: 2xx và phản hồi phát lại idempotency thì xoá mục; 409 hoặc 422 thì giữ mục ở trạng thái cần người dùng xem lại và hiển thị thông báo; 401 thì làm mới token rồi gửi lại; 5xx, mất mạng thì thử lại.
- Theo dõi trạng thái mạng, hiển thị MSG-03-06 khi lưu trên máy và MSG-03-07 khi đã gửi các mục chờ; đồng bộ nền bằng cơ chế nền của hệ điều hành (suy luận: WorkManager, BGTaskScheduler).
- Giới hạn thời gian giữ mục chờ (mặc định đề xuất 7 ngày, suy luận) và báo người dùng trước khi huỷ.

## 4. Ngoài phạm vi

- Màn hình nghiệp vụ dùng hàng đợi (MOB-03..MOB-08); kho idempotency phía máy chủ (NEN-013).

## 5. Tệp được phép sửa

- `mobile/**`
- Trang wiki quy ước di động do MOB-01 tạo

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh, gồm kiểm thử: gửi lại một mục ba lần dùng cùng một `Idempotency-Key`; 409 không bị gửi lại tự động; mục được xoá sau phản hồi thành công.
- Kiểm thử mở tệp CSDL thô không có khoá không đọc được chuỗi đánh dấu đã ghi (chứng minh đã mã hoá).
- Kiểm thử tích hợp với máy chủ giả lập: ngắt mạng, nhập ba lần, bật mạng, máy chủ nhận đúng ba lệnh, không trùng.
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng chưa chạy được); báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
