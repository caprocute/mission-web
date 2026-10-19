# CORE-02-13 · ADR-04 tầng 1 Máy trạng thái nhiệm vụ khai báo từ Policy Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-02, CORE-05-03, NEN-041 |
| Truy vết | QT-05, QT-06, QT-07 → SCR-0501, SCR-0503, SCR-0601 → API `POST /api/v1/tasks/{id}/transitions` → MOD-02, MOD-05 (khoá `task_lifecycle`), MOD-08 (`PeriodApi`) → bảng `task.task`, `task.task_state_log`, `task.outbox_event` → EVT-06 `task.task.statuschanged.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực máy trạng thái nhiệm vụ theo ADR-04 tầng 1: trạng thái và lệnh là kiểu đóng trong domain, còn bảng chuyển (ai được làm, điều kiện CEL, có cần lý do) đọc từ Policy Pack khoá `task_lifecycle` theo tenant và kỳ. Mọi lần chuyển ghi `task.task_state_log` và outbox EVT-06 trong cùng giao dịch, có `state_machine_version` để truy lại bảng chuyển đã dùng. Đây là lõi dùng chung của QT-05, QT-06, QT-07 và là đường ghi nóng nhất hệ thống, nên phải thuần, nhanh và kiểm thử đầy đủ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Máy trạng thái nhiệm vụ" (L599–708), "Quản lý giao dịch" (L710), "Outbox" (L754).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Trạng thái đối tượng" (L1437–1455); QT-06, QT-07 phần trạng thái nhiệm vụ.
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1 (trạng thái `task.task.status`).
- `contracts/openapi/modules/tasks.yaml`: `transitionTask` (lỗi `TASK-409-INVALID-TRANSITION`), lược đồ `TaskStatus`, `TaskTransition`.
- Thư viện `vt-cel` (NEN-041) và `PolicyQueryApi` (CORE-05-03).

## 3. Phạm vi công việc

- Viết domain thuần: `TaskStatus` (`NHAP`, `DA_GIAO`, `DANG_THUC_HIEN`, `CHO_NGHIEM_THU`, `HOAN_THANH`, `TAM_DUNG`, `HUY`; `isTerminal`), `TaskCommand` sealed (ASSIGN, ACCEPT, SUBMIT, APPROVE, REWORK, SUSPEND, RESUME, CANCEL), `Transition(from, command, to, allowedRoles, guardCel, reasonRequired)`, `TransitionTable` (khoá `from:command`, `policyVersion`), `GuardEvaluator`, `TaskStateMachine.apply` trả kết quả hoặc mã từ chối PERIOD_LOCKED, TRANSITION_NOT_ALLOWED, ROLE_NOT_ALLOWED, REASON_REQUIRED, GUARD_FAILED.
- Viết `PolicyBackedTransitionTableProvider` đọc `task_lifecycle` qua `PolicyQueryApi`, có bộ đệm theo (tenant, phiên bản gói); bảng mặc định đúng TL-02a QT-05 (ví dụ `CHO_NGHIEM_THU → HOAN_THANH` cần vai trò `manager` và CEL `deliverables.all(d, d.status == 'DA_XAC_NHAN')`); biểu thức CEL biên dịch một lần và lưu đệm.
- Viết `TaskTransitionService` ở `application`: khoá lạc quan, `PeriodApi.assertWritable`, gọi máy trạng thái, ghi `task_state_log` (`from_status`, `to_status`, `transition`, `actor_id`, `reason`, `before_after`, `state_machine_version`), outbox EVT-06 (`TrangThaiNhiemVuDoi`) và sự kiện trong tiến trình của CORE-02-12; ánh xạ mã từ chối sang RFC 9457 (409 `TASK-409-INVALID-TRANSITION`, 409 kỳ khoá, 403 vai trò, 422 thiếu lý do hoặc guard).
- Hiện thực `transitionTask` cho các giá trị `TaskTransition` của hợp đồng (`TIEP_TUC` → RESUME, `TRINH_NGHIEM_THU` → SUBMIT, `HOAN_THANH` → APPROVE); các lệnh có thao tác riêng (giao, nhận, tạm dừng, huỷ qua đề nghị) do task tương ứng gọi `TaskTransitionService`.
- Cờ `qua_han` không là trạng thái: chỉ là thuộc tính tính theo lịch ngày làm việc (CORE-02-23 đặt cờ).
- Viết schema Avro `TrangThaiNhiemVuDoi` cho `task.task.statuschanged.v1` trong `contracts/events/avro/` nếu chưa có.
- Gợi ý tách: (a) domain thuần và kiểm thử bảng chuyển; (b) provider Policy Pack, CEL, dịch vụ ứng dụng, outbox, API.

## 4. Ngoài phạm vi

- Nghiệp vụ giao việc (CORE-02-14), đề nghị tạm dừng, huỷ (CORE-02-22), nghiệm thu (CORE-03).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `contracts/events/avro/task/**` (chỉ thêm schema EVT-06 nếu chưa có)
- `data/policy-pack/**` (chỉ thêm bảng `task_lifecycle` mặc định dùng cho dev và kiểm thử, nếu CORE-05 chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh; lớp `domain` không phụ thuộc Spring (ArchUnit), độ phủ dòng `domain` ≥ 90% (suy luận cho lõi dùng chung).
- Kiểm thử bảng: mọi cặp (trạng thái, lệnh) trong bảng mặc định cho kết quả đúng; mọi cặp ngoài bảng trả TRANSITION_NOT_ALLOWED; trạng thái kết thúc không chuyển tiếp được.
- Kiểm thử tích hợp: mỗi lần chuyển có đúng một dòng `task_state_log` và một EVT-06 trong outbox cùng giao dịch; đổi `task_lifecycle` trong Policy Pack phiên bản mới không ảnh hưởng kỳ đã ghim phiên bản cũ.
- Kiểm thử vi mô: `apply` kèm một guard CEL p99 ≤ 1 ms; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
