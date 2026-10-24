# QA-05 · QT-04..QT-07 Hành trình đầu cuối: kế hoạch, giao việc, tiến độ, minh chứng, nghiệm thu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-05` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-03, CORE-03-07, CORE-02-22 |
| Truy vết | QT-04 → SCR-0401..SCR-0403; QT-05 → SCR-0501..SCR-0503; QT-06 → SCR-0601, SCR-0602; QT-07 → SCR-0701, SCR-0703 → API `/api/v1/plans*`, `/api/v1/tasks*`, `/api/v1/evidences*`, `/api/v1/files/*` → MOD-02, MOD-03, SVC-06, SVC-08 → bảng `task.plan_version`, `task.task`, `product.evidence_version` → EVT-03..EVT-08, EVT-24 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các hành trình đầu cuối nhóm QT-04..QT-07 trên hệ thống thật, đi trọn chuỗi giá trị trung tâm của sản phẩm: lập và duyệt kế hoạch quý, giao việc và nhận việc, cập nhật tiến độ và xử lý đề nghị điều chỉnh, nộp minh chứng có quét mã độc, nghiệm thu năm mức. Khi xong, một nhiệm vụ đi từ kế hoạch tới `HOAN_THANH` với minh chứng `DA_XAC_NHAN` hoàn toàn qua giao diện, và các nhánh trả lại, yêu cầu bổ sung, vượt ngưỡng duyệt đều được kiểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` các mục QT-04, QT-05, QT-06, QT-07.
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1 (trạng thái kế hoạch, nhiệm vụ, minh chứng; mức chất lượng 1/0,75/0,5/0,25/0).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md`.
- `contracts/openapi/modules/tasks.yaml` (`createPlan`, `savePlanVersion`, `checkPlanVersion`, `submitPlanVersion`, `returnPlanVersion`, `approvePlanVersion`, `diffPlanVersions`, `createTaskDraft`, `assignTask`, `acceptTask`, `createTaskFromDocument`, `updateTaskProgress`, `reportTaskBlocker`, `submitTaskChangeRequest`, `decideTaskChangeRequest`), `products.yaml` (`createFileUpload`, `uploadFilePart`, `completeFileUpload`, `getFileScanResult`, `createEvidence`, `submitEvidenceVersion`, `openEvidenceReview`, `acceptEvidenceVersion`, `requestEvidenceRevision`), `appraisals.yaml` (`previewAppraisalTaskScore`).

## 3. Phạm vi công việc

- Hành trình kế hoạch: AC-05 lập kế hoạch quý từ danh mục công việc, kiểm tra hợp lệ, gửi duyệt; AC-04 xem so sánh phiên bản, trả lại; AC-05 sửa, gửi lại; AC-04 phê duyệt; kiểm `NHAP` → `CHO_DUYET` → `TRA_LAI` → `CHO_DUYET` → `DA_DUYET` và EVT-03 `task.plan.approved.v1`.
- Hành trình giao việc: AC-05 giao nhiệm vụ từ kế hoạch đã duyệt, AC-06 nhận việc; kiểm EVT-04 và nhiệm vụ hiện trong "Việc của tôi"; hành trình tạo nhiệm vụ từ văn bản chỉ đạo (`createTaskFromDocument`) dùng văn bản thử nạp sẵn (EVT-24 giả lập qua dữ liệu khởi tạo).
- Hành trình tiến độ: AC-06 cập nhật tiến độ nhiều lần, báo điểm nghẽn; gửi đề nghị gia hạn; AC-05 quyết định; nhánh vượt ngưỡng đi qua luồng duyệt BPMN và người duyệt thay; kiểm EVT-05, EVT-06.
- Hành trình minh chứng: AC-06 tải tệp nhiều phần, chờ kết quả quét (`CHO_QUET` tới sạch), nộp minh chứng; kiểm EVT-07; tệp EICAR thử bị đánh dấu nhiễm và không nộp được.
- Hành trình nghiệm thu: AC-05 mở nghiệm thu, xem trước điểm (`previewAppraisalTaskScore`), chọn mức chất lượng, xác nhận với step-up AAL2; kiểm minh chứng `DA_XAC_NHAN`, nhiệm vụ `HOAN_THANH`, EVT-08; nhánh yêu cầu bổ sung và từ chối.
- Ca phủ định: người khởi tạo không được tự nghiệm thu (tách vai trò, máy chủ trả 403); gửi lại lệnh ghi với cùng `Idempotency-Key` không tạo bản ghi trùng.
- Mỗi QT có ít nhất một hành trình ở chế độ Đảng viên cao tuổi; thao tác thường ngày (cập nhật tiến độ) không quá 3 chạm từ trang chủ.
- Gợi ý tách: (a) QT-04 và QT-05; (b) QT-06; (c) QT-07.

## 4. Ngoài phạm vi

- Phiên bản di động SCR-0504, SCR-0702 (bộ kiểm thử MOB); hiệu năng tải lên (PERF-18, QA-10).
- Sửa lỗi phát hiện được (mở task sửa riêng).

## 5. Tệp được phép sửa

- `frontend/e2e/hanh-trinh/qt04-07/**`, `frontend/e2e/hanh-trinh/danh-muc.json` (trạng thái hành trình), `data/seed/e2e/**` (kể cả tệp EICAR thử dạng chuỗi chuẩn, không phải mã độc thật).

## 6. Tiêu chí hoàn thành

- `E2E_MODE=he-thong npm run e2e:he-thong -- --grep "QT-0[4-7]"` xanh trên server dev, hai lần liên tiếp, không lỗi console.
- Mỗi chuyển trạng thái và EVT nêu ở mục 3 được kiểm (qua API đọc và Kafka); ca phủ định trả đúng mã HTTP.
- Đếm số chạm của hành trình cập nhật tiến độ ≤ 3 và ghi trong báo cáo; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6, kèm bảng hành trình × kết quả.

## 7. Báo cáo

Theo AGENTS.md mục 6.
