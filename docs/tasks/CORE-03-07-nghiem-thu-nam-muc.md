# CORE-03-07 · SCR-0703 Nghiệm thu năm mức: AAL2, tách vai trò, nhận xét C2, EVT-08

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | L (nên tách: phần A xác nhận và EVT-08; phần B yêu cầu bổ sung, từ chối) |
| Phụ thuộc | CORE-03-06, NEN-014, NEN-015 |
| Truy vết | QT-07 → SCR-0703 → API `POST /api/v1/evidences/{id}/versions/{v}/accept` (`acceptEvidenceVersion`), `.../request-revision` (`requestEvidenceRevision`), `.../reject` (`rejectEvidenceVersion`) → MOD-03 → `product.acceptance`, `product.evidence_version` → EVT-08 (`product.evidence.accepted.v1`), EVT-21; EVT-06 qua MOD-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người nghiệm thu xác nhận phiên bản minh chứng với mức chất lượng năm mức, hoặc yêu cầu bổ sung, hoặc từ chối, trên phiên AAL2 còn hạn và đúng nguyên tắc tách vai trò. Xác nhận ghi `product.acceptance` có nhận xét mã hoá C2, phát EVT-08 để SVC-07 tính điểm dần, và liên động nhiệm vụ sang `HOAN_THANH` khi đủ sản phẩm. Đây là điểm duy nhất sinh dữ liệu chất lượng (cấu phần B) cho tính điểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07: "Quy tắc nghiệp vụ" (dòng 1917: BR-07-05..08, BR-07-11), "SCR-0703. Nghiệm thu chất lượng 5 mức" (dòng 2030; bảng trường, quy tắc mọi tiêu chuẩn phải được đánh giá dòng 2057, nguyên nhân chỉnh sửa dòng 2059, chức năng dòng 2068–2070, MSG-07-09, MSG-07-10, MSG-07-12).
- TL-03 bảng `product.acceptance` (dòng 2581); mục "Mã hoá phong bì cho cột C2" (dòng 4148).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Mã hoá đầu cuối cho dữ liệu C3" và phần mô hình khoá C2 (quanh dòng 2252–2293).
- `contracts/openapi/modules/products.yaml`: `acceptEvidenceVersion`, `requestEvidenceRevision`, `rejectEvidenceVersion`.
- `wiki/15-phan-quyen.md` (tách vai trò, step-up), `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0703; bản trích `tl02a §QT-07 BE-07-9`.

## 3. Phạm vi công việc

- `acceptEvidenceVersion`: yêu cầu claim `acr` mức AAL2 và `auth_time` ≤ 15 phút (BR-07-11), thiếu thì trả lỗi step-up theo thư viện NEN-014; người nghiệm thu khác người nộp và người phối hợp (BR-07-06) kiểm bằng OPA trước, trigger CSDL là lớp sau.
- Kiểm `quality_level` ∈ {1; 0,75; 0,5; 0,25; 0} (BR-07-07), mọi tiêu chuẩn trong `criteria_results` đã được đánh giá (MSG-07-09), `quality_level < 1` phải có nhận xét (BR-07-08, MSG-07-10), `revision_cause` bắt buộc khi đã có phiên bản bị yêu cầu bổ sung.
- Mã hoá nhận xét vào `comment_enc` + `dek_id` bằng `FieldEncryptor` (mục đích DEK theo TL-03), không ghi nhận xét rõ vào nhật ký hay payload sự kiện.
- Xác nhận thành công: phiên bản `DA_XAC_NHAN`, cập nhật `deliverable.quantity_accepted`, ghi outbox EVT-08 cùng giao dịch, liên động nhiệm vụ qua CORE-03-02, trả MSG-07-12.
- `requestEvidenceRevision` và `rejectEvidenceVersion`: bắt buộc nhận xét hoặc lý do (BR-07-08), chuyển `YEU_CAU_BO_SUNG` hoặc `TU_CHOI`, đưa nhiệm vụ về `DANG_THUC_HIEN`, phát EVT-21 cho người nộp; `TU_CHOI` không nộp lại trước hạn nghiệm thu tồn đọng thì B = 0 (ghi chú cho SVC-07, không tính ở đây).
- Mọi quyết định ghi sổ cái qua outbox cùng ngữ cảnh phiên (BR-00-08); đăng ký lược đồ Avro EVT-08 nếu chưa có.

## 4. Ngoài phạm vi

- Tính A, B, C, KPI và `POST /appraisals/score-preview` (DV-07-03, DV-07-08); mở nghiệm thu (CORE-03-06); nghiệm thu thay theo uỷ quyền (CORE-03-08).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-08 nếu chưa có)
- `deploy/policies/` (chỉ quy tắc Rego tách vai trò nghiệm thu và ca `opa test`)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, gồm Testcontainers PostgreSQL + Kafka + Keycloak (token có và không có `acr` AAL2).
- Kiểm thử chứng minh: `auth_time` quá 15 phút bị chặn; người phối hợp nghiệm thu bị 403 với mã lỗi nghiệp vụ; `quality_level = 0,5` thiếu nhận xét bị 422; cột `comment_enc` trong CSDL không chứa văn bản rõ; EVT-08 phát đúng một lần khi gửi lại cùng `Idempotency-Key`.
- `opa test` cho gói chính sách nghiệm thu xanh.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
