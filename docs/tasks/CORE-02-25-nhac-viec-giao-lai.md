# CORE-02-25 · QT-16/SCR-1601, QT-05 Nhắc việc, giao lại (`remindTask`, `reassignTask`) và xử lý người chủ trì bị điều động

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-25` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-14 (`AssignmentChecker`, `TaskTransitionService`), CORE-00-03 (`org_replica`, consumer EVT-15) |
| Truy vết | QT-16 bước 4 → SCR-1601 → API `POST /api/v1/tasks/{id}/reminders` (`remindTask`, 202), `POST /api/v1/tasks/{id}/reassign` (`reassignTask`) → MOD-02 → bảng `task.task`, `task.task_assignee`, `task.task_state_log`, `task.outbox_event` → EVT-21, EVT-04; QT-05 ngoại lệ "người chủ trì được điều động trước hạn" ← EVT-15 `org.assignment.changed.v1`; BR-16-07; MSG-16-02, MSG-16-03 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lãnh đạo và quản lý chỉ đạo ngay trên bảng điều hành: nhắc người chủ trì việc chậm và giao lại việc cho người khác, bằng API của MOD-02 với quyền của chính người bấm (BR-16-07), không phải quyền của bảng điều hành. Cùng cơ chế đổi người chủ trì, task xử lý ngoại lệ của QT-05 khi người chủ trì được điều động trước hạn: hệ thống phát hiện qua EVT-15, báo người giao việc để giao lại, và giữ kết quả đã nghiệm thu cho người cũ theo PL 6. Hai thao tác này đã có trong hợp đồng nhưng chưa có task nhận.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16: tác nhân và đầu ra (L1622–1627), bước 4 "Chỉ đạo" (L1647), BR-16-07 (L1665), hành động SCR-1601 "Nhắc", "Giao lại" (L1715–1716), MSG-16-02, MSG-16-03 (L1725–1726).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Ngoại lệ và xử lý" (L1480: người chủ trì được điều động trước hạn), BR-05-01, BR-05-02 (L1457–1471); QT-01 BR-01-09 (L553, điều động giữa kỳ dưới 2 tháng).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-04, EVT-15, EVT-21).
- `contracts/openapi/modules/tasks.yaml` `remindTask` (L696), `reassignTask` (L728).
- Thẻ `docs/tasks/CORE-02-14-giao-viec.md`, `CORE-00-03-org-replica-consumer.md`, `CORE-02-21-de-nghi-gia-han.md`.

## 3. Phạm vi công việc

- Hiện thực `remindTask` (Idempotency-Key, trả 202): kiểm OPA phạm vi `ĐvD` của người bấm (roles `leader`, `manager`), nhiệm vụ đang `DA_GIAO` hoặc `DANG_THUC_HIEN`; ghi outbox EVT-21 tới người chủ trì kèm ghi chú (≤ 500 ký tự); chống nhắc dồn: cùng người nhắc, cùng nhiệm vụ trong 24 giờ chỉ gửi một lần (suy luận, tham số hoá theo Policy Pack nếu có, ghi vào báo cáo).
- Hiện thực `reassignTask` (Idempotency-Key, If-Match): lý do bắt buộc; người chủ trì mới qua `AssignmentChecker` (BR-05-01 đúng một chủ trì, BR-05-02 khác người nghiệm thu), còn công tác và thuộc phạm vi theo `org_replica`; cập nhật `task_assignee`, ghi `task_state_log` (trạng thái giữ nguyên, ghi loại thao tác giao lại), outbox EVT-04 cho người nhận mới và EVT-21 báo người chủ trì cũ (MSG-16-03).
- Consumer EVT-15 trong MOD-02 (qua inbox, tách khỏi consumer cập nhật `org_replica`): khi phân công của người chủ trì kết thúc hoặc chuyển sang đơn vị khác có hiệu lực trước hạn nhiệm vụ chưa xong, đánh dấu nhiệm vụ "cần giao lại" và phát EVT-21 tới người giao việc kèm danh sách nhiệm vụ; điều động tương lai thì báo trước theo `valid_from`.
- Điểm lệch cần lưu ý: TL-02a L1480 ghi nhiệm vụ "được chuyển cho người khác qua đề nghị điều chỉnh (QT-06)", nhưng danh sách loại đề nghị ở QT-06 (L1701: gia hạn, điều chỉnh số lượng, sản phẩm, tạm dừng, huỷ) và hợp đồng không có loại đổi người chủ trì. Task chọn dùng `reassignTask` do người giao việc thực hiện (cùng hiệu lực, có lý do và ghi vết), ghi vào `wiki/14` để người phụ trách xác nhận.
- Giữ kết quả cho người cũ theo PL 6: tiến độ và sản phẩm đã nghiệm thu trước thời điểm giao lại vẫn gắn với người chủ trì cũ trong `task_assignee` (vai trò cũ có thời gian kết thúc); cách tách điểm chi tiết thuộc scoring (MOD-04, SVC-07), task chỉ bảo đảm dữ liệu đủ để tách.
- Cột "cần giao lại" nếu cần lưu thì thêm bằng migration bổ sung chỉ thêm (đánh dấu "bổ sung", suy luận), hoặc tính từ `org_replica` khi đọc nếu đủ nhanh; ghi lựa chọn vào báo cáo.

## 4. Ngoài phạm vi

- Bảng điều hành và chọn việc chậm (DV-15-04, DV-15-06); giao việc ban đầu (CORE-02-14); tính điểm tách theo người khi điều động (CORE-04).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/main/resources/db/migration/task/**` (chỉ thêm migration mới, nếu chọn lưu cờ)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh gồm Testcontainers PostgreSQL + Kafka: nhắc lặp cùng `Idempotency-Key` chỉ một EVT-21; người ngoài phạm vi nhắc hoặc giao lại bị 403; giao lại cho người đang là người nghiệm thu bị chặn đúng `rule`; `If-Match` lệch trả 409.
- Kiểm thử EVT-15: phát một sự kiện kết thúc phân công của người chủ trì có hai nhiệm vụ đang làm thì người giao việc nhận đúng một thông báo liệt kê hai nhiệm vụ; nhận trùng sự kiện không thông báo lại.
- Kiểm thử hợp đồng cho `remindTask`, `reassignTask`; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
