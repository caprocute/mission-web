# OPA 1.x cho môi trường dev (`infra/dev/opa/`)

Thư mục này chứa chính sách Rego v1挂载 vào container `mission-dev-opa` (cổng `${OPA_PORT:-35181}` -> `8181`, profile `security`).

## Cấu trúc

- `policies/authz.rego` — chính sách khởi tạo gói `okrkpi.authz` (rule `allow`, `reason`, `decision`) để ứng dụng và kiểm thử tích hợp gọi `/v1/data/okrkpi/authz/decision` ngay khi bật profile `security`.
- Các tệp chính sách chi tiết theo ma trận phân quyền (`docs/phan-quyen/ma-tran.json`) được bổ sung ở `NEN-023` và nạp tự động qua `--watch /policies`.

## Kiểm tra nhanh

```bash
curl -fsS http://127.0.0.1:${OPA_PORT:-35181}/health

curl -fsS -X POST http://127.0.0.1:${OPA_PORT:-35181}/v1/data/okrkpi/authz/decision \
  -H "Content-Type: application/json" \
  -d '{
    "input": {
      "subject": {"sub": "user-01", "tenant_id": "11111111-1111-7111-8111-111111111111", "roles": ["CAN_BO"]},
      "action": "task:read",
      "resource": {"tenant_id": "11111111-1111-7111-8111-111111111111"}
    }
  }'
```
