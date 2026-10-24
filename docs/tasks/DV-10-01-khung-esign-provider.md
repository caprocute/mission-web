# DV-10-01 · SVC-10 Khung esign-service, migration schema `esign`, giao diện `SignatureProvider`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-10-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | NEN-019, NEN-033; cần NEN-006 (sinh giao diện từ OpenAPI) và NEN-014 (kiểm step-up `acr`/`auth_time`) đã gộp (bổ sung, vì task hiện thực API AAL2) |
| Truy vết | QT-04, QT-09, QT-11, QT-12, QT-13, QT-15 → API `GET/POST /api/v1/esign/requests`, `GET /api/v1/esign/requests/{id}`, `GET /api/v1/esign/certificates/mine` → SVC-10 → bảng `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref`, `esign.outbox_event` → EVT-18 `esign.signature.completed.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng esign-service (Java 21, Spring Boot 3.5) từ mẫu service của NEN-019, tạo schema `esign` đúng TL-03, định nghĩa SPI `SignatureProvider` cắm được nhiều nhà cung cấp và hiện thực bốn API công khai theo hợp đồng với một nhà cung cấp giả lập. Khi xong, các module nghiệp vụ tạo được yêu cầu ký bất đồng bộ, theo dõi trạng thái và nhận EVT-18 khi hoàn tất, trước khi có nhà cung cấp thật.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-10 esign-service" (L1118–1140, "Thiết kế giao diện nhà cung cấp") và "Ký số tại mốc pháp lý" (L2626).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema esign (SVC-10)" (L3694–3749) với từ điển `sign_request`, `sign_item`, `certificate_ref`.
- `contracts/openapi/modules/dossiers.yaml` phần `/esign/*` và lược đồ `EsignRequest`, `EsignRequestCreate`, `EsignPurpose`, `EsignProvider`, `EsignRequestStatus`, `EsignSignatureFormat`, `EsignCertificate`.
- `00-co-so-thiet-ke-chung.md` mục 5 (SVC-10), mục 7 (EVT-18), mục 13; bản trích `extract/tl01-thanh-phan.md` mục C9, `extract/tl03-phan2.md` mục A8.
- `wiki/15a-ma-tran-phan-quyen.md` dòng API esign; `wiki/06-quy-uoc-api.md`.

## 3. Phạm vi công việc

- Tạo `backend/services/esign/` bằng `scripts/new-service.sh` của NEN-019, gói gốc `vn.viettel.okrkpi.esign`, bốn lớp `api`, `application`, `domain`, `infrastructure`; gói `spi` chứa `SignatureProvider` với `id()`, `supportedFormats()`, `begin()`, `poll()`, `collect()` như TL-01.
- Viết migration Flyway `db/migration/esign` tạo `sign_request` (CHECK `purpose`, `provider`, `status` đúng bộ giá trị TL-03; `is_batch`; `expires_at`; `completed_at`), `sign_item` (`document_sha256`, `signature_format` IN (`PADES_LTA`,`CADES_T`,`JWS`), `signature_value`, `signed_object_key`, FK `certificate_ref_id`), `certificate_ref` (UQ `tenant_id`,`cert_sha256`), cùng `outbox_event`, `inbox_processed`, `idempotency_key` theo NEN-032, RLS theo NEN-033.
- Hiện thực `createEsignRequest` (202 + `Location`, `Idempotency-Key`, AAL2 với `auth_time` ≤ 5 phút): máy chủ lấy danh sách tài liệu và băm từ module nguồn theo `sourceModule`/`sourceRefId` (client không gửi băm), kiểm người gọi là chủ thể ký (403 `ESIGN-403-NOT-SIGNER`), 409 `ESIGN-409-SOURCE-NOT-READY` và `ESIGN-409-REQUEST-PENDING`, 422 `ESIGN-422-CERTIFICATE-INVALID`, `ESIGN-422-PROVIDER-UNAVAILABLE`.
- Hiện thực `getEsignRequest` (kèm `itemCount`, `signedCount`, `Retry-After`), `listMyEsignRequests` (phân trang con trỏ), `listMyEsignCertificates`.
- Viết worker thăm dò `poll()` theo `expires_at` (mặc định 120 giây), chuyển trạng thái `TAO` → `CHO_XAC_NHAN` → `DA_KY` | `TU_CHOI` | `HET_HAN` | `LOI`; khi `DA_KY` lưu chữ ký qua `collect()`, ghi EVT-18 vào outbox cùng giao dịch.
- Viết `FakeSignatureProvider` (profile dev/test) để các task khác kiểm thử đầu cuối; không bao giờ bật ở production (kiểm bằng cấu hình).
- Điểm lệch cần lưu ý: TL-01 ghi định dạng `PADES_B_LTA`, `CADES_B_T`, `XADES_B_T` trong SPI còn TL-03 và hợp đồng dùng `PADES_LTA`, `CADES_T`, `JWS`; task giữ giá trị TL-03/hợp đồng ở CSDL và API, ánh xạ trong SPI. Cách lấy băm từ module nguồn không có hợp đồng công khai; dùng cổng nội bộ có Resilience4j (suy luận), ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Nhà cung cấp thật: MySign (DV-10-02), HSM/TSA (DV-10-03), Viettel-CA/CA Chính phủ (DV-10-04), Ban Cơ yếu (DV-10-05); ký lô (DV-10-06).
- Consumer EVT-18 ở các module nghiệp vụ (các task CORE tương ứng).

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `backend/pom.xml` (chỉ thêm module `services/esign`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh: kiểm thử đơn vị, Testcontainers PostgreSQL 17 + Kafka, ArchUnit, so khớp phản hồi với `dossiers.yaml`, `openapi-diff` không phá tương thích.
- Kiểm thử chứng minh: tạo yêu cầu với nhà cung cấp giả lập đi hết `CHO_XAC_NHAN` → `DA_KY` và có đúng một EVT-18 trong outbox; quá `expires_at` chuyển `HET_HAN`; gọi lại cùng `Idempotency-Key` trả cùng yêu cầu.
- Độ phủ dòng ≥ 80% cho `domain` và `application`; SpotBugs + Find Security Bugs không có lỗi mức cao; khoá riêng của người ký không bao giờ xuất hiện trong CSDL hay nhật ký.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
