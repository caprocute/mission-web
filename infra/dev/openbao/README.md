# OpenBao 2.x cho môi trường dev (`infra/dev/openbao/`)

Thư mục này chứa script khởi tạo `init.sh` cho container `mission-dev-openbao` (cổng `${OPENBAO_PORT:-35820}` -> `8200`, profile `security`).

## Thành phần được khởi tạo tự động

Khi chạy `docker compose --profile security up -d`, dịch vụ một lần `openbao-init` thực thi `init.sh` để:

1. Bật secrets engine `transit/` (mã hoá phong bì DEK theo `ADR-06`, `NEN-015`).
2. Bật secrets engine `secret/` (`kv-v2`) cho cấu hình bí mật dùng chung ở dev.
3. Tạo ba khoá Transit (`aes256-gcm96`, `exportable=false`):
   - `kek-default` — khoá KEK mặc định cho môi trường dev;
   - `kek-tenant-demo` — khoá KEK mẫu tách theo tenant;
   - `blind-index-default` — khoá sinh chỉ mục mù HMAC-SHA256 cho trường dữ liệu mức C2 cần tìm kiếm đẳng thức.

## Lưu ý bảo mật

- `OPENBAO_DEV_ROOT_TOKEN` chỉ đọc từ `infra/dev/.env` (đã nằm trong `.gitignore`); `.env.example` chỉ chứa giá trị mẫu.
- Chế độ `-dev` lưu dữ liệu trong bộ nhớ và chỉ dùng cho phát triển/kiểm thử; môi trường thật triển khai cụm OpenBao HA kèm auto-unseal theo `INF-03`.
