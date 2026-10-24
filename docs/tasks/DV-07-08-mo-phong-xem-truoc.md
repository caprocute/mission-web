# DV-07-08 · QT-07, QT-12, QT-13 API mô phỏng và xem trước điểm với phiên bản Policy Pack gốc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | DV-07-05 |
| Truy vết | QT-07 (SCR-0703), QT-12 (SCR-1202), QT-13 → `POST /api/v1/appraisals/score-preview` (`previewAppraisalTaskScore`), `POST /api/v1/appraisals/profiles/{id}/simulate` (`simulateAppraisalScore`), phục vụ `POST /api/v1/oversight/reopen-requests/{id}/simulate` (`simulateReopenRequest`, owner MOD-07) → SVC-07 → bảng `scoring.score_input_v`, `scoring.score_result` (chỉ đọc); BR-12-08, BR-13-06, BR-09-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người nghiệm thu xem trước A, B, C, KPI trước khi xác nhận; người xử lý khiếu nại và người đề nghị mở lại kỳ tính thử điểm với điều chỉnh đề nghị, luôn bằng đúng phiên bản Policy Pack của kỳ bị khiếu nại hay mở lại. Khi xong, mọi lời gọi mô phỏng không ghi gì vào kết quả hay ảnh chụp, không phát EVT-10, và trả kết quả đủ nhanh để hiển thị ngay trên màn hình.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 SCR-0703 xem trước điểm (dòng 2061, 2067).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-12 mô phỏng (dòng 771, 915), BR-12-08; QT-13 mô phỏng ảnh hưởng mở lại (dòng 1059–1067), BR-13-06.
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` `previewAppraisalTaskScore`, `simulateAppraisalScore` (schema `AppraisalSimulationInput`); `contracts/openapi/modules/oversight.yaml` `simulateReopenRequest`.
- Wiki: `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0703, SCR-1202), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `previewAppraisalTaskScore`: nhận đầu vào nhiệm vụ đang nghiệm thu (mức chất lượng dự kiến, số sản phẩm), tính A, B, C, KPI theo gói của kỳ, không lưu.
- Hiện thực `simulateAppraisalScore` (Idempotency-Key): lấy `score_input_v` và `policy_version` từ kết quả gốc của hồ sơ, áp danh sách điều chỉnh cấu phần (kiểm giá trị mới nằm trong miền hợp lệ của cấu phần), tính lại điểm và mức, trả cả giá trị cũ và mới; không ghi ảnh chụp, không đổi trạng thái (BR-09-16, BR-12-08).
- Cung cấp API nội bộ cho MOD-07 tính thử theo phạm vi yêu cầu mở lại (danh sách hồ sơ, điều chỉnh), dùng phiên bản gói gốc (BR-13-06), trả tổng hợp ảnh hưởng.
- Kiểm quyền: người xem trước là người nghiệm thu của nhiệm vụ; người mô phỏng là người xử lý khiếu nại hoặc người đề nghị mở lại, kiểm qua OPA.
- Kiểm thử: mô phỏng không tạo dòng mới trong `score_result`, `score_trace`, không có bản ghi outbox; dùng đúng phiên bản cũ dù đã có phiên bản mới phát hành; điều chỉnh ngoài miền bị 422; kiểm thử hợp đồng hai thao tác.
- Điểm lệch cần lưu ý: TL-02a gọi xem trước là `POST /api/v1/appraisals/score-preview` dưới tiền tố `appraisals` dù do SVC-07 tính; hợp đồng giữ đường dẫn này với `x-mission.owner: SVC-07`, cổng định tuyến thao tác này sang scoring-service (DV-01-01).

## 4. Ngoài phạm vi

- Lớp API `simulateReopenRequest` ở MOD-07 (CORE-07); `evaluatePolicyVersion` và chạy test Policy Pack ở MOD-05 (CORE-05).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm kiểm thử hợp đồng và các ca ở mục 3; `openapi-diff` không phá tương thích.
- Đo p95 mô phỏng một hồ sơ ≤ 800 ms (ngưỡng API ghi NFR-03); ghi số đo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
