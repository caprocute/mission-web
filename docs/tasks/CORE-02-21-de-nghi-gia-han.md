# CORE-02-21 · QT-06/SCR-0602 Đề nghị gia hạn, điều chỉnh: gửi, rút, xem trước hệ số C

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-21` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-19, CORE-08-04; cần CORE-05-03 (tham số hệ số C, ngưỡng gia hạn) (bổ sung) |
| Truy vết | QT-06 → SCR-0602 → API `POST /api/v1/tasks/{id}/change-requests`, `POST /tasks/{id}/change-requests/preview`, `POST /tasks/{id}/change-requests/{requestId}/withdraw` → MOD-02, MOD-05, SVC-08 (tệp đính kèm) → bảng `task.task_change_request`, `task.task_blocker`, `task.outbox_event` → EVT-21; BR-06-01, BR-06-06, BR-06-07, BR-06-09; MSG-06-06, MSG-06-08, MSG-06-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người thực hiện gửi đề nghị gia hạn, đổi số lượng, đổi sản phẩm, tạm dừng hoặc huỷ nhiệm vụ, kèm lý do, nguyên nhân tự nhận (khách quan hoặc chủ quan) và tệp đính kèm; trước khi gửi xem được hệ số tiến độ C dự kiến cho hai giả định khách quan và chủ quan. Đề nghị đi theo máy trạng thái năm trạng thái; người gửi rút được khi chưa xử lý.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Mô tả các bước" (L1693–1707), "Trạng thái đối tượng" (L1708–1722), BR-06-01, BR-06-06, BR-06-07, BR-06-09 (L1724–1739), "Ngoại lệ và xử lý" (L1741–1751), SCR-0602 (L1799–1846, ràng buộc trường L1818–1826, API L1833–1836).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.task_change_request".
- `contracts/openapi/modules/tasks.yaml`: `submitTaskChangeRequest`, `previewTaskChangeRequest`, `withdrawTaskChangeRequest`, lược đồ `ChangeRequest`, `ChangeRequestType`, `ChangeRequestStatus`, `CauseKind`.

## 3. Phạm vi công việc

- Viết domain `ChangeRequest` với máy trạng thái: (chưa có) → `NHAP` → `CHO_DUYET` → `DA_DUYET` | `TU_CHOI` | `DA_RUT`; đề nghị đã kết thúc bất biến; mỗi nhiệm vụ chỉ một đề nghị cùng loại đang chờ.
- Hiện thực `submitTaskChangeRequest` (Idempotency-Key): loại theo hợp đồng (`GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY`); lý do 20–2.000 ký tự; hạn mới sau hạn hiện tại và trong kỳ hoặc tới hạn nghiệm thu tồn đọng; tính số ngày làm việc tăng thêm theo lịch nghỉ; ghi số lần gia hạn đã dùng trong quý; liên kết `blocker_id` nếu có; tệp đính kèm là mã đối tượng đã tải lên SVC-08 (≤ 20 MB, đã quét).
- Kiểm BR-06-09: đề nghị `HUY` cho nhiệm vụ đã có minh chứng `DA_XAC_NHAN` bị chặn (MSG-06-09; dữ liệu từ `TaskApi` CORE-02-12).
- Kiểm BR-06-07: gửi sau hạn thực hiện vẫn nhận nhưng cảnh báo và ghi vết rằng ngày chậm trước khi gửi tính chủ quan (MSG-06-08).
- Xác định người duyệt: người giao việc mặc định; đánh dấu "vượt ngưỡng" (lần gia hạn thứ ba trong quý hoặc > 10 ngày làm việc, BR-06-06) để CORE-02-22 chuyển BPMN; outbox EVT-21 tới người duyệt.
- Hiện thực `previewTaskChangeRequest`: tính C = 1 − 0,05 × số ngày làm việc chậm do chủ quan, không âm (BR-06-01, hệ số lấy từ Policy Pack) cho hai giả định khách quan và chủ quan, kèm cờ vượt ngưỡng (MSG-06-06). Điểm cuối cùng vẫn do scoring-service tính; công thức xem trước dùng cùng biểu thức CEL của Policy Pack để không lệch (suy luận).
- Hiện thực `withdrawTaskChangeRequest`: chỉ người gửi, chỉ khi `CHO_DUYET` và chưa có quyết định → `DA_RUT`.

## 4. Ngoài phạm vi

- Duyệt, từ chối, áp dụng, BPMN vượt ngưỡng (CORE-02-22); tính điểm chính thức (SVC-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử: gửi hai đề nghị `GIA_HAN` cùng lúc cho một nhiệm vụ thì cái sau bị 409; lý do 19 ký tự bị 422; huỷ nhiệm vụ đã có minh chứng xác nhận bị chặn với MSG-06-09; rút đề nghị đã quyết định trả 409.
- Xem trước với 3 ngày chậm chủ quan trả C = 0,85 (hệ số 0,05) và C = 1 nếu khách quan; số ngày làm việc tăng thêm đúng qua kỳ nghỉ lễ.
- Kiểm thử hợp đồng cho ba operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
