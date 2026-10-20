# CORE-04-04 · QT-09/SCR-0901 Phiếu tự đánh giá: lưu nháp, kiểm tiêu chí chung, nộp chờ ký

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A đọc hồ sơ và lưu nháp; B nộp 202 và nhận EVT-18) |
| Phụ thuộc | CORE-04-03, CORE-08-14, DV-10-02 |
| Truy vết | QT-09 → SCR-0901 → API `GET /api/v1/appraisals/profiles/mine`, `GET .../profiles/{id}`, `PUT .../profiles/{id}/self-assessment`, `POST .../profiles/{id}/submit` → MOD-04 → `appraisal.self_assessment`, `appraisal.general_criteria_result`, `appraisal.rating_proposal` (`TU_DE_XUAT`) → SVC-10 → EVT-18 → EVT-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cán bộ xem hồ sơ quý của mình, lưu nháp phiếu tự đánh giá có kiểm xung đột, và ký số nộp phiếu; máy chủ kiểm lại tiêu chí chung bằng cel-java (không tin kết quả cel-js của trình duyệt), trả 202 và chỉ chuyển hồ sơ sang `CHO_THAM_DINH` khi nhận EVT-18 của SVC-10. Đây là bước đầu tiên có chữ ký số trong QT-09.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Quy tắc nghiệp vụ" (dòng 87: BR-09-05, BR-09-12, BR-09-15), "Ngoại lệ và xử lý" (dòng 113, ngoại lệ 3–5), "SCR-0901. Phiếu tự đánh giá" (dòng 129; bảng trường, giải trình 20–2.000 ký tự, HTXSNV cần minh chứng `DA_XAC_NHAN`, MSG-09-01..05).
- TL-03 bảng `self_assessment` (dòng 2828), `general_criteria_result` (dòng 2813).
- `contracts/openapi/modules/appraisals.yaml`: `listMyAppraisalProfiles`, `getAppraisalProfile`, `saveAppraisalSelfAssessment`, `submitAppraisalSelfAssessment`; `contracts/openapi/modules/dossiers.yaml`: `createEsignRequest`, `previewDossierForm` (Mẫu 1B/2 nháp, chỉ để biết luồng).
- Bản trích `tl02b §QT-09 BE-Q09-03, 04`.

## 3. Phạm vi công việc

- Hiện thực `listMyAppraisalProfiles` và `getAppraisalProfile` (kiểm OPA: chủ hồ sơ, người thẩm định, người quyết định trong phạm vi); hồ sơ `CHUA_LAP` trả kèm thông tin để giao diện hiện MSG-09-01.
- `saveAppraisalSelfAssessment`: `If-Match` theo `row_version`, 409 + MSG-09-07 khi lệch; mã hoá các trường C2 (`strengths_enc`, `weaknesses_enc`, `remedial_plan_enc`, `content_enc`, `breakthrough_enc`) bằng `FieldEncryptor`; kiểm JSON Schema có `x-cel` bằng bộ kiểm của CORE-08-14.
- Kiểm lại tiêu chí chung ở máy chủ bằng cel-java theo BR-09-05 và lưu `general_criteria_result` giai đoạn `TU_DANH_GIA`; kết quả khác cel-js thì máy chủ thắng và trả lỗi theo trường.
- Kiểm: mức tự đề xuất khác mức tham khảo phải có giải trình 20–2.000 ký tự (MSG-09-02); đề xuất HTXSNV phải có mô tả giải pháp đột phá và ≥ 1 minh chứng `DA_XAC_NHAN` (MSG-09-03).
- `submitAppraisalSelfAssessment`: `Idempotency-Key`, tính `content_hash`, tạo yêu cầu ký tới SVC-10, trả 202 + `Location` tới tác vụ; hồ sơ giữ `TU_DANH_GIA` với cờ chờ ký.
- Consumer EVT-18 cho tài liệu tự đánh giá: chuyển `CHO_THAM_DINH`, ghi `rating_proposal` mức `TU_DE_XUAT`, phát EVT-11 và EVT-21 (MSG-09-04); ký lỗi thì giữ nguyên dữ liệu và trả MSG-09-05 qua trạng thái tác vụ.
- Quá hạn tự đánh giá (timer của DV-06-04): hồ sơ chuyển thẩm định với cờ "không tự đánh giá", không tự điền mức.

## 4. Ngoài phạm vi

- Dịch vụ ký và nhà cung cấp MySign (DV-10-02); sinh Mẫu 1B/2 (DV-11-xx); thẩm định (CORE-04-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `deploy/policies/` (chỉ quy tắc Rego đọc hồ sơ đánh giá)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; phản hồi khớp `appraisals.yaml`.
- Kiểm thử: hai lần lưu đồng thời, lần sau 409; nộp hai lần cùng khoá chỉ một yêu cầu ký; hồ sơ chỉ sang `CHO_THAM_DINH` sau EVT-18; cột `_enc` không chứa văn bản rõ.
- p95 lưu nháp ≤ 800 ms, đọc hồ sơ ≤ 300 ms (NFR-03) trong kiểm thử tải nhỏ.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
