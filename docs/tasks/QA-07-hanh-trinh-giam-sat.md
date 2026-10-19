# QA-07 · QT-11..QT-18 Hành trình đầu cuối: kiểm điểm, hồ sơ số, khiếu nại, mở lại kỳ, cảnh báo, Policy Pack, bảng điều hành, AI, UI Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (hành trình trợ lý AI QT-17 hoàn thiện ở đợt 3) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-06, CORE-06-08, CORE-07-11, DV-15-06 |
| Truy vết | QT-11..QT-18 → SCR-1101, SCR-1102, SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401, SCR-1501..SCR-1504, SCR-1601..SCR-1603, SCR-1701, SCR-1801, SCR-1802 → API `/api/v1/dossiers*`, `/api/v1/oversight*`, `/api/v1/ledger*`, `/api/v1/policies*`, `/api/v1/analytics*`, `/api/v1/ai*`, `/api/v1/ui*` → MOD-04..MOD-08, SVC-09, SVC-14, SVC-15 → EVT-13, EVT-19, EVT-20, EVT-22, EVT-23, EVT-25 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực các hành trình đầu cuối còn lại của danh mục QA-01 cho nhóm QT-11..QT-18 trên hệ thống thật: hội nghị kiểm điểm và gói hồ sơ số ký số, khiếu nại và giải trình, mở lại kỳ hai người duyệt kèm tra cứu sổ cái, xử lý cảnh báo bất thường, soạn và phát hành Policy Pack qua ca vàng, bảng điều hành đi xuống tới minh chứng, trợ lý AI chỉ đọc, và phát hành UI Pack. Khi xong, toàn bộ khoảng 40 hành trình của danh mục chạy xanh, là điều kiện vào kiểm thử chấp nhận QA-16.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` các mục QT-11 đến QT-18; `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md` (SCR-1504).
- `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/03-giao-dien-theo-tenant.md`, `wiki/15a-ma-tran-phan-quyen.md`.
- `contracts/openapi/modules/dossiers.yaml` (`createDossierMeeting`, `createDossierMeetingMinutes`, `closeDossierMeeting`, `createDossierPackage`, `signDossierPackage`, `exportDossierPackage`), `oversight.yaml` (`createComplaint`, `submitComplaint`, `acceptComplaint`, `requestComplaintExplanations`, `respondComplaint`, `createReopenRequest`, `simulateReopenRequest`, `approveReopenRequest`, `createLedgerVerifyRun`, `getLedgerInclusionProof`, `listAnomalyAlerts`, `createAlertAction`), `policies.yaml` (`createPolicyPackVersion`, `runPolicyTests`, `submitPolicyPackVersion`, `approvePolicyPackVersion`, `simulateWorkflowBinding`), `platform.yaml` (`queryAnalytics`, `createDashboard`, `getTraceChain`, `assistOkrDraft`, `reviewOkrKeyResults`, `decideAiSuggestion`), `config.yaml` (`createUiPackVersion`, `previewUiPackVersion`, `submitUiPackVersion`, `publishUiPackVersion`).

## 3. Phạm vi công việc

- QT-11: chủ trì mở hội nghị kiểm điểm, thành viên góp ý, lập biên bản, đóng hội nghị; lập gói hồ sơ số, kiểm danh mục, ký số (bộ ký giả lập ở dev), xuất gói; tài liệu tải về có tệp kê khai.
- QT-12, QT-13: AC-06 gửi khiếu nại (EVT-25), người xử lý tiếp nhận, yêu cầu giải trình, kết luận, trả lời; yêu cầu mở lại kỳ, mô phỏng tác động, hai người duyệt khác nhau có step-up (EVT-22, kỳ `MO_LAI_HAU_KIEM`); tra cứu sổ cái, chạy kiểm toàn vẹn, xem bằng chứng bao hàm.
- QT-14: AC-10 xem cảnh báo bất thường (EVT-20 tạo từ dữ liệu khởi tạo), giao kiểm tra, kết luận.
- QT-15: AC-03 tạo phiên bản Policy Pack, sửa một quy tắc, chạy ca vàng (phải đạt 100% mới gửi duyệt được), gửi, người duyệt phát hành (EVT-13); cấu hình luồng duyệt bằng mẫu và mô phỏng ràng buộc.
- QT-16, QT-17: AC-04 xem bảng điều hành, đi xuống tới minh chứng của một chỉ số (chuỗi truy vết); AC-06 dùng trợ lý AI gợi ý KR, chấp nhận hoặc bỏ qua gợi ý; kiểm AI không có quyền ghi (gợi ý chỉ áp dụng khi người dùng quyết định).
- QT-18: AC-02 tạo phiên bản UI Pack, xem trước, gửi, người duyệt phát hành (EVT-23); tải lại trang thấy giao diện mới; UI Pack vượt 30 KB bị từ chối.
- Mỗi QT có ít nhất một hành trình ở chế độ Đảng viên cao tuổi; tách vai trò và bốn mắt được kiểm bằng ca phủ định.
- Gợi ý tách: (a) QT-11..QT-13; (b) QT-14..QT-16; (c) QT-17, QT-18.

## 4. Ngoài phạm vi

- SCR-1803 chế độ người cao tuổi trên di động (bộ kiểm thử MOB); hiệu năng dashboard và AI (PERF-24, PERF-26; QA-11).
- Sửa lỗi phát hiện được (mở task sửa riêng).

## 5. Tệp được phép sửa

- `frontend/e2e/hanh-trinh/qt11-18/**`, `frontend/e2e/hanh-trinh/danh-muc.json` (trạng thái hành trình), `data/seed/e2e/**`.

## 6. Tiêu chí hoàn thành

- `E2E_MODE=he-thong npm run e2e:he-thong -- --grep "QT-1[1-8]"` xanh trên server dev, hai lần liên tiếp, không lỗi console; hành trình QT-17 được đánh dấu bỏ qua có lý do nếu ai-gateway chưa chạy ở dev.
- Mọi EVT nêu ở mục 3 được kiểm thấy; ca phủ định bốn mắt, tách vai trò trả đúng mã HTTP.
- Toàn bộ danh mục QA-01 chạy một lượt đầy đủ xanh (hoặc liệt kê hành trình còn chặn và lý do).
- Báo cáo cuối task theo AGENTS.md mục 6, kèm bảng hành trình × kết quả.

## 7. Báo cáo

Theo AGENTS.md mục 6.
