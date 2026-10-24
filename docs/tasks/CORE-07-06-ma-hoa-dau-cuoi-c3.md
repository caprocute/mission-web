# CORE-07-06 · ADR-27 Mã hoá đầu cuối C3 phía máy chủ: bản mã, khoá công khai người nhận, bọc khoá, phục hồi tổ chức

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-06` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A danh bạ khoá công khai `org.person_public_key` ở SVC-04; B lưu và phục vụ khoá đã bọc cho `task`/`product`; C chia sẻ, thu hồi, nhật ký; D quy trình phục hồi tổ chức) |
| Phụ thuộc | CORE-07-02, NEN-015 (bổ sung đề xuất: DV-04-01 cho schema `org`, CORE-02-02 và CORE-03-01 cho bảng `e2ee_key_wrap`, NEN-040 cho ABAC chia sẻ) |
| Truy vết | ADR-27; C3 → SVC-04 `org.person_public_key`; MOD-02 `task.task_secret_content`, `task.e2ee_key_wrap`; MOD-03 `product.e2ee_key_wrap`; SVC-08 tệp `e2ee: true` → API `GET /api/v1/evidences/{id}/versions/{v}` (khoá đã bọc cho người gọi), `POST /api/v1/files/uploads` (`e2ee`) → sổ cái SVC-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Phía máy chủ của mã hoá đầu cuối C3 hoạt động đúng nguyên tắc "máy chủ không đọc được nội dung kể cả khi bị xâm nhập": lưu bản mã và danh sách khoá nội dung (CEK) đã bọc cho từng người nhận và cho khoá phục hồi tổ chức, phục vụ danh bạ khoá công khai có ký và ghi sổ cái, kiểm ABAC khi chia sẻ, chặn tải bản mã khi thu hồi, và hỗ trợ quy trình phục hồi Shamir 3 trên 5 trên máy trạm cách ly. Thư viện trình duyệt `@vt/e2ee` dựa vào phần này.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Mã hoá đầu cuối cho dữ liệu C3" (dòng 2309: mô hình, quản lý khoá người nhận, chia sẻ và thu hồi, khôi phục khi mất khoá, giới hạn tìm kiếm, phân tích và AI), "Ranh giới pháp lý với bí mật nhà nước", "ADR-27" (dòng 3652).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Mã hoá đầu cuối cho nội dung mật" (dòng 4168), bảng `org.person_public_key` (dòng 1196), `task.e2ee_key_wrap` (dòng 2401), `product.e2ee_key_wrap` (dòng 2603).
- `contracts/openapi/modules/products.yaml`: lược đồ khoá đã bọc (`recipientId`, `wrappedKey`, `alg`), cờ `hasE2ee`/`e2ee` ở `createFileUpload`, `getEvidenceVersion`, `getFilePreview` (`KHONG_HO_TRO` cho C3).
- Bản trích `tl01-xuyen-suot §3` (X-G3-12..16), `tl03-phan2 §B3`; `wiki/08-bao-mat-frontend.md` phần E2EE.

## 3. Phạm vi công việc

- Phía SVC-04: migration `org.person_public_key` đúng TL-03 (CHECK `key_alg`, `source`, `status`, UQ `(tenant_id, fingerprint)`); đăng ký khoá thiết bị khi đăng nhập MFA lần đầu, ký bản ghi đăng ký bằng khoá danh bạ tenant (qua OpenBao, không giữ khoá trong mã), ghi sổ cái mọi thay đổi; thu hồi, hết hạn.
- Phía mission-core: kho `e2ee_key_wrap` cho `TASK_CONTENT` và `EVIDENCE_FILE` (UQ `(tenant_id, object_id, recipient_key_fingerprint)`), bắt buộc luôn có một bản bọc cho khoá phục hồi tổ chức; `getEvidenceVersion` trả khoá đã bọc của chính người gọi khi `hasE2ee`.
- Chia sẻ: nhận CEK bọc lại cho người nhận mới, kiểm ABAC trước khi chấp nhận, ghi `granted_by`; thu hồi: đặt `revoked_at`, chặn cấp URL tải bản mã cho người bị thu hồi.
- Bảo đảm C3 không rời ranh giới: không gửi tới SVC-14, OpenSearch, ClickHouse, không quét mã độc hay khử trùng (SVC-08 chỉ lưu bản mã); kiểm thử phủ từng điểm chặn.
- Phục hồi tổ chức: lưu khoá công khai phục hồi của tenant và nhật ký các lần phục hồi (bọc lại CEK cho thiết bị mới) có sổ cái; phần chia Shamir và giải mã nằm ở máy trạm cách ly, ngoài máy chủ.
- Điểm lệch cần lưu ý: hợp đồng OpenAPI chưa có endpoint danh bạ khoá công khai (đăng ký, tra cứu, thu hồi) và endpoint chia sẻ hay thu hồi CEK; không tự viết controller lệch hợp đồng, mở task sửa hợp đồng riêng (chạy `openapi-diff`) trước phần A và C, ghi vào `wiki/14`. Hàng khung đặt task này ở MOD-07 nhưng nội dung thuộc SVC-04, MOD-02, MOD-03; ghi rõ trong báo cáo.

## 4. Ngoài phạm vi

- Thư viện trình duyệt `@vt/e2ee` và giao diện (WEB); khoá trên di động (MOB); mã hoá C2 phong bì (NEN-015).

## 5. Tệp được phép sửa

- `backend/services/org-service/**` (chỉ phần `person_public_key` và migration tương ứng)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`, `.../product/**` (chỉ gói xử lý `e2ee_key_wrap`)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`, `.../product/**`
- `deploy/policies/` (chỉ quy tắc Rego chia sẻ C3)
- `wiki/08-bao-mat-frontend.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core,services/org-service -am verify` xanh, Testcontainers PostgreSQL + Kafka.
- Kiểm thử: không có đường mã nào ghi nội dung C3 dạng rõ (kiểm bằng ca dữ liệu đánh dấu và quét nhật ký, sự kiện, CSDL); đối tượng C3 thiếu bản bọc cho khoá phục hồi bị từ chối; người bị thu hồi không lấy được URL tải bản mã; thay đổi danh bạ khoá có bản ghi sổ cái.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
