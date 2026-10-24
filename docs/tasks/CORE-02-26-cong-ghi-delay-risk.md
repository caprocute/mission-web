# CORE-02-26 · QT-06/SCR-0601 Cột `delay_risk` và cổng ghi cho dự báo nguy cơ trễ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-26` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-02 (cột `task.task.delay_risk`); NEN-013, NEN-014 (If-Match, OPA cho `service_client`) (bổ sung) |
| Truy vết | QT-06 bước 2 (MSG-06-02) → SCR-0601 trường "Dự báo trễ hạn" → `getTask` trả `delayRisk` (lược đồ `DelayRisk`) → cổng dịch vụ đề xuất `PUT /api/v1/tasks/{id}/delay-risk` (`setTaskDelayRisk`, `roles: [service_client]`, chưa có trong hợp đồng) do DV-15-10 gọi → MOD-02 → bảng `task.task` (`delay_risk` CHECK IN (`THAP`, `VUA`, `CAO`)) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-02a ghi `task.task.delay_risk` "ghi bởi SVC-15", nhưng TL-03 không cho SVC-15 ghi schema `task`, và hợp đồng chưa có cách nào để analytics đẩy kết quả dự báo về. Task này làm phía MOD-02: một cổng ghi có kiểm soát cho tài khoản dịch vụ của bộ dự báo (DV-15-10), lưu mức nguy cơ cùng phần giải thích mà lược đồ `DelayRisk` của hợp đồng đã yêu cầu, và trả về trong chi tiết nhiệm vụ. Khi xong, SCR-0601 hiện "Dự báo trễ hạn" với mức Thấp, Trung bình, Cao kèm giải thích, cập nhật sau mỗi lần cập nhật tiến độ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 hình và bước 2 (L1691: dự báo nguy cơ trễ dựa trên nhịp thực hiện), bảng trường SCR-0601 dòng 6 "Dự báo trễ hạn" (L1775: `task.task.delay_risk`, đề xuất, ghi bởi SVC-15), "Liên kết thiết kế" (L1849).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `task.task` (L2270: `delay_risk varchar(8)`), chương 2 sở hữu dữ liệu (L143–151).
- `contracts/openapi/modules/tasks.yaml` lược đồ `DelayRisk` (L2420: `level`, `neededWorkingDays`, `explanation`) và `TaskDetail.delayRisk`; tiền lệ thao tác dịch vụ `listAppraisalScoringInputs` (`appraisals.yaml`, `roles: [service_client]`).
- Thẻ `docs/tasks/DV-15-10-du-bao-tre-han.md` (bên gọi, điểm lệch), `CORE-02-02-migration-nhiem-vu.md`.

## 3. Phạm vi công việc

- Bước đầu, trước khi viết mã: thêm vào `tasks.yaml` thao tác `PUT /tasks/{id}/delay-risk` (`setTaskDelayRisk`, `owner: MOD-02`, `roles: [service_client]`, thân gồm `level`, `neededWorkingDays`, `explanation`, `computedAt`, `basedOnProgressId`), chỉ thêm, chạy `npm run lint` và `openapi-diff`, chờ người phụ trách hợp đồng duyệt; sinh lại kiểu frontend.
- Điểm lệch cần lưu ý: lược đồ `DelayRisk` có `neededWorkingDays` và `explanation` nhưng TL-03 chỉ có cột `delay_risk`. Thêm migration bổ sung (chỉ thêm, đánh dấu "bổ sung") các cột `delay_risk_detail jsonb` và `delay_risk_at timestamptz` trên `task.task`, phân loại C1, ghi vào `wiki/14`; nếu người phụ trách CSDL không duyệt thì chỉ lưu `level` và trả hai trường kia rỗng (cần sửa hợp đồng thành tuỳ chọn).
- Hiện thực `setTaskDelayRisk`: chỉ chấp nhận token client credentials của bộ dự báo (OPA kiểm `service_client` và tenant khớp nhiệm vụ); bỏ qua kết quả cũ hơn giá trị đang lưu theo `computedAt` (trả 409 có mã lỗi rõ thay vì ghi đè); không đổi `row_version` nghiệp vụ của nhiệm vụ để không làm hỏng `If-Match` của người dùng đang sửa (cập nhật cột riêng, suy luận, ghi vào báo cáo); không phát EVT nghiệp vụ.
- Nhiệm vụ đã `HOAN_THANH`, `HUY` hoặc `TAM_DUNG` thì xoá dự báo (đặt NULL) và từ chối ghi mới.
- `getTask` trả `delayRisk` từ cột đã lưu; nhiệm vụ chưa có dự báo thì không có trường.
- Tuỳ chọn khi người phụ trách yêu cầu: phát EVT-21 tới người giao việc khi mức chuyển lên `CAO` (một lần cho mỗi lần chuyển mức), dùng mẫu thông báo có sẵn của DV-12-01.

## 4. Ngoài phạm vi

- Thuật toán dự báo, tiêu thụ EVT-05, ClickHouse (DV-15-10); hiển thị trên SCR-0601 (WEB).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/tasks.yaml` (chỉ thêm `setTaskDelayRisk` sau khi được duyệt)
- `frontend/projects/api-client/src/lib/generated/**` (chỉ sinh lại bằng `npm run api:gen`)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ thêm migration mới)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm run lint` xanh, `openapi-diff` chỉ báo thêm thao tác; `cd frontend && npm run api:check` xanh.
- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh: token người dùng gọi `setTaskDelayRisk` bị 403; kết quả cũ hơn bị từ chối; ghi dự báo không làm `If-Match` của người dùng lệch; `getTask` trả đúng lược đồ `DelayRisk`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
