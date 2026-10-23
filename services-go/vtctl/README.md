# `vtctl`: công cụ vận hành cho người trực (INF-26)

`vtctl` gom các thao tác thường xuyên trong runbook thành lệnh (TL-04 OPS-14), để người trực không phải gõ tay `curl`, `helm template | kubectl create` hay đọc token từ Secret. Lệnh chỉ gọi API công bố hoặc công cụ chuyên dụng đã có (Kafka, `kubectl`, `helm`), không truy cập thẳng CSDL nghiệp vụ; máy chủ vẫn là nơi kiểm quyền, bốn mắt và nghiệp vụ.

## Nguyên tắc an toàn

Mọi lệnh ghi mặc định chỉ chạy thử. Ở chế độ này vtctl in tóm tắt tác động và chỉ đọc hoặc kiểm phía máy chủ không lưu (ví dụ `kubectl create --dry-run=server`, đọc DLQ để đếm). Thêm `--confirm` mới thực hiện, và khi đã gõ `--dry-run` tường minh thì chạy thử luôn thắng. Mỗi lệnh ghi đã thực hiện (thành công hay thất bại) để lại đúng một bản ghi kiểm toán JSON một dòng trong `auditLog` (mặc định `~/.local/state/vtctl/audit.jsonl`, quyền 0600): người chạy, môi trường, cell, lệnh, tham số (che giá trị mang tên bí mật), Idempotency-Key, kết quả. Fluent Bit của máy trạm vận hành hoặc pod Job chuyển tệp này sang SIEM (suy luận, chốt theo INF-19); thao tác phía máy chủ còn có kiểm toán riêng trên `sys.audit.v1`.

Đăng nhập dùng luồng thiết bị OAuth 2.0 (RFC 8628) với Keycloak vận hành `platform-ops`, có MFA. Token lưu theo môi trường ở `tokenDir` (quyền 0600). Thao tác mà hợp đồng ghi `aal: 2` gửi token bậc 2 còn trong 5 phút; nếu chưa có, vtctl tự mở luồng thiết bị với `acr_values=aal2`, và nếu máy chủ vẫn trả 401 `insufficient_user_authentication` thì xin bước lên lại và gửi lại đúng một lần với cùng Idempotency-Key. Lệnh ghi qua API tự đọc ETag làm `If-Match`.

Lệnh cần quyền Kubernetes cao không tự nâng quyền. vtctl hỏi `kubectl auth can-i` trước; nếu chưa có quyền (`operate-app` cho khởi động lại consumer và đọc token bộ điều khiển suy giảm, `operate-data` cho Job phát lại), lệnh dừng và hướng dẫn xin quyền có thời hạn qua PAM (INF-27, TL-04 SEC-29).

## Cấu hình

Chép `vtctl.example.yaml` về `~/.config/vtctl/config.yaml` (hoặc đặt `VTCTL_CONFIG`). Tệp khai báo môi trường (issuer Keycloak vận hành, `clientId`, `registryURL`) và cell. Tên cell phải trùng thư mục `deploy/cells/<cell>/cell.yaml`; nếu cell không khai `apiURL`, vtctl lấy `apiBaseUrl` của cell trong registry theo `displayName` của `cell.yaml`. Kafka mặc định dùng mTLS, khoá chỉ ghi đường dẫn tệp. Tệp cấu hình không được chứa mật khẩu hay token; trường lạ bị từ chối.

## Lệnh

