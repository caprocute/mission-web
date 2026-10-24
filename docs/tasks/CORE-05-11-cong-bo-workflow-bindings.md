# CORE-05-11 · QT-15/SCR-1504 Lớp công bố `/policies/.../workflow-bindings` của MOD-05 gọi API `wf_binding` của workflow-service

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-05-02 (phiên bản gói, trạng thái đóng băng), DV-06-08 (API nội bộ `wf_binding`, linter, mô phỏng); NEN-014, NEN-016 (OPA, Resilience4j) (bổ sung) |
| Truy vết | QT-15 → SCR-1504 → API `GET /api/v1/policies/packs/{id}/versions/{v}/workflow-bindings` (`listWorkflowBindings`), `GET/PUT .../workflow-bindings/{key}` (`getWorkflowBinding`, `saveWorkflowBinding`), `POST /api/v1/policies/workflow-bindings/simulate` (`simulateWorkflowBinding`), `GET /api/v1/policies/workflow-templates` (`listPolicyWorkflowTemplates`), `GET /api/v1/policies/workflow-templates/{key}/versions/{v}` (`getPolicyWorkflowTemplateVersion`) → MOD-05 → SVC-06 → bảng `flowable.wf_binding`, `flowable.wf_template`; BR-15-11, BR-15-12; MSG-15-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hợp đồng đặt sáu thao tác cấu hình luồng duyệt dưới tiền tố `/policies` với `owner: MOD-05`, trong khi dữ liệu nằm ở `flowable.wf_binding` của workflow-service (SVC-06) và DV-06-08 chỉ làm API nội bộ của SVC-06. Task này làm lớp công bố trong mission-core: kiểm quyền và trạng thái phiên bản gói ở MOD-05, rồi gọi API nội bộ của SVC-06 để đọc, lưu, mô phỏng. Khi xong, người soạn chính sách cấu hình luồng duyệt ở SCR-1504 qua đúng hợp đồng, tham số gắn với phiên bản Policy Pack và bị khoá khi phiên bản đã đạt kiểm thử.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-15 BR-15-11, BR-15-12 (L1367–1368), ràng buộc tham số (L1421–1428), "SCR-1504. Cấu hình luồng duyệt (mẫu + tham số)" (L1541–1590).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "ADR-04. Ba tầng điều phối luồng" (L3422), "Quy tắc quản trị chống mạng nhện" (L2149–2153).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `flowable.wf_binding` (L3256) và schema `policy` (trigger đóng băng từ `DAT_KIEM_THU`).
- `contracts/openapi/modules/policies.yaml` (L1296–1560: sáu thao tác, lược đồ `WorkflowBinding`, `WorkflowSimulateRequest`, mã lỗi `POLICY-409-VERSION-CONFLICT`, `POLICY-409-VERSION-FROZEN`, `POLICY-409-PERIOD-LOCKED`, `POLICY-422-WF-PARAMS`).
- Thẻ `docs/tasks/DV-06-08-api-wf-binding.md` (API nội bộ, điểm lệch chủ sở hữu), `CORE-05-02-*.md`; `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-1504.

## 3. Phạm vi công việc

- Viết cổng ra `policy.infrastructure.WorkflowBindingClient` gọi API nội bộ của SVC-06 do DV-06-08 công bố (token client credentials của mission-core, mTLS trong lưới), có Resilience4j (timeout 3 s, circuit breaker); lỗi SVC-06 trả 503 kèm `Retry-After`, không bao giờ trả dữ liệu cũ khi lưu.
- Hiện thực `listWorkflowBindings`, `getWorkflowBinding` (roles `policy_author`, `decider`): kiểm phiên bản gói tồn tại và thuộc tenant, đọc từ SVC-06 theo khoá (tenant, `process_kind`, `subject_group_id`, `flow`, `period_type`) và ánh xạ sang lược đồ hợp đồng.
- Hiện thực `saveWorkflowBinding` (roles `policy_author`, Idempotency-Key, If-Match): chặn khi phiên bản gói đã từ `DAT_KIEM_THU` trở đi (`POLICY-409-VERSION-FROZEN`) hoặc kỳ bắt đầu áp dụng đã khoá (`POLICY-409-PERIOD-LOCKED`); chuyển lỗi linter 422 của SVC-06 thành `POLICY-422-WF-PARAMS` với `errors[]` giữ nguyên quy tắc vi phạm (MSG-15-09); ETag của hợp đồng ánh xạ từ phiên bản dòng ở SVC-06.
- Hiện thực `simulateWorkflowBinding`, `listPolicyWorkflowTemplates`, `getPolicyWorkflowTemplateVersion` bằng cách chuyển tiếp có kiểm quyền; mô phỏng không ghi gì ở cả hai phía.
- Đưa tham số luồng vào băm nội dung của phiên bản gói (`content_hash`) nếu CORE-05-02 đã chọn băm cả `wf_binding` (suy luận; nếu chưa chốt thì ghi câu hỏi vào `wiki/14`, không tự đổi cách băm).
- Điểm lệch cần lưu ý: API giữa MOD-05 và SVC-06 không có trong hợp đồng OpenAPI công bố (DV-06-08 ghi "API nội bộ"); nếu người phụ trách yêu cầu mọi lời gọi giữa dịch vụ phải có hợp đồng thì đề xuất thêm thao tác `roles: [service_client]` vào `platform.yaml` theo tiền lệ `startPeriodCloseSaga`, ghi vào `wiki/14`, không tự sửa hợp đồng trong task này.
- Kiểm thử: MockWebServer giả SVC-06 cho đường thành công, 422 linter, 409 phiên bản, SVC-06 ngừng; phiên bản đóng băng chặn lưu; người không có `policy_author` bị 403.

## 4. Ngoài phạm vi

- API nội bộ, linter, engine mô phỏng của SVC-06 (DV-06-08); mẫu BPMN (DV-06-03, DV-06-04, DV-06-12); màn hình SCR-1504 (WEB-046).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh gồm kiểm thử mục 3, ArchUnit/Modulith, kiểm thử hợp đồng cho sáu `operationId`.
- Khi DV-06-08 đã gộp: một kiểm thử tích hợp chạy SVC-06 thật bằng Testcontainers lưu rồi đọc lại một `wf_binding`; nếu chưa gộp, ghi rõ trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
