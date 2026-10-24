# DL-04 · ADR-04/QT-05 Bảng chuyển trạng thái `task_lifecycle` và tham số luồng duyệt mặc định

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | S (≤ nửa ngày agent) |
| Phụ thuộc | CORE-05-03 (bổ sung: nên gộp trước hoặc cùng CORE-02-13 vì máy trạng thái nhiệm vụ đọc bảng này) |
| Truy vết | ADR-04; QT-05, QT-06, QT-07 (BR-05-01..BR-05-05, PL 4 III.5) → API `POST /api/v1/tasks/{id}/transitions` (`transitionTask`), `GET /api/v1/policies/resolve` (`resolvePolicyPack`) → MOD-02, MOD-05, SVC-06 → bảng `task.task`, `task.task_state_log`, `policy.policy_rule` (khoá `task_lifecycle`), `flowable.wf_binding` → EVT-06 |
| Trạng thái | Xong phần kiểm thử nạp 21/10/2026: `TaskLifecycleLoadTest` 10/10 trên vt-cel (bộ trạng thái, lệnh, vai trò, guard CEL, miền tham số, tham số luồng duyệt); kiểm thử chuyển trạng thái trên Testcontainers chờ CORE-02-13 |

## 1. Mục tiêu

Policy Pack có khoá `task_lifecycle` chứa bảng chuyển trạng thái mặc định của nhiệm vụ đúng TL-02a, và bộ tham số mặc định cho các luồng duyệt (thời hạn, số lần, ngưỡng) mà TL-02a, TL-02b đã dùng. Nhờ đó `PolicyBackedTransitionTableProvider` của MOD-02 và các mẫu luồng của SVC-06 có dữ liệu thật để chạy, thay vì hằng số trong mã (ADR-04: vòng đời nhiệm vụ là máy trạng thái khai báo, không chạy BPMN).

## 2. Đầu vào bắt buộc đọc

- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "Máy trạng thái nhiệm vụ" (dòng 599–708: `TaskStatus`, `TaskCommand`, `Transition(from, command, to, allowedRoles, guardCel, reasonRequired)`, dòng mặc định `CHO_NGHIEM_THU → HOAN_THANH`).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-05 mục "Trạng thái đối tượng" (bảng chuyển trạng thái `task.task`), QT-06 mục "Trạng thái đối tượng" (`task.task_change_request`), QT-07 "Trạng thái đối tượng".
- Tham số mặc định: `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-02a.md` mục 5, `_ghi-chu-TL-02b.md` mục "Tham số Policy Pack mới"; TL-02b QT-15 "Quy tắc nghiệp vụ" (ràng buộc tham số luồng: số cấp 2–4, thời hạn 1–15 ngày làm việc, không bỏ bước ký bắt buộc).
- Hợp đồng `contracts/openapi/modules/tasks.yaml` (`transitionTask`, lược đồ tên chuyển trạng thái), `contracts/openapi/modules/policies.yaml` (`resolvePolicyPack`, `saveWorkflowBinding`).
- `wiki/15a-ma-tran-phan-quyen.md` (tên vai trò dùng trong `allowedRoles`).

## 3. Phạm vi công việc

- Viết khoá `task_lifecycle` trong gói `khoi-dang/quy-dinh-moi` (và tham chiếu dùng chung cho gói luồng Nhà nước) gồm đủ chín dòng của bảng QT-05: `NHAP → DA_GIAO`, `DA_GIAO → DANG_THUC_HIEN`, `DANG_THUC_HIEN → CHO_NGHIEM_THU`, `CHO_NGHIEM_THU → DANG_THUC_HIEN`, `CHO_NGHIEM_THU → HOAN_THANH`, `DANG_THUC_HIEN → TAM_DUNG`, `TAM_DUNG → DANG_THUC_HIEN` và ba dòng sang `HUY`.
- Gắn mỗi dòng với lệnh của TL-01 (`ASSIGN`, `ACCEPT`, `SUBMIT`, `APPROVE`, `REWORK`, `SUSPEND`, `RESUME`, `CANCEL`), `allowedRoles` lấy đúng tên vai trò trong ma trận phân quyền, `guardCel` và `reasonRequired`; dòng `CHO_NGHIEM_THU → HOAN_THANH` dùng guard `deliverables.all(d, d.status == 'DA_XAC_NHAN')`.
- Không đưa `qua_han` thành trạng thái; ghi chú rằng đó là thuộc tính tính theo lịch ngày làm việc.
- Viết bộ tham số mặc định có miền giá trị: `plan_approval_deadline_rule`, `plan_max_adjustments_per_quarter` = 2, `extension_max_per_task_quarter` = 2, `extension_escalation_days` = 10, `acceptance_sla_days` = 5, `kr_risk_low_streak` = 2, `similarity_threshold` = 0,7, `mfa_stepup_window_minutes` = 15, `khieu_nai.thoi_han` = 10, `kiem_diem.cach_dem_ngay`; đánh dấu `difficulty_coefficients` là giả định chờ cơ quan tổ chức xác nhận.
- Viết tham số mặc định cho `wf_binding` của các mẫu luồng đã nêu tên (`duyet-okr` v2, `duyet-ke-hoach-quy` v2, `duyet-ho-so-danh-gia` v3 với thời hạn 3/3/2/3 ngày làm việc, `xu-ly-khieu-nai` v2, `mo-lai-ky-hai-nguoi` v1) trong miền của QT-15.
- Viết kiểm thử nạp: mọi trạng thái thuộc bộ trạng thái chuẩn, mọi `guardCel` biên dịch được bằng `vt-cel` và trả `bool`, không có cặp `from:command` trùng.
- Điểm lệch cần lưu ý: TL-02a ghi `DA_GIAO → DANG_THUC_HIEN` khi "nhận việc hoặc cập nhật lần đầu", TL-01 chỉ có lệnh `ACCEPT`; giữ một dòng `ACCEPT` và ghi trong wiki trang 14 rằng cập nhật tiến độ lần đầu phát lệnh `ACCEPT` ngầm (suy luận).

## 4. Ngoài phạm vi

- Máy trạng thái trong mã, `task_state_log`, EVT-06 (CORE-02-13); trạng thái `task_change_request` (viết trong mã, CORE-02-xx).
- Nội dung BPMN của mẫu luồng (nhóm DV-06).

## 5. Tệp được phép sửa

- `data/policy-pack/khoi-dang/quy-dinh-moi/task_lifecycle.yaml`, `data/policy-pack/**/workflow-bindings/**`, phần tham số trong `policy.yaml` của hai gói
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`, `backend/mission-core/src/test/resources/policy-pack/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, gồm kiểm thử nạp bảng chuyển trạng thái và tham số.
- Với CORE-02-13 đã gộp: kiểm thử tích hợp (Testcontainers) chạy đủ chín chuyển trạng thái qua `transitionTask`, chuyển sai trả lỗi `TRANSITION_NOT_ALLOWED`, sai vai trò trả `ROLE_NOT_ALLOWED`, thiếu minh chứng `DA_XAC_NHAN` trả `GUARD_FAILED`.
- Mọi tham số nằm trong miền QT-15; tham số giả định được liệt kê trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
