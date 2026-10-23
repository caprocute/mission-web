# Luồng SIEM và quy tắc phát hiện tối thiểu cho SOC

> Nguồn: TL-04 mục 9.7 "SOC và SIEM" (SEC-43), mục 9.3 (SEC-22, SEC-29, SEC-30, SEC-50), mục 10.3 (G6, `sys.audit.v1`); `_ghi-chu-TL-04` dòng 56 (`sys.siem.v1`, `sys.security.runtime.v1` do TL-04 tự đặt); INF-19.
> Thành phần: `deploy/charts/otel-collector/templates/siem.yaml` (otel-siem, chặng ra SOC), `deploy/charts/security-cell` (Falco, Falcosidekick), `deploy/clusters/cell-cluster/files/audit-policy.yaml`.

Tài liệu này là bản thống nhất với SOC (dịch vụ ngoài, SEC-43) trước khi vận hành: cell gửi gì, ở dạng nào, và tám quy tắc phát hiện tối thiểu SOC phải cấu hình phía SIEM. Vận hành SIEM và trực SOC 24/7 nằm ngoài phạm vi cell; cell chỉ bảo đảm luồng dữ liệu đầy đủ, đúng lược đồ và có bằng chứng.

## Luồng dữ liệu

| Nguồn | Đường vào otel-siem | `event.module` |
|---|---|---|
| Nhật ký kiểm toán API Kubernetes (chính sách `audit-policy.yaml`) | Fluent Bit đọc `/var/log/kubernetes/audit/audit.log` trên master → Kafka `sys.audit.v1` | `kubernetes` |
| Quyết định từ chối của OPA (`vt-authz`, SEC-18, SEC-22) | Log ECS `event.dataset=security`, Fluent Bit tách sang `sys.audit.v1` | `opa` |
| WAF Coraza trong APISIX (SEC-34) | Log ECS của APISIX, `event.dataset=security` → `sys.audit.v1` | `waf` |
| Sự kiện kiểm toán và an ninh JSON của ứng dụng | Log ECS `event.dataset=audit` hoặc `security` → `sys.audit.v1` | `mission` |
| Sự kiện xác thực Keycloak | Kafka `iam.auth.event.v1` (Avro `AuthEvent`) | `keycloak` |
| Cảnh báo Falco (SEC-50) | Falco → Falcosidekick → OTLP/HTTP mTLS → otel-siem → Kafka `sys.security.runtime.v1` | `falco` |
| Nhật ký kiểm toán OpenBao | Thiết bị kiểm toán `socket` của OpenBao → TCP 54525 của otel-siem | `openbao` |
| Phiên PAM (SEC-42) | Syslog RFC 5424 qua TLS → cổng 6514 của otel-siem | `pam` |

otel-siem chuẩn hoá mọi bản ghi theo ECS 8.11 (khoá có dấu chấm: `@timestamp`, `event.module`, `event.dataset`, `event.action`, `event.outcome`, `user.name`, `user.id`, `source.ip`, `organization.id` là tenant, `labels.cell`), giữ bản gốc dưới khoá riêng của nguồn (`kubernetes.audit`, `keycloak`, `falco`, `openbao`, `pam`, `mission`) và ghi Kafka `sys.siem.v1` bằng mTLS. Chặng ra SOC (`siem-soc-forwarder`, namespace `egress`) đọc `sys.siem.v1` và ghi sang Kafka của SOC bằng mTLS; chỉ cell mở kết nối ra, SOC không có đường vào cell (một chiều).

Thẻ `vt.phat_hien` đánh dấu sự kiện thuộc quy tắc dưới đây, để SOC đếm và đặt ngưỡng mà không phải tự phân tích từng nguồn.

## Tám quy tắc phát hiện tối thiểu

| # | Quy tắc (TL-04) | Thẻ `vt.phat_hien` và điều kiện | Ngưỡng đề xuất | Mức |
|---|---|---|---|---|
| 1 | Nhiều lần đăng nhập thất bại | `dang-nhap-that-bai`: Keycloak `LOGIN_ERROR`; API server trả 401 | ≥ 5 lần cùng `user.id` hoặc `source.ip` trong 15 phút; số lần sai theo `organization.id` tăng gấp 3 trung bình 7 ngày | Cao |
| 2 | Truy cập phá kính | `pha-kinh`: tạo binding tới `platform-admin`; phiên PAM gắn nhãn phá kính | Mọi sự kiện | Nghiêm trọng |
| 3 | Thay đổi RBAC | `doi-rbac`: create, update, patch, delete trên `rbac.authorization.k8s.io` | Mọi sự kiện không do Argo CD (`user.name` khác `system:serviceaccount:argocd:*`) hoặc PAM | Cao |
| 4 | Ảnh không ký bị từ chối | `anh-khong-ky`: API server từ chối với chính sách `vt-require-signed-images` | Mọi sự kiện | Cao |
| 5 | Shell trong container | `shell-container`: Falco "VT shell trong container…"; `pods/exec` trong nhật ký kiểm toán API | Mọi sự kiện không khớp phiên PAM `debug-app` đang mở | Nghiêm trọng |
| 6 | Xuất dữ liệu bất thường | `xuat-hang-loat`: sự kiện kiểm toán `XUAT_HANG_LOAT` hoặc hành động xuất | Một `user.id` vượt 1.000 hồ sơ trong 1 giờ | Cao |
| 7 | `decrypt` Transit tăng đột biến | `transit-decrypt`: OpenBao `transit/decrypt/*` | Gấp 3 trung vị cùng khung giờ 7 ngày theo `user.name` (danh tính dịch vụ) | Cao |
| 8 | Chuỗi băm đứt | `chuoi-bam-dut`: sự kiện an ninh `ledger.verify.failed` của sổ cái | Mọi sự kiện (kèm cảnh báo Prometheus `LedgerChainBroken`) | Nghiêm trọng |

