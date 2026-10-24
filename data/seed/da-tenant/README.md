# Bộ dữ liệu thử đa tenant (giả lập)

Ba tenant hư cấu của tỉnh giả định Phú Lâm để trải nghiệm và kiểm thử trên môi trường dev: Đảng bộ tỉnh (khối Đảng), Ủy ban nhân dân tỉnh (khối chính quyền) và Công ty TNHH MTV Cấp nước (doanh nghiệp nhà nước), 51 tài khoản với vai trò đa dạng theo ma trận phân quyền. Không có dữ liệu cá nhân thật; tên người, đơn vị và số hiệu văn bản căn cứ là hư cấu.

| Tệp | Nội dung |
|---|---|
| `du_lieu.py` | Nguồn duy nhất: tenant, đơn vị, chức danh, cán bộ và vai trò, căn cứ, danh mục công việc, OKR, nhiệm vụ |
| `sinh_seed.py` | Kiểm tính nhất quán của nguồn rồi sinh ba tệp bên dưới; `--kiem` chỉ kiểm tệp đã commit khớp nguồn |
| `TAI-KHOAN.md` | Bảng tài khoản theo tenant (sinh tự động) |
| `test_du_lieu.py` | Kiểm thử: nguồn không lỗi, tệp sinh khớp nguồn, tài khoản không trùng realm cũ |
| `../../../infra/dev/postgres/seed/da-tenant-du-lieu-thu.sql` | tenant-registry, tổ chức, căn cứ OKR, danh mục công việc (sinh tự động) |
| `../../../infra/dev/keycloak/tai-khoan-da-tenant.json` | Tài khoản realm `mission`, mật khẩu và OTP là biến thay thế (sinh tự động) |

Nạp lên dev: `infra/dev/scripts/seed-da-tenant.sh` (SQL, tài khoản, đồng bộ tổ chức, rồi `seed-da-tenant-api.py` tạo kỳ quý, OKR, check-in, nhiệm vụ qua API). Kịch bản trải nghiệm từng bước: `docs/kich-ban-trai-nghiem/KICH-BAN-DA-TENANT.md`.

```bash
python3 -I data/seed/da-tenant/sinh_seed.py                                   # sinh lại sau khi sửa du_lieu.py
python3 -I -m unittest discover -s data/seed/da-tenant -p 'test_*.py'        # kiểm thử
```
