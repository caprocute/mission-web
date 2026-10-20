# CORE-07-08 · QT-13/SCR-1301 Bốn mắt: chọn hai người duyệt qua OPA, phê duyệt có step-up và ký số, EVT-22

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-07, NEN-040 (bổ sung đề xuất: DV-10-02 cho ký số phê duyệt, DV-06-04 cho mẫu luồng `mo-lai-ky-hai-nguoi`) |
| Truy vết | QT-13 bước 5–8 → SCR-1301 → API `POST /api/v1/oversight/reopen-requests/{id}/approvals` (`approveReopenRequest`), `POST .../reopen-requests/{id}/reject` (`rejectReopenRequest`) → MOD-07 → `oversight.reopen_request` (`CHO_DUYET_1` → `CHO_DUYET_2` → `DA_DUYET` / `TU_CHOI`), `oversight.reopen_approval` → EVT-22 (`oversight.period.reopened.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mỗi yêu cầu mở lại kỳ cần hai phê duyệt của hai người khác nhau, khác người tạo và chưa từng thẩm định hay quyết định hồ sơ bị mở (BR-13-02, BR-13-03), mỗi phê duyệt qua xác thực bước lên và ký số (BR-13-04). Đủ hai phê duyệt thì phát EVT-22 để MOD-08 đưa kỳ sang `MO_LAI_HAU_KIEM` chỉ trong phạm vi đã duyệt. Đơn vị không có người duyệt thứ hai thì chuyển cấp trên, không bao giờ hạ xuống một người.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13: "Mô tả các bước" (dòng 962, bước 5–8), "Trạng thái đối tượng" (dòng 984), "Quy tắc nghiệp vụ" (dòng 999: BR-13-02..04, BR-13-07), "Ngoại lệ và xử lý" (dòng 1016: chuyển cấp trên, ý kiến duyệt 10–2.000 ký tự), "SCR-1301" (dòng 1028, MSG-13-01..04).
- TL-03 bảng `oversight.reopen_approval` (dòng 3164: trigger, `SELECT … FOR UPDATE`, ít nhất một `second_approver`).
- `contracts/openapi/modules/oversight.yaml`: `approveReopenRequest` (`OVERSIGHT-403-FOUR-EYES`, `WWW-Authenticate` với `acr_values="aal2"` theo RFC 9470), `rejectReopenRequest`.
- `wiki/15-phan-quyen.md` (bốn mắt, step-up); bản trích `tl02b §QT-13 BE-Q13-02, 03`.

## 3. Phạm vi công việc

- Viết quy tắc Rego chọn người duyệt: lượt 1 vai trò `decider`, lượt 2 vai trò `second_approver`, loại trừ người tạo, người đã thẩm định hoặc quyết định bất kỳ hồ sơ trong phạm vi (đọc qua cổng `api` của MOD-04); không có ứng viên thì nâng lên cấp trên theo cây tổ chức; gọi khi tạo yêu cầu (gán hai task qua SVC-06) và gọi lại khi nhận lệnh duyệt.
- `approveReopenRequest`: thiếu `acr` AAL2 hoặc `auth_time` quá hạn thì trả 401 kèm `WWW-Authenticate` theo hợp đồng (MSG-13-02); đúng lượt, đúng người được chỉ định, nếu không trả 403 `OVERSIGHT-403-FOUR-EYES` (MSG-13-01); ý kiến 10–2.000 ký tự; tạo yêu cầu ký và chỉ ghi `reopen_approval` khi chữ ký hoàn tất (lưu `mfa_acr`, `sign_request_id`).
- Tuần tự hoá hai phê duyệt đồng thời bằng `SELECT … FOR UPDATE` trên dòng yêu cầu; chỉ đặt `DA_DUYET` khi số `DONG_Y` ≥ `approvals_required` và có ít nhất một `second_approver`.
- Đủ duyệt: ghi outbox EVT-22 kèm kỳ, phạm vi, `open_until`, mã yêu cầu trong cùng giao dịch; phát EVT-21 (MSG-13-03); đặt `executed_at` khi nhận xác nhận MOD-08 đã mở (chuyển `DA_THUC_HIEN`).
- `rejectReopenRequest`: cũng qua step-up, bắt buộc ý kiến, chuyển `TU_CHOI`, ghi sổ cái.
- Đăng ký lược đồ Avro EVT-22 nếu chưa có.

## 4. Ngoài phạm vi

- Trạng thái kỳ, bộ đệm phạm vi, 409 `period-locked`, timer khoá lại (CORE-08-06); điều phối tính lại (CORE-07-09).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-22 nếu chưa có)
- `deploy/policies/` (chỉ quy tắc Rego bốn mắt và ca `opa test`)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka + Keycloak; `opa test` xanh.
- Kiểm thử: người tạo tự duyệt bị 403; cùng người duyệt hai lượt bị 403; hai phê duyệt gửi đồng thời không tạo hai EVT-22; token thiếu AAL2 nhận 401 có `WWW-Authenticate` đúng; đơn vị một người duyệt được nâng cấp trên.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
