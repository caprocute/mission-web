# QA-03 · YC-PT-08 Bộ kiểm thử đầu cuối với hệ thống thật trên server dev (backend thật, Keycloak, APISIX)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B9 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-22, QA-01 |
| Truy vết | YC-PT-08 → TL-01 ch.18 (Playwright, khoảng 40 hành trình) → SVC-17 → SVC-01 (APISIX dev) → SVC-02 (realm `mission`) → SVC-05 → topic EVT trên Kafka dev |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng khung chạy hành trình đầu cuối trên hệ thống thật ở server dev: giao diện build thật, đăng nhập Keycloak thật có OTP, API qua APISIX tới mission-core thật, dữ liệu khởi tạo bằng API, kiểm sự kiện trên Kafka. Khi xong, một hành trình mẫu "cán bộ cập nhật tiến độ nhiệm vụ" chạy xanh với backend thật, và QA-04..QA-07 chỉ cần viết thêm hành trình trên khung này.

## 2. Đầu vào bắt buộc đọc

- `frontend/e2e/` (khung Playwright WEB-057 hiện có: `playwright.config.ts`, `serve.mjs`, `totp.ts`, chế độ `E2E_MODE=keycloak`), `wiki/11-kiem-thu-va-chat-luong.md` mục WEB-057 (bài học `idempotencyInterceptor` với Keycloak thật).
- `infra/dev/README.md` (tài khoản thử, OTP, cổng), kết quả INF-22 (profile `backend`, `smoke-backend.sh`).
- Danh mục hành trình của QA-01 (`frontend/e2e/hanh-trinh/danh-muc.json`), `contracts/openapi/modules/tasks.yaml` (`listMyTasks`, `updateTaskProgress`).
- `AGENTS.md` mục 7 (chỉ thao tác project `mission-dev`), `docs/quy-dinh/HA-TANG.md` mục 3.

## 3. Phạm vi công việc

- Thêm chế độ `E2E_MODE=he-thong` vào `frontend/e2e/playwright.config.ts`: `config.json` trỏ `apiBaseUrl` tới APISIX dev, `useMockApi: false`, `auth.enabled: true` với realm `mission`; chỉ chạy tệp `hanh-trinh/**/*.spec.ts`; không chụp ảnh màn hình đăng nhập (giữ quy ước của chế độ keycloak).
- Viết lớp hỗ trợ `frontend/e2e/hanh-trinh/ho-tro/`: đăng nhập theo vai trò (dùng lại `totp.ts`), client API gọi qua APISIX với token của tài khoản thử để dựng dữ liệu đầu vào (gửi `Idempotency-Key`, `If-Match` đúng quy ước), dọn dữ liệu theo tiền tố riêng của lần chạy.
- Viết bộ kiểm sự kiện: đọc topic Kafka dev (qua cổng được phép hoặc qua API của kafka-ui) để khẳng định một EVT đã phát sau thao tác, lọc theo `traceparent` hoặc khoá tổng hợp.
- Viết dữ liệu khởi tạo tối thiểu `data/seed/e2e/` (đơn vị, kỳ, danh mục công việc) nạp bằng API, không chèn trực tiếp vào CSDL; không dùng dữ liệu cá nhân thật.
- Viết hành trình mẫu: AC-06 `canbo` mở "Việc của tôi" (`listMyTasks`), cập nhật tiến độ (`updateTaskProgress`), kiểm EVT-05 `task.task.progressed.v1` xuất hiện và màn hình hiển thị tiến độ mới.
- Viết reporter xuất kết quả ra `dist/e2e-ket-qua/bao-cao-he-thong.md` (hành trình, kết quả, thời gian, mã tra cứu `X-Request-Id` khi lỗi) để dán vào PR.

## 4. Ngoài phạm vi

- Các hành trình nghiệp vụ còn lại (QA-04..QA-07); chạy trên môi trường tạm theo nhánh (INF-21, sau khi có tài nguyên).
- Sửa lỗi nghiệp vụ phát hiện được: mở task sửa riêng, ghi trong báo cáo.

## 5. Tệp được phép sửa

- `frontend/e2e/**`, `frontend/package.json` (thêm lệnh `e2e:he-thong`), `data/seed/e2e/**`.
- `wiki/11-kiem-thu-va-chat-luong.md`.

## 6. Tiêu chí hoàn thành

- `npm run e2e` (chế độ giả lập) và chế độ keycloak hiện có vẫn xanh như trước.
- `E2E_MODE=he-thong npm run e2e:he-thong` chạy hành trình mẫu xanh trên server dev (qua Remote Control), không lỗi console trình duyệt, EVT-05 được kiểm thấy.
- Chạy lại hai lần liên tiếp vẫn xanh (dữ liệu được dọn, không phụ thuộc thứ tự).
- Báo cáo cuối task theo AGENTS.md mục 6 (không ghi địa chỉ máy chủ, tài khoản, mật khẩu); wiki 11 có mục "Kiểm thử đầu cuối trên hệ thống thật".

## 7. Báo cáo

Theo AGENTS.md mục 6.
