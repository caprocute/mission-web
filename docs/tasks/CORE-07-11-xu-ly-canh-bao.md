# CORE-07-11 · QT-14/SCR-1401 Xử lý cảnh báo: hợp lệ, giao kiểm tra, kết luận; quyền inspector; ngưỡng phát hiện có phiên bản

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-10 (bổ sung đề xuất: CORE-08-07 cho `config.tenant_setting`, CORE-07-07 cho tạo yêu cầu mở lại khi có vi phạm) |
| Truy vết | QT-14 → SCR-1401 → API `GET /api/v1/oversight/alerts` (`listAnomalyAlerts`), `GET .../alerts/{id}` (`getAnomalyAlert`), `POST .../alerts/{id}/actions` (`createAlertAction`); `GET/PUT /api/v1/config/anomaly-thresholds` (`getAnomalyThresholds`, `updateAnomalyThresholds`, MOD-08) → MOD-07 → `oversight.anomaly_alert`, `oversight.alert_action`; `config.tenant_setting` → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người xử lý (AC-07) xem cảnh báo trong phạm vi, xác nhận hợp lệ (gỡ cờ chặn, minh chứng được tính điểm lại, MSG-14-03) hoặc giao kiểm tra; chỉ thanh tra (`inspector`, AC-10) kết luận có hay không vi phạm (BR-14-02). Người là đối tượng cảnh báo không thấy và không xử lý được cảnh báo đó (BR-14-03, BR-14-08). Ngưỡng phát hiện có phiên bản và lý do, để theo dõi tỷ lệ cảnh báo hợp lệ (BR-14-09).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14: "Trạng thái đối tượng" (dòng 1186: `CO_VI_PHAM` → `DONG` chỉ khi đã có yêu cầu hậu kiểm), "Quy tắc nghiệp vụ" (dòng 1202: BR-14-02, BR-14-03, BR-14-05, BR-14-08, BR-14-09), "SCR-1401. Trung tâm cảnh báo" (dòng 1226, MSG-14-02..04).
- TL-03 bảng `oversight.alert_action` (dòng 3213); bảng `config.tenant_setting`.
- `contracts/openapi/modules/oversight.yaml`: `listAnomalyAlerts`, `getAnomalyAlert`, `createAlertAction`; `contracts/openapi/modules/config.yaml`: `getAnomalyThresholds`, `updateAnomalyThresholds`.
- `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-1401; bản trích `tl02b §QT-14 BE-Q14-03, 04, 05`.

## 3. Phạm vi công việc

- `listAnomalyAlerts`: phạm vi cây tổ chức, sắp theo mức rồi thời gian, kèm thẻ phân bố 90 ngày; Rego ẩn cảnh báo mà người xem là đối tượng (BR-14-03).
- `getAnomalyAlert`: trả chuỗi bằng chứng, nguồn, hành động; lần mở đầu của người xử lý chuyển `MOI` → `DANG_XEM` luỹ đẳng, không đổi ETag của lần đọc khác (theo hợp đồng).
- `createAlertAction` (`Idempotency-Key`): ghi chú 20–2.000 ký tự và ghi sổ cái (BR-14-05); `XAC_NHAN_HOP_LE` từ `DANG_XEM` → `HOP_LE` → `DONG`, tắt cờ chặn, phát EVT-21 (MSG-14-03); `GIAO_KIEM_TRA` → `DA_GIAO_KIEM_TRA`, bắt buộc người được giao; `KET_LUAN` chỉ `inspector` từ `DA_GIAO_KIEM_TRA` → `CO_VI_PHAM`/`KHONG_VI_PHAM`; `CO_VI_PHAM` trên kỳ đã khoá trả MSG-14-04 và chỉ cho `DONG` khi đã có yêu cầu mở lại hoặc hậu kiểm gắn kèm; `GHI_CHU` không đổi trạng thái; người xử lý là đối tượng bị 403 (BR-14-08).
- Ngưỡng: hiện thực `getAnomalyThresholds`, `updateAnomalyThresholds` ở MOD-08 (`policy_author`, `If-Match`, bắt buộc lý do, mỗi lần lưu tạo phiên bản mới trong `config.tenant_setting`, ghi sổ cái); SVC-15 đọc ngưỡng theo phiên bản.
- Chỉ số tỷ lệ cảnh báo hợp lệ theo dấu hiệu xuất qua OpenTelemetry để theo dõi BR-14-09.

## 4. Ngoài phạm vi

- Tiếp nhận EVT-20 và cờ chặn tự động (CORE-07-10); tạo yêu cầu mở lại (CORE-07-07); job phát hiện (DV-15-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**` (chỉ phần ngưỡng phát hiện bất thường)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`, `.../config/**`
- `deploy/policies/` (chỉ quy tắc Rego cảnh báo bất thường và ca `opa test`)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `opa test` xanh; phản hồi khớp `oversight.yaml`, `config.yaml`.
- Kiểm thử: đối tượng cảnh báo không thấy cảnh báo trong danh sách; AC-07 gọi `KET_LUAN` bị 403; ghi chú 19 ký tự bị 422; hai lần GET đầu tiên chỉ tạo một lần chuyển `DANG_XEM`; lưu ngưỡng thiếu lý do bị 422 và mỗi lần lưu tăng phiên bản.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
