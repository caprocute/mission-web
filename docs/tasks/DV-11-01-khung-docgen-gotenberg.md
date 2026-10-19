# DV-11-01 · SVC-11 Khung document-service, migration schema `docgen`, Gotenberg, job sinh 202

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | NEN-019, NEN-033; NEN-013 (job 202 và `Idempotency-Key`) phải gộp trước (bổ sung) |
| Truy vết | QT-11 bước 10 → API `POST /api/v1/dossiers/render-jobs`, `GET /api/v1/jobs/{id}` → SVC-11 → bảng `docgen.template`, `docgen.render_job`, `docgen.render_output`, `docgen.outbox_event` → EVT-17 `dossier.form.generated.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng document-service (Java 21, Spring Boot 3.5) với đường ống sinh tài liệu chuẩn: nhận yêu cầu sinh (REST 202 hoặc lệnh từ saga), trộn dữ liệu vào mẫu DOCX bằng docx4j, chuyển sang PDF/A-2b bằng Gotenberg 8, kiểm PDF/A bằng veraPDF, lưu tệp vào kho đối tượng và phát EVT-17. Khi xong, các task mẫu cụ thể (DV-11-02, DV-11-03) chỉ cần thêm mẫu và bộ ánh xạ dữ liệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-11 document-service" (L1142–1154: công nghệ, API, co giãn; lý do cấm iText 7).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema docgen (SVC-11)" (L3751–3795) với từ điển `template`, `render_job`, `render_output`; mục "Tổ chức bucket" (L4509, bucket `templates`, `dossier`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11 (L510–L724), "Quy tắc nghiệp vụ" (L579, BR-11-02, BR-11-03).
- `contracts/openapi/modules/dossiers.yaml` `createDossierRenderJob` (lược đồ `DossierRenderJobRequest`), `contracts/openapi/modules/jobs.yaml` `getJob`.
- Bản trích `extract/tl01-thanh-phan.md` mục C10, `extract/tl03-phan2.md` mục A9, `extract/tl02b-may-chu.md` QT-11.

## 3. Phạm vi công việc

- Tạo `backend/services/document/` bằng `scripts/new-service.sh`, gói `vn.viettel.okrkpi.docgen`; phụ thuộc docx4j 11, OpenPDF, client HTTP tới Gotenberg; không dùng iText 7 (thêm luật kiểm phụ thuộc trong build chặn `com.itextpdf`).
- Viết migration `db/migration/docgen` tạo `template` (UQ `tenant_id`,`form_code`,`version_no`; `subject_group_codes[]`; `object_key`, `content_sha256`; CHECK `status` IN (`NHAP`,`HIEU_LUC`,`NGUNG`)), `render_job` (UQ `request_event_id`; `input_sha256`; CHECK `status` IN (`CHO`,`DANG_SINH`,`XONG`,`LOI`); `attempts`, `error`), `render_output` (CHECK `output_kind` IN (`PDFA`,`DOCX`); `pdf_sha256`, `size_bytes`, `pdfa_validated`), cùng outbox, inbox, idempotency, RLS.
- Hiện thực `createDossierRenderJob` (202 + `Location: /api/v1/jobs/{id}`, nhận cả token người dùng và `serviceClient`) và trạng thái job cho `getJob`; idempotent theo `Idempotency-Key` và `request_event_id`.
- Viết worker sinh: lấy dữ liệu đã chuẩn hoá của form, kiểm `render_job.input_sha256` khớp `form_instance.data_snapshot_hash` (từ chối khi lệch), trộn DOCX, gọi Gotenberg chuyển PDF/A-2b, kiểm veraPDF (`pdfa_validated`), ghi tệp vào bucket `dossier` theo khoá có băm, ghi `render_output` và EVT-17 trong cùng giao dịch.
- Định nghĩa giao diện `FormRenderer` (mã mẫu, phiên bản, ánh xạ dữ liệu → trường mẫu) và kho mẫu đọc `docgen.template` theo phiên bản; một mẫu thử tối giản để kiểm thử đường ống.
- Thêm Gotenberg 8 và veraPDF vào profile dev và Testcontainers; metric hàng đợi job cho KEDA, thời gian sinh mỗi tệp, tỷ lệ lỗi.
- Điểm lệch cần lưu ý: TL-03 (L3751) ghi document-service "nhận dữ liệu đã chuẩn hoá trong thông điệp Kafka từ MOD-06", còn hợp đồng có `POST /dossiers/render-jobs` do SVC-11 sở hữu; task hiện thực REST theo hợp đồng và thêm consumer lệnh sinh từ saga dùng cùng use case (suy luận về tên topic, đặt tiền tố `sys.` và ghi vào `wiki/14`). Tiền tố `/dossiers` dùng chung với MOD-06 cần luật định tuyến ở gateway (ghi vào báo cáo).

## 4. Ngoài phạm vi

- Các mẫu nghiệp vụ Mẫu 1A–1D (DV-11-02), Mẫu 2, 3, bảng chấm điểm (DV-11-03), gói hồ sơ có tem (DV-11-04).
- Tệp DOCX mẫu theo văn bản pháp lý (DL-05); ký số (DV-10).

## 5. Tệp được phép sửa

- `backend/services/document/**`
- `backend/pom.xml` (chỉ thêm module)
- `infra/dev/` (Gotenberg trong profile backend)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/document -am verify` xanh: Testcontainers PostgreSQL 17, Kafka, Gotenberg 8, MinIO hoặc Ceph giả lập S3; ArchUnit; so khớp phản hồi với `dossiers.yaml`; `openapi-diff` không phá tương thích.
- Kiểm thử chứng minh: một yêu cầu sinh ra đúng một tệp PDF/A-2b được veraPDF xác nhận, `pdf_sha256` khớp tệp, một EVT-17 trong outbox; gửi lại cùng `Idempotency-Key` không sinh thêm; `input_sha256` lệch thì job `LOI` với lý do rõ.
- Một phiếu hai trang sinh trong khoảng 300–600 ms trên Gotenberg cục bộ (ghi số đo); build thất bại nếu có phụ thuộc iText.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
