// Package platform gom các gói nền tối thiểu dùng tạm thay thư viện Go dùng chung NEN-017
// (services-go/vtplatform) vì NEN-017 chưa có trên nhánh (điểm chờ nền móng). Mỗi gói giữ giao diện hẹp để
// khi NEN-017 gộp chỉ cần đổi import, không đổi mã nghiệp vụ:
//
//   - problem: RFC 9457 theo lược đồ ProblemDetails của contracts/openapi/common/common.yaml → vtplatform/problem.
//   - logx: slog JSON, traceparent W3C, span và số đo qua OpenTelemetry API; SDK và exporter do
//     vtplatform/otel cài (ở đây provider toàn cục mặc định là noop).
//   - outbox: ghi registry.outbox_event trong cùng giao dịch; payload tạm là JSON, Avro chờ NEN-007.
//   - idem: khoá chống ghi trùng 48 giờ theo bảng idempotency_key của TL-03.
//   - auth: kiểm JWT Keycloak bằng JWKS (chờ thư viện xác thực dùng chung).
//   - crypto: mã hoá trường C2 bằng AES-256-GCM với khoá từ biến môi trường (chờ NEN-015, OpenBao Transit).
//   - dbx: giao dịch pgx; schema registry không dùng RLS nên không đặt app.tenant_id.
package platform
