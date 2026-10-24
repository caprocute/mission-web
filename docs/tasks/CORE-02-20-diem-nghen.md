# CORE-02-20 · QT-06/SCR-0601 Điểm nghẽn: báo, giao xử lý, chuyển cấp trên, đóng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-20` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-19 |
| Truy vết | QT-06 → SCR-0601 → API `POST /api/v1/tasks/{id}/blockers`, `PATCH /tasks/{id}/blockers/{blockerId}` → MOD-02 → bảng `task.task_blocker`, `task.outbox_event` → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người thực hiện báo điểm nghẽn của nhiệm vụ (nội dung, nguyên nhân theo người báo, mức ảnh hưởng, người cần hỗ trợ, hạn xử lý) và cho người giao việc xử lý: giao người xử lý, đặt hạn, chuyển cấp trên, đóng. Mỗi bước thông báo người liên quan qua EVT-21. Điểm nghẽn là căn cứ khi xét đề nghị gia hạn khách quan ở CORE-02-22.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Mô tả các bước" (L1699–1700), "Trạng thái đối tượng" (điểm nghẽn mở → đóng, L1708–1722), SCR-0601 (L1785–1786).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.task_blocker".
- `contracts/openapi/modules/tasks.yaml`: `reportTaskBlocker`, `handleTaskBlocker`, lược đồ `BlockerStatus`, `BlockerCauseCategory`, `ImpactLevel`.

## 3. Phạm vi công việc

- Hiện thực `reportTaskBlocker` (Idempotency-Key): người thực hiện (chủ trì hoặc phối hợp) của nhiệm vụ đang mở; tạo `task_blocker` `MO` với `cause_category`, `cause_detail`, `impact_level`, `handler_person_id`, `resolve_by`; outbox EVT-21 tới người giao và người cần hỗ trợ.
- Hiện thực `handleTaskBlocker` (PATCH dạng JSON Merge Patch, If-Match) với lược đồ `BlockerPatch` (`handlerPersonId`, `resolveBy`, `status`, `resolutionNote`): giao người xử lý (`DANG_XU_LY`), đặt hoặc đổi hạn xử lý, ghi kết quả (`DA_GIAI_QUYET`, `resolution`, `resolved_at`), đóng (`DONG`). Điểm lệch cần lưu ý: `BlockerPatch` không có trường "chuyển cấp trên"; task coi việc đổi `handlerPersonId` sang người thuộc cấp trên của người giao là chuyển cấp trên, ghi `escalated_to_person_id` và EVT-21 tới cấp trên (suy luận, ghi `wiki/14`).
- Viết máy trạng thái nhỏ cho điểm nghẽn trong domain: `MO` → `DANG_XU_LY` → `DA_GIAI_QUYET` → `DONG`, cho phép `MO`/`DANG_XU_LY` → `DONG` khi người giao đóng có lý do (TL-02a chưa có máy trạng thái chính thức, bảng chuyển là suy luận và ghi rõ trong chú thích).
- Chỉ người giao việc hoặc người được giao xử lý được thao tác xử lý; người báo được sửa nội dung khi còn `MO` (suy luận).
- Đưa danh sách điểm nghẽn vào phản hồi chi tiết nhiệm vụ `getTask` theo lược đồ hợp đồng.

### Bổ sung theo BS-03 (ADR-33, 22/10/2026)

Nguồn: `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md`. Phần dưới mở rộng phạm vi của thẻ; mọi giá trị riêng của khách hàng đi qua cấu hình, không viết cứng trong mã (YC-TK-11). Nếu thẻ phụ thuộc chưa gộp, làm phần gốc của thẻ trước và để phần bổ sung sau cờ hoặc ghi rõ trong báo cáo.

Phụ thuộc thêm NEN-044, CORE-05-12.

- Điểm nghẽn tự sinh: thêm cột `origin` (`NGUOI_DUNG` mặc định, `TU_DONG`) và `rule_code` cho `task.task_blocker`; job đánh giá bộ quy tắc CEL do hồ sơ trỏ tới (ví dụ chín quy tắc của hồ sơ TWĐ), ghi điểm nghẽn idempotent theo `(task_id, rule_code, ngày chạy)`, bỏ qua khi hồ sơ không có bộ quy tắc.

## 4. Ngoài phạm vi

- Đề nghị gia hạn liên kết điểm nghẽn (`blocker_id`, CORE-02-21); thống kê điểm nghẽn trên bảng điều hành (SVC-15).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`
- `backend/mission-core/src/main/resources/db/migration/task/**` (BS-03)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử: chuyển trạng thái không hợp lệ (ví dụ `DONG` → `MO`) trả 409; người ngoài nhiệm vụ báo điểm nghẽn trả 403; chuyển cấp trên sinh đúng một EVT-21 tới cấp trên; PATCH lệch `If-Match` trả 409.
- Kiểm thử hợp đồng cho hai operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