| Nhóm | Lệnh | Gọi tới | Truy vết |
|---|---|---|---|
| Đăng nhập | `login [--step-up]`, `logout`, `whoami` | Keycloak `platform-ops` | SEC-29, TL-01 step-up |
| `dlq` | `list`, `inspect`, `replay --topic --consumer --from --to [--partition]`, `skip --partition --to --approver` | Kafka của cell: `<topic>.dlq.<consumer>` → `<topic>.replay.<consumer>`, nhóm `dlq-replayer` | OPS-24 |
| `consumer` | `lag [--group]`, `restart --deployment -n` | Kafka; `kubectl rollout restart` | OPS-14 |
| `tenant` | `status <id>`, `activate <id> --reason`, `suspend <id> --reason` | `getRegistryTenant`, `activateConfigTenant`, `suspendConfigTenant` | QT-01, DV-03-03 |
| `cell` | `list`, `move request|approve|status|cancel` | `/registry/cells`, `requestTenantMove`, `approveTenantMove`, `getTenantMove`, `cancelTenantMove` | QT-01, ADR-29 |
| `period-lock` | `status|pause|resume|retry-failed <period-id>` | `/periods/{id}/lock*` của cổng API cell | QT-08, MOD-08, SVC-06 |
| `outbox` | `replay --schema --tenant --from --to [--consumer]` | Job `outbox-replay` (NEN-022) từ chart `deploy/charts/vtctl-jobs` | DR-10, OPS-24 |
| `org` | `republish --tenant [--entity] [--bucket]` | Job `org-republish` (DV-04-08) từ chart `org-service` | TL-03 ECST |
| `degrade` | `status`, `set <0..3>`, `pin <0..3> --for <thời hạn>`, `clear` | bộ điều khiển suy giảm (INF-25) `/level` | TL-04 mục 6.9 |

Phát lại DLQ đọc đúng dải offset (gồm hai đầu), giữ nguyên khoá, thân Avro và header CloudEvents, bỏ header lỗi `x-dlq-*`, `x-inbox-dlq-*`, thêm `ce_replayed=true`, rồi commit vị trí của nhóm `dlq-replayer` tới bản ghi cuối cộng một (không lùi vị trí đã cao hơn). Bỏ qua có duyệt chỉ đẩy vị trí đó, không xoá hay phát gì; lệnh cần người duyệt khác người chạy và người chạy vừa xác thực bậc 2. `dlq list` tính số chưa xử lý từ vị trí này (suy luận: danh mục topic ghi consumer của DLQ là `dlq-replayer` nhưng không mô tả cách đếm).

Ví dụ:

```bash
vtctl -e staging login
vtctl -e staging -c hn dlq list
vtctl -e staging -c hn dlq replay --topic task.task.assigned.v1 --consumer scoring-service \
  --partition 0 --from 120 --to 180 --reason "INC-2026-1023 sửa dữ liệu nguồn"          # chạy thử
vtctl -e staging -c hn dlq replay ... --confirm                                          # thực hiện
vtctl -e staging cell move approve <tenant-id> <move-id> --confirm
```

Mã thoát: 0 thành công, 1 lỗi thao tác, 2 sai tham số.

## Kiểm thử

```bash
cd services-go/vtctl
go vet ./... && go test ./... && govulncheck ./...
VTCTL_KAFKA_IT=1 go test ./internal/dlq/   # Kafka thật qua Testcontainers (cần Docker)
```

Kiểm thử dùng Keycloak, registry, cổng API cell và bộ điều khiển suy giảm giả lập bằng `httptest`, `kubectl` và `helm` giả, và Kafka qua cụm `kfake` trong tiến trình (mặc định) hoặc Kafka thật qua Testcontainers khi đặt `VTCTL_KAFKA_IT=1`.

## Giới hạn hiện tại

- `suspendConfigTenant` (`DANG_VAN_HANH` → `TAM_NGUNG`) đã có trong hợp đồng và tenant-registry; ma trận phân quyền `docs/phan-quyen/ma-tran.json` chưa có dòng cho thao tác này (chờ duyệt).
- `degrade pin` cần bộ điều khiển Go của INF-25 (`services-go/degrade-controller`); bộ điều khiển Python cũ của DV-01-02 bỏ qua trường `ttl` và giữ mức đặt tay tới khi `clear`.
- Job `outbox-replay` đọc `DATABASE_URL`, `KAFKA_BROKERS`, `KAFKA_TLS_*` từ Secret khai ở `outboxReplay.envFromSecret` (xem runbook `phat-lai-outbox`).
- Phòng ngừa bốn mắt của `dlq skip` nằm ở phía công cụ (không có API máy chủ cho DLQ); bản ghi kiểm toán giữ tên người duyệt.
