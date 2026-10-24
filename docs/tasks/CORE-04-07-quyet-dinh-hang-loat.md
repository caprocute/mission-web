# CORE-04-07 · QT-09/SCR-0904 Quyết định xếp loại hàng loạt và ký lô

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A tạo lô, kiểm điều kiện, ghi mức; B job 202, huỷ, tiếp tục lô dở) |
| Phụ thuộc | CORE-04-05, DV-10-06 |
| Truy vết | QT-09 → SCR-0904 → API `POST /api/v1/appraisals/batch-decisions` (`createAppraisalBatchDecision`), `GET .../batch-decisions/{id}`, `POST .../batch-decisions/{id}/cancel`, `GET /api/v1/jobs/{id}` → MOD-04 → `appraisal.batch_decision`, `appraisal.rating_proposal` (`QUYET_DINH`) → SVC-10 ký lô (ADR-31) → EVT-18, EVT-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người quyết định (AC-08) chọn tối đa 1.000 hồ sơ `CHO_QUYET_DINH`, ghi mức quyết định, xác thực bước lên một lần và gửi ký lô; máy chủ kiểm quy tắc người đứng đầu, phân bố mức HTXSNV, số quyết định không trùng, rồi trả 202 và theo dõi tiến độ. Quyết định một hồ sơ cũng đi qua lô một phần tử (điểm chốt AP-02 trong hợp đồng). Đây là bước pháp lý chốt kết quả xếp loại quý.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09: "Quy tắc nghiệp vụ" (dòng 87: BR-09-12, BR-09-13, BR-09-15, BR-09-17), "Ngoại lệ và xử lý" (dòng 113, ngoại lệ 4 ký lỗi), "SCR-0904. Quyết định xếp loại hàng loạt và ký số" (dòng 282, MSG-09-13..16).
- TL-03 bảng `batch_decision` (dòng 2954), DDL `rating_proposal` (dòng 2728).
- TL-01 mục G3 lớp 2 ký số bất đồng bộ và ADR-31 (ký lô 1.000 phiếu).
- `contracts/openapi/modules/appraisals.yaml`: `createAppraisalBatchDecision` (mô tả lỗi `APPRAISAL-409-DECISION-NO-DUPLICATE`, `APPRAISAL-422-HEAD-TOO-EARLY`, `APPRAISAL-422-REASON-REQUIRED`, `APPRAISAL-409-COMPLAINT-OPEN`, `APPRAISAL-422-CALIBRATION-REQUIRED`, `APPRAISAL-409-VERSION-CONFLICT`), `getAppraisalBatchDecision`, `cancelAppraisalBatchDecision`; `contracts/openapi/modules/jobs.yaml`.
- Bản trích `tl02b §QT-09 BE-Q09-09, 11`.

## 3. Phạm vi công việc

- Tạo lô: `Idempotency-Key`, AAL2 step-up một lần cho cả lô, 1–1.000 mục, mỗi mục có `rowVersion`; một mục lệch thì từ chối cả lô với 409 và `errors[]` chỉ rõ mục.
- Ghi `rating_proposal` mức `QUYET_DINH` cho từng hồ sơ trong cùng giao dịch với `batch_decision` (`profiles_hash`, `profile_count`, `decision_no` UQ trong năm của đơn vị); đổi mức so với đề xuất thì bắt buộc lý do ≥ 20 ký tự.
- Kiểm BR-09-13: hồ sơ người đứng đầu chỉ quyết định khi các đơn vị trực thuộc đã có mức (422 + MSG-09-14).
- Kiểm BR-09-17 ở máy chủ: tỷ lệ HTXSNV của danh sách vượt ngưỡng khối (tham số Policy Pack, mặc định 20%) mà `calibrationAcknowledged = false` thì 422 + MSG-09-13.
- Kiểm OPA: người quyết định có vai trò `decider` với đơn vị và khác người thẩm định của từng hồ sơ (BR-09-14).
- Gửi yêu cầu ký lô tới SVC-10, trả 202 + `Location: /api/v1/jobs/{id}`; cập nhật tiến độ job (đã ký/tổng/lỗi, MSG-09-15), lô `CHO_KY` → `DA_KY` hoặc `LOI_MOT_PHAN`; hồ sơ lỗi ký giữ `CHO_QUYET_DINH`.
- `cancelAppraisalBatchDecision`: huỷ phần chưa ký, lô đã `DA_KY` trả 409 `APPRAISAL-409-BATCH-COMPLETED`; tiếp tục lô dở sau khi dịch vụ ký phục hồi, không tạo trùng chữ ký.
- Điểm lệch cần lưu ý: TL-02b ghi BR-09-17 là cảnh báo kiểm trên giao diện; hợp đồng chặn bằng 422 khi chưa xác nhận đã hiệu chỉnh. Chọn hợp đồng (API-first): máy chủ chặn cho tới khi người dùng xác nhận, không chặn tuyệt đối.

## 4. Ngoài phạm vi

- Nhận EVT-18 chuyển `DA_KY_SO` và phát EVT-12 (CORE-04-08); ký lô HSM (DV-10-06); chặn quyết định năm khi còn khiếu nại (CORE-04-10).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `deploy/policies/` (chỉ quy tắc Rego cho quyết định xếp loại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka + Keycloak; phản hồi và mã lỗi khớp `appraisals.yaml`.
- Kiểm thử: lệnh tạo lô 1.000 hồ sơ trả 202 với p95 ≤ 800 ms (ngân sách ghi NFR-03; TL-01 G3 ước tính phần trả 202 khoảng 30 ms), kết quả đo ghi vào báo cáo; lô 1.001 bị 422; gửi lại cùng khoá không tạo lô mới; hồ sơ người đứng đầu sớm bị 422.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
