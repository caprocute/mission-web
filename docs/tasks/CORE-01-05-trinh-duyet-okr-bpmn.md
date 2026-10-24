# CORE-01-05 · QT-02/SCR-0203 Trình duyệt OKR qua BPMN `duyet-okr`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L |
| Phụ thuộc | CORE-01-03, DV-06-03; cần CORE-01-04 (kiểm lại toàn bộ quy tắc khi trình) (bổ sung) |
| Truy vết | QT-02 → SCR-0202, SCR-0203 → API `POST /api/v1/okr/objectives/{id}/submit`, `GET /okr/reviews` → MOD-01, SVC-06 → bảng `okr.objective`, `okr.outbox_event`, `flowable.wf_binding`, `flowable.wf_task_projection` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Nối luồng trình OKR của MOD-01 với tiến trình BPMN `duyet-okr` trên workflow-service: trình thì kiểm lại toàn bộ quy tắc, chuyển `NHAP` → `CHO_DUYET`, tạo tiến trình với người duyệt và người duyệt thay lấy từ `flowable.wf_binding`, trả về người duyệt và hạn để web hiện MSG-02-08. Hàng chờ duyệt của người duyệt đọc từ `wf_task_projection`. Đây là luồng duyệt BPMN đầu tiên của lõi, làm mẫu cho kế hoạch (CORE-02-08) và đề nghị (CORE-02-22).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 bước trình (L790), "Trạng thái đối tượng" (L798–813), "Ngoại lệ và xử lý" (người duyệt vắng, L838), SCR-0202 (L930), SCR-0203 (L941–977).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục về ba tầng điều phối (tầng 1 phát sự kiện, tầng 2 khởi tạo tiến trình với business key, L1887–1889), `wf_binding` (L2023), quy tắc quản trị mẫu (L2153), "Quản lý giao dịch" (L710).
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md` mục "Cấu hình luồng ở cấp tenant (SCR-1504)".
- `contracts/openapi/modules/okr.yaml`: `submitOkrObjective`, `listOkrReviews`; `contracts/openapi/modules/platform.yaml` nhóm `/workflow/*`.

## 3. Phạm vi công việc

- Hiện thực `submitOkrObjective` (Idempotency-Key, If-Match): gọi `OkrRuleChecker`, còn lỗi chặn thì trả 422 kèm danh sách kết quả; hợp lệ thì chuyển `CHO_DUYET`, ghi outbox yêu cầu khởi tạo tiến trình `duyet-okr` với business key `OKR_OBJECTIVE:<id>` trong cùng giao dịch, không gọi mạng trong giao dịch.
- Dùng đúng cơ chế khởi tạo tiến trình mà DV-06-02, DV-06-03 công bố (TL-01: tầng 1 phát sự kiện, workflow-service tạo tiến trình). Điểm lệch cần lưu ý: baseline chỉ liệt kê EVT-03, EVT-11, EVT-13, EVT-25 là sự kiện khởi tạo tiến trình, không có mã EVT cho "mục tiêu đã trình"; task không tự đặt mã EVT mới, dùng cơ chế DV-06 chốt (lệnh nội bộ qua outbox tới workflow-service) và ghi vào `wiki/14`.
- Lưu `wf_process_instance_id` khi workflow-service báo đã tạo tiến trình; phản hồi trình trả người duyệt dự kiến và hạn (đọc từ `wf_task_projection` hoặc từ kết quả DMN chọn cấp duyệt), khớp lược đồ phản hồi của `submitOkrObjective`.
- Hiện thực `listOkrReviews`: hàng chờ của người duyệt trong kỳ, kể cả việc duyệt thay theo uỷ quyền (`onBehalfOf`), mỗi bộ kèm kết quả kiểm tra tính lại khi mở (không lưu).
- Hiện thực external worker trong mission-core nhận lệnh áp kết quả từ tiến trình `duyet-okr` (idempotent theo khoá job), gọi use case duyệt hoặc trả lại của CORE-01-06; không đặt dữ liệu nghiệp vụ trong biến tiến trình (≤ 4 KB, chỉ id, tenant, khối).
- Gợi ý tách nếu quá lớn: (a) trình và outbox khởi tạo tiến trình; (b) worker áp kết quả và hàng chờ `listOkrReviews`.

## 4. Ngoài phạm vi

- Mẫu BPMN `duyet-okr`, DMN chọn cấp duyệt, timer nhắc và người duyệt thay trong engine (DV-06-03).
- Logic duyệt, trả lại, rút lại, huỷ, duyệt hàng loạt (CORE-01-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (ghi cơ chế khởi tạo tiến trình đã chọn)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, workflow-service giả lập hoặc Flowable thật của DV-06): trình hợp lệ tạo đúng một bản ghi outbox và mục tiêu `CHO_DUYET`; trình lặp cùng `Idempotency-Key` không tạo tiến trình thứ hai; trình còn lỗi chặn trả 422 và trạng thái giữ `NHAP`.
- Worker áp kết quả chạy lại hai lần cùng job chỉ đổi trạng thái một lần.
- Báo cáo cuối task theo AGENTS.md mục 6; `wiki/14` ghi điểm lệch về sự kiện khởi tạo tiến trình.

## 7. Báo cáo

Theo AGENTS.md mục 6.
