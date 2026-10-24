# CORE-02-22 · QT-06/BR-06-04..11 Duyệt đề nghị, tạm dừng, tiếp tục, huỷ; vượt ngưỡng qua BPMN `duyet-de-nghi`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-22` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-21, DV-06-03 |
| Truy vết | QT-06 → SCR-0602, SCR-0601 → API `POST /api/v1/tasks/{id}/change-requests/{requestId}/decision`, `POST /tasks/{id}/transitions` (`TIEP_TUC`) → MOD-02, SVC-06 → bảng `task.task_change_request`, `task.task` (`due_date`, `score_due_date`, `status`), `task.task_state_log`, `flowable.wf_binding` → EVT-06, EVT-21; BR-06-04, BR-06-05, BR-06-06, BR-06-08, BR-06-09, BR-06-10, BR-06-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bước quyết định đề nghị và áp dụng hệ quả lên nhiệm vụ: người duyệt chọn nguyên nhân khách quan hoặc chủ quan, chấp nhận hoặc từ chối có lý do; gia hạn khách quan dời cả hạn thực hiện và hạn tính điểm, chủ quan chỉ dời hạn thực hiện; tạm dừng, tiếp tục, huỷ đi qua máy trạng thái. Đề nghị vượt ngưỡng chuyển sang cấp trên (AC-04) qua BPMN `duyet-de-nghi`, người duyệt vắng quá 2 ngày làm việc được chuyển người duyệt thay. Đây là nơi quyết định trực tiếp tới hệ số C của cán bộ, nên mọi quyết định phải có lý do và để lại dấu vết.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-06 "Mô tả các bước" bước 7 (áp dụng, L1703), "Trạng thái đối tượng" (L1708–1722), BR-06-04..BR-06-11 (L1724–1739), "Ngoại lệ và xử lý" (người duyệt vắng L1745, đề nghị chưa duyệt khi đã qua hạn cũ L1746, đổi người chủ trì L1748), SCR-0602 (quyết định, L1788, L1835).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` đoạn ba tầng điều phối (L1887–1889).
- `contracts/openapi/modules/tasks.yaml`: `decideTaskChangeRequest`, `transitionTask`, lược đồ `CauseKind`.
- Thẻ CORE-01-05 (cơ chế khởi tạo tiến trình) và CORE-02-13 (máy trạng thái nhiệm vụ).

## 3. Phạm vi công việc

- Hiện thực `decideTaskChangeRequest` (Idempotency-Key, If-Match): người duyệt khác người gửi (BR-06-08, cũng có CHECK ở CSDL); bắt buộc `cause_decided`; chấp nhận hoặc từ chối; ghi `decided_by/at`, `decision_note`.
- Điểm lệch cần lưu ý: BR-06-11 bắt buộc lý do khi "từ chối hoặc xác định chủ quan", còn đoạn giải thích L1706 nói "ghi lý do khi xác định khách quan". Task bắt buộc lý do cho mọi quyết định (thoả cả hai cách đọc, suy luận), ghi `wiki/14` để chủ trì nghiệp vụ xác nhận.
- Áp dụng khi chấp nhận, trong cùng giao dịch: `GIA_HAN` khách quan dời `due_date` và `score_due_date`, chủ quan chỉ dời `due_date` (BR-06-04, BR-06-05); `DOI_SO_LUONG`, `DOI_SAN_PHAM` cập nhật nhiệm vụ; `TAM_DUNG` → lệnh SUSPEND; `HUY` → lệnh CANCEL (BR-06-09 kiểm lại); ghi `task_state_log`, EVT-06, EVT-21 tới người gửi.
- Tiếp tục từ `TAM_DUNG` (lệnh RESUME qua `transitionTask` `TIEP_TUC`): dời hạn theo số ngày làm việc đã tạm dừng (BR-06-10).
- Vượt ngưỡng (BR-06-06: lần gia hạn thứ ba trong quý hoặc > 10 ngày làm việc): người giao không được quyết định; tạo tiến trình `duyet-de-nghi` theo cơ chế của CORE-01-05; worker áp kết quả gọi cùng use case quyết định; người duyệt vắng quá 2 ngày làm việc được chuyển người duyệt thay theo `wf_binding` và báo người gửi (timer trong mẫu BPMN của DV-06-03).
- Đề nghị chưa duyệt khi đã qua hạn cũ: tạm tính chủ quan; khi duyệt khách quan thì phát EVT-06 kèm hạn mới để scoring-service tính lại (L1746).
- Gợi ý tách: (a) quyết định và áp dụng bốn loại, tạm dừng, tiếp tục; (b) vượt ngưỡng, BPMN, worker, người duyệt thay.

## 4. Ngoài phạm vi

- Mẫu BPMN `duyet-de-nghi` (DV-06-03); phát hiện tỷ lệ chấp nhận khách quan bất thường BR-06-12, EVT-20 (SVC-15); đổi người chủ trì do điều động (cần thao tác `reassignTask`, QT-16).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, kiểm thử tích hợp Testcontainers PostgreSQL + Kafka.
- Kiểm thử: gia hạn khách quan 5 ngày dời cả hai hạn; chủ quan chỉ dời hạn thực hiện; người gửi tự duyệt trả 403; quyết định thiếu lý do trả 422; tạm dừng 4 ngày làm việc rồi tiếp tục dời hạn đúng 4 ngày làm việc.
- Đề nghị gia hạn lần thứ ba trong quý không quyết định được ở cấp người giao (403 kèm hướng dẫn) và tạo đúng một tiến trình `duyet-de-nghi`; worker chạy lại cùng job chỉ áp dụng một lần.
- Kiểm thử hợp đồng cho `decideTaskChangeRequest` xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
