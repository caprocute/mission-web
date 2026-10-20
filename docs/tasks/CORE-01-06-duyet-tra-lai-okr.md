# CORE-01-06 · QT-02/SCR-0203 Duyệt, trả lại, rút, huỷ và duyệt hàng loạt OKR

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-01-05 |
| Truy vết | QT-02 → SCR-0203 → API `POST /api/v1/okr/objectives/{id}/approve`, `/return`, `/withdraw`, `POST /okr/objectives/approve-batch` (202), `GET /okr/objectives/{id}/history` → MOD-01, SVC-06 → bảng `okr.objective`, `okr.objective_review`, `okr.outbox_event` → EVT-01 `okr.objective.approved.v1`, EVT-21 `notify.message.requested.v1`; BR-02-08, BR-02-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn tất vòng đời duyệt mục tiêu: người duyệt (AC-04) duyệt hoặc trả lại có ý kiến, duyệt hàng loạt các bộ "Đạt" bằng tác vụ 202; người soạn rút lại khi chưa xử lý; người duyệt huỷ có lý do. Mỗi quyết định ghi `okr.objective_review` chỉ ghi thêm, đổi trạng thái và ghi outbox trong cùng giao dịch, phát EVT-01 để SVC-15, SVC-09 và MOD-02 dùng mục tiêu đã duyệt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 "Trạng thái đối tượng" (L798–813), "Quy tắc nghiệp vụ" BR-02-08, BR-02-09 (L815–829), SCR-0203 (L941–977, API L966–968), "Liên kết thiết kế" (L978–980).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` bảng sự kiện (EVT-01, EVT-21).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Outbox" (L754).
- `contracts/openapi/modules/okr.yaml`: `approveOkrObjective`, `returnOkrObjective`, `withdrawOkrObjective`, `approveOkrObjectivesBatch`, `listOkrObjectiveHistory`; `contracts/openapi/modules/jobs.yaml`.
- `wiki/15a-ma-tran-phan-quyen.md` mục "Ràng buộc tách vai trò, bốn mắt và xác thực bước lên".

## 3. Phạm vi công việc

- Hiện thực `approveOkrObjective` (Idempotency-Key, If-Match): người duyệt khác người soạn (BR-02-08, 403 kèm MSG-02-11), mục tiêu ở `CHO_DUYET`; chuyển `DA_DUYET`, ghi `approved_by/at`, `objective_review` (`DUYET`), outbox EVT-01 với payload Avro `MucTieuDaDuyet`; ghi "duyệt thay" khi người duyệt là người được uỷ quyền (`onBehalfOf`).
- Hiện thực `returnOkrObjective`: ý kiến bắt buộc (BR-02-09, MSG-02-10), chuyển về `NHAP`, ghi `objective_review` (`TRA_LAI`), outbox EVT-21 tới người soạn.
- Hiện thực `withdrawOkrObjective`: chỉ người soạn, chỉ khi `CHO_DUYET` và chưa có quyết định; yêu cầu workflow-service huỷ tiến trình qua outbox, mục tiêu về `NHAP`; sai trạng thái trả 409 `OKR-409-INVALID-STATE`.
- Hiện thực chuyển `DA_DUYET`/`CHO_DUYET` → `DONG` khi người duyệt huỷ có lý do ở lớp domain. Điểm lệch cần lưu ý: TL-02a L813 có chuyển "người duyệt huỷ" nhưng `okr.yaml` không có thao tác huỷ; theo API-first, task chỉ hiện thực chuyển trạng thái trong domain và dịch vụ ứng dụng, không mở endpoint, và ghi đề nghị bổ sung hợp đồng vào báo cáo.
- Hiện thực `approveOkrObjectivesBatch` trả 202 + `Location`: chỉ nhận bộ có kết quả kiểm tra "Đạt", mỗi bộ một giao dịch riêng, mỗi mục mang `rowVersion` thay `If-Match`; lệch phiên bản thì mục đó lỗi 409, mục khác vẫn chạy; EVT-01 cho từng bộ.
- Hiện thực `listOkrObjectiveHistory` từ `objective_review` và lịch sử tiến trình (`/workflow/instances/{businessKey}/history`) ghép theo thời gian.
- Viết schema Avro `MucTieuDaDuyet` cho topic `okr.objective.approved.v1` trong `contracts/events/avro/` nếu chưa có, đăng ký chế độ BACKWARD.
- Khi duyệt xong, báo workflow-service hoàn thành user task qua outbox (không gọi mạng trong giao dịch), đúng cơ chế đã chọn ở CORE-01-05.

## 4. Ngoài phạm vi

- Điều chỉnh giữa kỳ bằng phiên bản mới (CORE-01-07); đóng mục tiêu do saga khoá kỳ (CORE-08, DV-06-05).
- Gửi thông báo thật (DV-12-01 tiêu thụ EVT-21); ghi sổ cái (SVC-09 tiêu thụ EVT-01).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `contracts/events/avro/okr/**` (chỉ thêm schema EVT-01 nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm kiểm thử Testcontainers PostgreSQL + Kafka (Debezium hoặc bộ đọc outbox giả lập) xác nhận EVT-01 nằm trong outbox cùng giao dịch với trạng thái `DA_DUYET`.
- Người soạn tự duyệt nhận 403 với MSG-02-11; trả lại không ý kiến nhận 422 với MSG-02-10.
- Duyệt hàng loạt 200 bộ trong đó 3 bộ lệch phiên bản: 197 bộ `DA_DUYET`, 3 bộ báo lỗi 409 trong kết quả job.
- Kiểm thử hợp đồng với `okr.yaml` cho năm operationId xanh; schema Avro qua kiểm tra tương thích BACKWARD.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu đề nghị bổ sung thao tác huỷ mục tiêu vào hợp đồng.

## 7. Báo cáo

Theo AGENTS.md mục 6.
