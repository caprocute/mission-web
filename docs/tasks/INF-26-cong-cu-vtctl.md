# INF-26 · TL-04 ch.11/OPS-14 Công cụ vận hành `vtctl` cho các thao tác trong runbook

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-26` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nhóm lệnh tenant và DLQ trước tenant thí điểm; các nhóm khác theo dịch vụ tương ứng) |
| Luồng đề xuất | B8 |
| Cỡ việc | L (bắt buộc tách theo nhóm lệnh: (a) khung CLI, xác thực, kiểm toán, `dlq`, `consumer`; (b) `tenant`, `cell`; (c) `period-lock`; (d) `outbox`, `org`, `degrade`) |
| Phụ thuộc | INF-04 (GitOps, môi trường), DV-03-03 (máy trạng thái tenant, API registry); NEN-022 (`outbox-replay`), DV-04-08 (`org-republish`), INF-25 (ghim mức suy giảm), DV-06-05 hoặc CORE-08-05 (khoá kỳ), NEN-017 (thư viện Go) (bổ sung) |
| Truy vết | TL-04 chương 11 vận hành: OPS-14 (thao tác thường xuyên tự động hoá thành lệnh `vtctl`), OPS-24 (`vtctl dlq replay --topic --from --to` qua `<topic>.replay.<consumer>`), OPS-07, OPS-23 → API `/api/v1/registry/*` (`createRegistryTenant`, `getRegistryTenant`, `requestTenantMove`, `approveTenantMove`), `/api/v1/periods/{id}/lock*` (`getPeriodLockProgress`, `pausePeriodLock`, `resumePeriodLock`, `retryFailedPeriodLock`) → SVC-03, MOD-08, SVC-06 → `sys.audit.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-04 yêu cầu các thao tác thường xuyên trong runbook (bật mức suy giảm, khởi động lại consumer, phát lại DLQ) được tự động hoá thành lệnh của công cụ `vtctl` để giảm lỗi thao tác, và VH-01, VH-02 đều ghi "công cụ `vtctl` đề xuất hàng khung mới". Task này viết `vtctl`: một CLI duy nhất cho người trực, xác thực bằng tài khoản vận hành có MFA, mọi lệnh ghi thay đổi đều có chế độ chạy thử, xác nhận, và để lại sự kiện kiểm toán. Lệnh gọi API công bố hoặc công cụ chuyên dụng đã có (không truy cập thẳng CSDL nghiệp vụ). Khi xong, các bước "lệnh thủ công tương đương" trong runbook VH-02 được thay bằng một dòng `vtctl`.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Trực vận hành, mức sự cố và runbook" (L3834–3847, OPS-14), "Quy trình định kỳ khác" (L3857–3873: OPS-23 thêm cell, OPS-24 xử lý thư chết và `vtctl dlq replay`), "Hành vi khi bão hoà" (L1649–1662), RBAC và PAM (L2693–2703: quyền cao qua PAM có thời hạn).
- `docs/quy-dinh/HA-TANG.md`; hợp đồng `contracts/openapi/modules/platform.yaml` (`/registry/*`, `/workflow/sagas/period-close`), `config.yaml` (`/periods/{id}/lock*`).
- Thẻ `docs/tasks/VH-01-runbook-ops-01-13.md`, `VH-02-runbook-ops-14-26.md`, `DV-03-03-*.md`, `NEN-022-phat-lai-outbox-connector.md`, `DV-04-08-org-republish.md`, `INF-25-bo-dieu-khien-suy-giam.md`.

## 3. Phạm vi công việc

- (a) Khung `services-go/vtctl/` (cobra hoặc thư viện CLI của NEN-017 nếu đã chọn): đăng nhập OIDC thiết bị với Keycloak vận hành (`platform-ops`, MFA), chọn cell và môi trường từ `deploy/cells/*/cell.yaml`; mọi lệnh ghi có `--dry-run` mặc định, `--confirm`, in tóm tắt tác động; ghi sự kiện kiểm toán (người chạy, lệnh, tham số, kết quả) qua API ghi kiểm toán hoặc nhật ký cục bộ chuyển SIEM (suy luận, chọn theo INF-19).
- (a) Nhóm `dlq`: `dlq list`, `dlq inspect`, `dlq replay --topic <t> --consumer <c> --from <offset> --to <offset>` phát lại qua `<topic>.replay.<consumer>` đúng OPS-24, `dlq skip` cần lý do và người duyệt thứ hai; nhóm `consumer`: xem độ trễ, khởi động lại Deployment consumer (qua Kubernetes API với quyền `operate-app`).
- (b) Nhóm `tenant`: xem trạng thái, tạm ngừng, kích hoạt lại theo máy trạng thái DV-03-03 bằng API registry; nhóm `cell`: `cell move request|approve|status` bọc `requestTenantMove`, `approveTenantMove` (bốn mắt giữ nguyên ở máy chủ).
- (c) Nhóm `period-lock`: `status`, `pause`, `resume`, `retry-failed` bọc các thao tác `/periods/{id}/lock*` với quyền của người chạy (step-up khi hợp đồng yêu cầu `aal: 2`).
- (d) Nhóm `outbox replay` bọc `outbox-replay` của NEN-022 (chạy dạng Job trong cell), `org republish` bọc DV-04-08, `degrade set|pin|clear` gọi INF-25.
- Lệnh cần quyền cao (`debug-app`, `operate-data`) không tự nâng quyền: kiểm token đang có quyền cấp qua PAM, nếu chưa thì dừng và hướng dẫn xin quyền (INF-27).
- Cập nhật các runbook VH-02 liên quan: thay khối "lệnh thủ công tương đương" bằng lệnh `vtctl`, giữ lệnh thủ công làm phương án dự phòng.

## 4. Ngoài phạm vi

- Logic của từng thao tác ở máy chủ (DV-03-03, NEN-022, DV-04-08, INF-25, CORE-08-05); PAM (INF-27); giao diện quản trị DLQ trên web.

## 5. Tệp được phép sửa

- `services-go/vtctl/**` (mới)
- `deploy/charts/vtctl-jobs/**` (mẫu Job chạy trong cell cho lệnh cần chạy gần dữ liệu, tên suy luận)
- `ops/runbooks/**` (chỉ thay lệnh thủ công bằng lệnh `vtctl` ở mục đã có)
- `wiki/12-ha-tang-dev-va-trien-khai.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/vtctl && go vet ./... && go test ./... && govulncheck ./...` xanh; kiểm thử với máy chủ API giả lập và Kafka Testcontainers cho `dlq replay` (đúng dải offset, đúng topic trung gian).
- Trên kind: `vtctl` chạy thật ít nhất `dlq replay`, `consumer restart`, `tenant status`, `degrade set` (khi INF-25 có), mỗi lệnh ghi đúng một bản ghi kiểm toán; lệnh ghi không có `--confirm` không thay đổi gì.
- Runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6 cho từng nhóm lệnh.

## 7. Báo cáo

Theo AGENTS.md mục 6.
