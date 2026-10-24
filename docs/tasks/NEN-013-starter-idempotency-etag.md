# NEN-013 · HD-01/HD-02 vt-platform-starter: Idempotency-Key 48 giờ, ETag/If-Match, phân trang con trỏ, tác vụ 202

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-013` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-009, NEN-032 |
| Truy vết | baseline mục 13, HD-01, HD-02, HD-06 → `common.yaml` (`IdempotencyKey`, `IfMatch`, `Limit`, `Cursor`, `Accepted`, `Job`) → bảng `<schema>.idempotency_key` → `GET /api/v1/jobs/{id}` (`getJob`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực một lần, trong thư viện nền, bốn quy ước API mà hợp đồng áp cho mọi thao tác: chống ghi trùng bằng `Idempotency-Key` giữ 48 giờ, khoá lạc quan bằng `ETag`/`If-Match` (409 khi lệch, 428 khi thiếu), phân trang con trỏ (`limit` mặc định 20, tối đa 200, `items` + `nextCursor`), và trả 202 kèm `Location` cho tác vụ dài. Khi xong, controller nghiệp vụ chỉ cần annotation hoặc gọi tiện ích, hành vi giống hệt nhau ở mọi module và khớp hợp đồng.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/common/common.yaml` tham số `IdempotencyKey`, `IfMatch`, `Limit`, `Cursor`, header `ETag`, `Location`, `RetryAfter`, phản hồi `Accepted`, `Conflict`, `PreconditionRequired`, `UnprocessableEntity`, lược đồ `Job`, `JobStatus`, `NextCursor`; `contracts/openapi/modules/jobs.yaml`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Bảng idempotency_key" (L875–892).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Quản lý giao dịch" (L710–752: `IdempotencyStore`, mẫu `UpdateProgressUseCase`), "Idempotency" (L2876–2880), "San phẳng đỉnh bằng hàng đợi" (L2773–2775).
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` HD-01, HD-02, HD-06.

## 3. Phạm vi công việc

- Gói `idempotency`: annotation `@Idempotent` cho thao tác ghi; `IdempotencyStore` (`find`/`begin`/`complete`) trên bảng `<schema>.idempotency_key` khoá `(tenant_id, actor_id, idem_key, created_day)`, `request_hash` SHA-256 trên phương thức + đường dẫn + thân yêu cầu chuẩn hoá, `status` `DANG_XU_LY`/`XONG`, lưu `response_code`, `response_body` (không lưu thân có trường C2, chỉ lưu `resource_ref`), ghi trong cùng giao dịch với tác dụng.
- Hành vi: cùng khoá cùng băm và `XONG` → trả lại phản hồi cũ; cùng khoá khác băm → 422 `COMMON-422-IDEMPOTENCY-MISMATCH`; cùng khoá đang `DANG_XU_LY` → 409 kèm `Retry-After` (suy luận, hợp đồng không ghi; nêu trong báo cáo); thiếu header ở thao tác bắt buộc → 400.
- Gói `etag`: tiện ích `ETags.of(rowVersion)` → `"7"`, đọc `If-Match`; thiếu → 428; lệch → 409 `<MODULE>-409-VERSION-CONFLICT` (mã module truyền vào); trả header `ETag` khi đọc và sau khi sửa thành công; hỗ trợ `If-None-Match` → 304 cho thao tác đọc có ETag.
- Gói `paging`: `CursorPage<T>` (`items`, `nextCursor`), mã hoá con trỏ mờ (keyset theo cột sắp xếp + `id`, base64url, có ký HMAC để client không tự dựng) và kiểm `limit` 1..200, mặc định 20; trả 400 khi con trỏ sai hoặc hết hạn.
- Gói `job`: tiện ích trả 202 với thân `Job` và `Location: /api/v1/jobs/{id}`; giao diện `JobSource` (mỗi module đăng ký nguồn trạng thái tác vụ của mình như `score_job`, `render_job`, saga) và `JobQueryService` gộp các nguồn để CORE-00-02 gắn vào `getJob`, `cancelJob` (chỉ người khởi tạo xem được, người khác nhận 404 theo `jobs.yaml`).
- Kiểm thử tích hợp Testcontainers PostgreSQL với migration mẫu của NEN-032: gửi lại cùng khoá 3 lần → một tác dụng; hai yêu cầu đồng thời cùng khoá → một thành công, một nhận 409; lệch phiên bản → 409; thiếu `If-Match` → 428; phân trang qua 450 dòng với `limit=200` → 3 trang, không trùng, không sót.

## 4. Ngoài phạm vi

- Gắn `/jobs/{id}` vào mission-core: CORE-00-02; nguồn job cụ thể của từng module: task nghiệp vụ.
- Dọn khoá quá 48 giờ bằng pg_partman: NEN-032.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `idempotency`, `etag`, `paging`, `job`, kiểm thử)
- `wiki/16-kien-truc-mission-core-va-microservice.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với các kịch bản ở mục 3.
- Phản hồi lỗi của các kịch bản khớp lược đồ `ProblemDetails`, `code` đúng mã trong hợp đồng.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 16 (nếu đã có) mục "Quy ước API trong mã".

## 7. Báo cáo

Theo AGENTS.md mục 6.
