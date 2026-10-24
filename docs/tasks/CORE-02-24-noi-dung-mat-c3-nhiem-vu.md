# CORE-02-24 · ADR-27/C3 Nội dung mật của nhiệm vụ: `task.task_secret_content`, `task.e2ee_key_wrap`, chia sẻ và thu hồi khoá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-24` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-02, CORE-07-06 (danh bạ khoá công khai, khoá phục hồi tổ chức), NEN-042 (thao tác `/tasks/{id}/secret-content*`); NEN-014 (ABAC), CORE-00-05 (ghi vết truy cập) (bổ sung) |
| Truy vết | ADR-27, ADR-06 (C3) → QT-05 (nhiệm vụ mật `is_restricted`) → API đề xuất trong NEN-042: `PUT/GET /api/v1/tasks/{id}/secret-content` (`saveTaskSecretContent`, `getTaskSecretContent`), `POST /api/v1/tasks/{id}/secret-content/key-wraps` (`shareTaskSecretContent`), `POST .../key-wraps/{fingerprint}/revoke` (`revokeTaskSecretShare`) → MOD-02 → bảng `task.task_secret_content`, `task.e2ee_key_wrap`, đọc `org.person_public_key` qua SVC-04 → sổ cái SVC-09 qua `sys.audit.v1` |
| Trạng thái | Chưa bắt đầu (phần API chờ NEN-042 gộp) |

## 1. Mục tiêu

Nhiệm vụ có nội dung mật (tiêu đề thật, mô tả, chỉ đạo) được mã hoá đầu cuối trên trình duyệt hoặc di động; máy chủ chỉ giữ bản mã và khoá nội dung (CEK) đã bọc cho từng người được xem và cho khoá phục hồi của tổ chức. Task này làm phần của MOD-02: hai bảng bổ sung của TL-03, lưu và trả bản mã cùng khoá đã bọc của chính người gọi, chia sẻ bằng CEK bọc lại do client gửi lên, thu hồi chặn tải bản mã về. Khi xong, nhiệm vụ `is_restricted` chạy được đầu cuối với thư viện `@vt/e2ee`, và không có nội dung C3 nào rời khỏi bảng `task_secret_content`.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Bảng task.task_secret_content (bổ sung)" (L2387), "Bảng task.e2ee_key_wrap (bổ sung)" (L2401), cột `is_restricted` của `task.task` (L2260), "Mã hoá đầu cuối cho nội dung mật" (L4168–4170), bảng xử lý theo phân loại (C3 không vào Kafka, ClickHouse, OpenSearch, Valkey, nhật ký).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Mã hoá đầu cuối cho dữ liệu C3" (L2309–2335: chia sẻ bằng bọc lại CEK L2327, thu hồi chỉ ngăn tải bản mã, khoá phục hồi L2331), ADR-27.
- Hợp đồng `contracts/openapi/modules/tasks.yaml` sau NEN-042 (bốn thao tác ở trên) và lược đồ khoá đã bọc trong `products.yaml` (`recipientId`, `wrappedKey`, `alg`) để giữ thống nhất.
- Thẻ `docs/tasks/CORE-07-06-ma-hoa-dau-cuoi-c3.md`, `CORE-02-02-migration-nhiem-vu.md`, `NEN-042-sua-hop-dong-nghiep-vu.md`; `wiki/08-bao-mat-frontend.md` phần E2EE.

## 3. Phạm vi công việc

- Viết migration trong `db/migration/task/` tạo `task.task_secret_content` đúng TL-03: PK (`period_bucket`, `id`), phân vùng theo kỳ, UQ (`period_bucket`, `task_id`), CHECK `length(nonce) = 12`, `content_alg` DEFAULT `A256GCM`, `aad_hash`, `title_bidx` có chỉ mục, `sender_key_fingerprint`; và `task.e2ee_key_wrap` (CHECK `object_type` IN (`TASK_CONTENT`), UQ (`tenant_id`, `object_id`, `recipient_key_fingerprint`), CHECK `wrap_alg`, `revoked_at`), RLS theo tenant, `COMMENT ON COLUMN` ghi phân loại (`ciphertext` C3).
- `saveTaskSecretContent`: chỉ nhận cho nhiệm vụ `is_restricted`, kiểm `aad_hash` khớp (`tenant_id`, `task_id`, phiên bản) theo cách TL-03 mô tả, bắt buộc danh sách khoá bọc có người tạo và đúng một bản cho khoá phục hồi tổ chức hiện hành (từ CORE-07-06), mỗi `recipient_key_fingerprint` phải là khoá `HIEU_LUC` trong danh bạ; If-Match theo phiên bản nội dung.
- `getTaskSecretContent`: trả bản mã kèm đúng một khoá đã bọc của người gọi (theo khoá thiết bị đang dùng); người gọi không có bản bọc còn hiệu lực trả 403; ghi một sự kiện truy cập C3 qua cơ chế của CORE-00-05 (không ghi nội dung).
- `shareTaskSecretContent`: người nhận mới phải qua ABAC (thuộc phạm vi nhiệm vụ, OPA) trước khi chấp nhận CEK bọc lại; ghi `granted_by`; `revokeTaskSecretShare`: đặt `revoked_at`, từ lần gọi sau không trả bản mã cho người đó; không cho thu hồi bản bọc của khoá phục hồi tổ chức.
- Bảo đảm ranh giới C3: `task_secret_content` không có trong publication `pub_analytics` của CDC, không vào chỉ mục tìm kiếm, sự kiện EVT-04 của nhiệm vụ mật chỉ mang tiêu đề che; ArchUnit chặn mọi đường đọc `ciphertext` ngoài use case trên; kiểm thử chứng minh nhật ký ứng dụng không chứa bản mã.
- Điểm lệch cần lưu ý: CORE-07-06 cũng nhận "kho `e2ee_key_wrap` cho `TASK_CONTENT`"; phân định: CORE-02-24 sở hữu hai bảng của schema `task` và bốn thao tác của MOD-02, CORE-07-06 giữ danh bạ khoá, khoá phục hồi và phần `product`; ghi vào báo cáo để điều chỉnh thẻ CORE-07-06.

## 4. Ngoài phạm vi

- Danh bạ khoá công khai `org.person_public_key`, khoá phục hồi Shamir (CORE-07-06); `product.e2ee_key_wrap` (CORE-03-01, CORE-07-06); thư viện `@vt/e2ee` và giao diện (WEB); khoá trên di động (MOB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ thêm migration mới)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh gồm Testcontainers PostgreSQL 17: lưu rồi đọc bản mã do kiểm thử tự mã hoá bằng khoá ECDH P-256 sinh trong kiểm thử, giải mã được ở phía kiểm thử; thiếu bản bọc khoá phục hồi trả 422; người bị thu hồi nhận 403; người ngoài phạm vi không được thêm vào chia sẻ.
- Kiểm thử hợp đồng cho bốn `operationId`; kiểm thử chứng minh không có cột C3 trong outbox, publication CDC và tài liệu chỉ mục.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
