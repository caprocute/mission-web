# Bộ dữ liệu thử đa tenant (giả lập)

Ba tenant hư cấu của tỉnh giả định Phú Lâm để trải nghiệm và kiểm thử trên môi trường dev: Đảng bộ tỉnh (khối Đảng), Ủy ban nhân dân tỉnh (khối chính quyền) và Công ty TNHH MTV Cấp nước (doanh nghiệp nhà nước), 51 tài khoản với vai trò đa dạng theo ma trận phân quyền. Không có dữ liệu cá nhân thật; tên người, đơn vị và số hiệu văn bản căn cứ là hư cấu.

| Tệp | Nội dung |
|---|---|
| `du_lieu.py` | Nguồn duy nhất: tenant, đơn vị, chức danh, cán bộ và vai trò, căn cứ, danh mục công việc, OKR (đủ trạng thái, nhiều lần check-in), nhiệm vụ (đủ bảy trạng thái kèm điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại), kế hoạch quý, uỷ quyền, phân quyền, kỳ trước và Policy Pack mẫu |
| `sinh_seed.py` | Kiểm tính nhất quán của nguồn rồi sinh các tệp bên dưới; `--kiem` chỉ kiểm tệp đã commit khớp nguồn |
| `sinh_bang_doi_chieu.py` | Sinh bảng đối chiếu vai trò × tính năng × dữ liệu thử từ ma trận phân quyền (YC-PT-25); `--kiem` kiểm tệp đã commit |
| `TAI-KHOAN.md` | Bảng tài khoản theo tenant (sinh tự động) |
| `test_du_lieu.py` | Kiểm thử: nguồn không lỗi, tệp sinh và bảng đối chiếu khớp nguồn, tài khoản không trùng realm cũ, mọi chức năng web đều có tình trạng |
| `../../../infra/dev/postgres/seed/da-tenant-du-lieu-thu.sql` | tenant-registry, tổ chức, căn cứ OKR, danh mục công việc (sinh tự động) |
| `../../../infra/dev/postgres/seed/da-tenant-policy-{dang,cq,dn}.sql` | Policy Pack mẫu bản nháp của từng tenant, sinh bằng `data/policy-pack/sinh_seed_dev.py` (cần PyYAML) |
| `../../../infra/dev/keycloak/tai-khoan-da-tenant.json` | Tài khoản realm `mission`, mật khẩu và OTP là biến thay thế (sinh tự động) |
| `../../../docs/kich-ban-trai-nghiem/BANG-DOI-CHIEU-VAI-TRO.md` | Bảng đối chiếu (sinh tự động) |

Nạp lên dev: `infra/dev/scripts/seed-da-tenant.sh` (SQL kèm Policy Pack, tài khoản, đồng bộ tổ chức, rồi `seed-da-tenant-api.py` tạo qua API như người dùng thật: kỳ quý trước với OKR đã duyệt và lệnh khoá đã duyệt; kỳ hiện hành với OKR mọi trạng thái, check-in, nhiệm vụ bảy trạng thái, điểm nghẽn, đề nghị điều chỉnh, nhắc việc, giao lại, kế hoạch quý, uỷ quyền, phân quyền; cuối cùng in bảng thống kê mới/đã có theo tenant và các bước lỗi). Bộ nạp chạy lại an toàn: bước nào đã có thì đếm là "đã có", bước lỗi không chặn bước sau. Kịch bản trải nghiệm từng bước: `docs/kich-ban-trai-nghiem/KICH-BAN-DA-TENANT.md`.

```bash
python3 -I data/seed/da-tenant/sinh_seed.py                                   # sinh lại sau khi sửa du_lieu.py
python3 -I data/seed/da-tenant/sinh_bang_doi_chieu.py                         # sinh lại bảng đối chiếu
python3 -I -m unittest discover -s data/seed/da-tenant -p 'test_*.py'        # kiểm thử
```

Thử bộ nạp API không cần máy chủ thật: đặt `SEED_FAKE_TOKEN=1` (token giả, không ký) và `SEED_API` trỏ tới một máy chủ giả lập các đường dẫn mà bộ nạp gọi; bộ nạp chỉ dùng cách này để kiểm luồng, không dùng được với máy chủ thật.

Giới hạn đã biết: minh chứng (tệp thật qua quét vi-rút), hồ sơ đánh giá cuối kỳ (cần phát hành Policy Pack rồi khoá kỳ), đăng ký nhiệm vụ (chưa có vị trí việc làm), UI Pack (cần Python 3.10 ở máy nạp) không nạp sẵn; lý do và cách mở khoá ghi trong bảng đối chiếu.