Ngoài tám quy tắc, SIEM nên giữ các quy tắc bổ sung của TL-04 mục 9.3: đăng nhập từ quốc gia mới, đăng nhập đồng thời từ hai vị trí xa. Mức Nghiêm trọng tương ứng Critical: SOC phản hồi trong 15 phút (TL-04 mục 9.7).

## Xử lý khi SOC báo

1. Xác định nguồn qua `event.module`, `labels.cell`, `organization.id`; lấy bản gốc trong khoá của nguồn.
2. Quy tắc 2, 3, 5: đối chiếu danh sách phiên PAM đang mở (`an-ninh-pam-rbac.md`). Không khớp phiên nào: coi là sự cố, thu hồi binding (`kubectl delete clusterrolebinding <tên>`), khoá tài khoản trong realm `platform-ops`, cô lập pod liên quan (gắn nhãn `vt.io/cach-ly=true`, NetworkPolicy chặn toàn bộ), giữ pod để điều tra trước khi xoá.
3. Quy tắc 1: kiểm tra Keycloak đã khoá tạm (5 lần sai, 15 phút); tăng đột biến theo tenant thì báo đội IAM và bật giới hạn theo IP ở APISIX.
4. Quy tắc 4: xem nhật ký Kyverno (`kyverno_policy_results_total`), xác định pipeline nào đẩy ảnh không ký; không cấp `PolicyException` khi chưa qua duyệt bảo mật.
5. Quy tắc 6, 7: tìm danh tính trong `user.id` hoặc `user.name`; tạm khoá tài khoản hoặc thu hồi token dịch vụ (`bao token revoke -accessor`), báo chủ dữ liệu.
6. Quy tắc 8: theo runbook DR-14 của sổ cái; không ghi thêm vào sổ cái cho tới khi đội sổ cái xác nhận.

## Kiểm chứng luồng

- Tĩnh: `deploy/charts/otel-collector/tests/chay-kiem-thu.sh` (otelcol validate cho otel-siem và chặng ra SOC, chạy thật bộ xử lý ECS trên bản ghi mẫu của từng quy tắc).
- Trên cụm: `kubectl -n observability logs deploy/otel-siem`, chỉ số `otelcol_exporter_sent_log_records{exporter="kafka/siem"}` tăng; đọc thử một thông điệp `sys.siem.v1` bằng tài khoản Kafka có quyền đọc.
- Hằng tháng: SOC gửi lại số sự kiện nhận theo `event.module`; chênh lệch so với `otelcol_exporter_sent_log_records` phải bằng 0.

## Quay lui

Lỗi ở otel-siem không làm mất dữ liệu kiểm toán: `sys.audit.v1` vẫn được `audit-archiver` ghi vào kho Object Lock 10 năm. Quay về phiên bản chart trước bằng Argo CD (rollback Application `otel-collector`); consumer group `siem-forwarder` đọc tiếp từ offset đã commit. Muốn gửi lại cho SOC một khoảng thời gian: đặt lại offset của group `siem-soc-forwarder` (`kafka-consumer-groups.sh --reset-offsets --to-datetime`).

## Giới hạn đã biết

- Bản phát hành otelcol-contrib không có phần mở rộng `avro_log_encoding`; sự kiện Avro (`iam.auth.event.v1`, sự kiện kiểm toán Avro của ứng dụng) chỉ vào SIEM khi dùng ảnh collector tự dựng bằng OCB và đặt `siem.avro.enabled: true`. Trước đó bản ghi không phải JSON trong `sys.audit.v1` bị bỏ khỏi luồng SIEM.
- KafkaUser `siem-forwarder` hiện chỉ có quyền đọc `sys.audit.v1`, `iam.auth.event.v1`; cần thêm quyền đọc, ghi `sys.security.runtime.v1` và ghi `sys.siem.v1`, và hai topic này cần vào danh mục topic (wiki 14).
- OpenBao chưa bật thiết bị kiểm toán `socket`; PAM chưa có hệ thống thật (SEC-42, SEC-58).
