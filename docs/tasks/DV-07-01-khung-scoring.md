# DV-07-01 · SVC-07 Khung scoring-service và migration schema `scoring`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | NEN-019, NEN-033 |
| Truy vết | QT-07, QT-08, QT-09 → SVC-07 scoring-service → bảng `scoring.score_job`, `scoring.score_input_v`, `scoring.score_result`, `scoring.score_trace`, `scoring.outbox_event`, `scoring.inbox_processed` → EVT-10 `scoring.score.computed.v1`; ADR-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng scoring-service (Java 21, Spring Boot 3.5, Spring Kafka) là bộ máy tính thuần không giữ trạng thái nghiệp vụ, với lược đồ `scoring` đúng TL-03 và thư viện chuẩn hoá, băm đầu vào dùng chung. Khi xong, các task DV-07 sau chỉ việc thêm nạp chính sách, chuỗi tính và consumer; mọi kết quả có thể truy về đầu vào bằng `input_hash` (SHA-256 của dạng chuẩn hoá RFC 8785).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-07 scoring-service" (dòng 1072–1084).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "SVC-07 scoring-service" (dòng 500) và "Schema scoring (SVC-07)" (dòng 3316–3389), quy ước "Cột chung của bảng nghiệp vụ" (dòng 97) và "Bảng outbox_event" (dòng 768).
- Baseline `00-co-so-thiet-ke-chung.md` mục 5 (SVC-07), 7 (EVT-10), 9 (schema `scoring`).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` các thao tác có `x-mission.owner: SVC-07` (để sinh giao diện); lược đồ Avro EVT-10 trong `contracts/events/avro/`.
- Wiki: `wiki/04-ma-dinh-danh-va-truy-vet.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Sinh dịch vụ bằng `scripts/new-service.sh`; giao diện API sinh từ các thao tác owner SVC-07 trong `appraisals.yaml` (`interfaceOnly=true`), chưa hiện thực nghiệp vụ.
- Migration: `score_job` (`job_kind` `TAM_TINH`, `CHOT_KY`, `TINH_LAI_HAU_KIEM`, `KIEM_TRA_TAI_LAP`; `trigger_event_id`; `total_items`, `done_items`, `failed_items`; `status` `CHO`, `DANG_CHAY`, `XONG`, `LOI`), `score_input_v` (UQ `period_bucket`, `tenant_id`, `input_hash`; `input_doc` jsonb với `ALTER ... SET COMPRESSION lz4`; không chứa C2), `score_result` (`input_hash`, `policy_content_hash`, `engine_version`, `result_doc`, `result_hash`, `status` `OK`/`LOI`), `score_trace` (`rule_code`, `legal_ref`, `inputs`, `output`), phân vùng theo `period_bucket` bằng `fn_create_period_partitions`, RLS mọi bảng, outbox, inbox.
- Viết thư viện chuẩn hoá JSON theo RFC 8785 (JCS) cộng chuẩn hoá Unicode NFC và băm SHA-256, kèm bộ vector kiểm thử đặt ở nơi ledger-service (Go) cũng đọc được để hai bên cho cùng kết quả (suy luận theo TL-03).
- Viết đường ghi lô: `COPY` 1.000 dòng vào bảng tạm rồi `INSERT ... SELECT` cho `score_result` và `score_trace`.
- Viết phát EVT-10 qua outbox trong cùng giao dịch ghi `score_result`.
- Kiểm thử: Testcontainers PostgreSQL cho migration, RLS hai tenant, ghi lô; vector JCS; đo đường ghi lô và so với mục tiêu 8.000 hồ sơ/giây trên một cụm (TL-03 dòng 3324; ghi số đo trên máy kiểm thử, không bắt đạt trên máy nhỏ).

## 4. Ngoài phạm vi

- Nạp Policy Pack (DV-07-02); chuỗi tính (DV-07-03); consumer sự kiện (DV-07-04); tính lại hàng loạt (DV-07-06).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `backend/libs/` (chỉ thư viện chuẩn hoá JCS nếu đặt dùng chung, ghi lý do)
- `backend/pom.xml`
- `data/golden/` (vector kiểm thử JCS)
- `deploy/charts/scoring-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm Testcontainers, ArchUnit, lint migration (NEN-030).
- `helm lint deploy/charts/scoring-service` không lỗi.
- Không có lệnh `UPDATE` hay `DELETE` nào trên `score_result`, `score_trace` trong mã (kết quả chỉ ghi thêm).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
