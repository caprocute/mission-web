# Mẫu sổ tay vận hành (OPS-14)

> **Truy vết:** TL-04 mục 11.7 (OPS-14: triệu chứng, tác động, kiểm tra nhanh, hành động theo nhánh, leo thang, xác nhận đã khắc phục) · VH-01, VH-02.
> **Chu kỳ:** theo bảng OPS · **Chịu trách nhiệm:** theo bảng OPS · **Tự động hoá:** theo bảng OPS.
> **Chạy thử:** `<ngày>` · `<người hoặc luồng chạy>` · `<kind | staging>`; hoặc "chưa chạy thử" kèm lý do.

Mọi sổ tay trong `ops/runbooks/ops/` (quy trình OPS) và `ops/runbooks/canh-bao/` (theo cảnh báo) dùng đúng các mục dưới đây, theo đúng thứ tự, để người trực cấp 1 tìm được cùng một thông tin ở cùng một chỗ. Sổ tay chi tiết đã có ở `ops/runbooks/` (ví dụ `cap-phat-kich-hoat-tenant.md`, `saga-chot-ky.md`) được dẫn tới thay vì chép lại.

Quy ước chung:

- Giá trị phụ thuộc môi trường viết dạng `<cell>`, `<site>`, `<service>`, `<tenant-id>`; địa chỉ máy chủ lấy từ cấu hình `vtctl` (`~/.config/vtctl/config.yaml`) hoặc biến môi trường như `ARGOCD_SERVER`. Không ghi địa chỉ IP, tài khoản, mật khẩu hay token vào sổ tay; bí mật chỉ tham chiếu đường dẫn trong OpenBao.
- Lệnh ghi của `vtctl` mặc định chạy thử; chỉ thêm `--confirm` sau khi đã đọc tóm tắt tác động. Thao tác cần quyền cao xin quyền có thời hạn qua PAM (`an-ninh-pam-rbac.md`), không dùng tài khoản dùng chung.
- Thay đổi cấu hình đi qua merge request có chữ ký vào kho GitOps (`deploy/`), không sửa tay tài nguyên trên cụm, trừ khi mục "Các bước" ghi rõ ngoại lệ.

## Điều kiện kích hoạt

Khi nào mở sổ tay này: lịch định kỳ, yêu cầu, hoặc cảnh báo cụ thể (tên cảnh báo, ngưỡng). Với sổ tay theo cảnh báo, mục này mô tả triệu chứng mà cảnh báo đo.

## Tác động

Người dùng hay dịch vụ nào bị ảnh hưởng, mức sự cố mặc định theo bảng SEV-1 đến SEV-4 của OPS-13.

## Kiểm tra nhanh

Các lệnh, truy vấn PromQL hoặc SQL chỉ đọc và dashboard cần xem trước khi hành động, mỗi mục ghi điều cần đọc ra.

## Các bước

Hành động theo nhánh: mỗi nhánh mở đầu bằng điều kiện ("Nếu ..."), các bước đánh số.

## Lệnh vtctl và lệnh tương đương

Bảng thao tác → lệnh `vtctl` (khi có) → lệnh thủ công tương đương (`kubectl`, `argocd`, `helm`, SQL).

## Kiểm sau khi làm

Cách xác nhận đã hoàn tất: chỉ số về ngưỡng, trạng thái đối tượng, cảnh báo tự đóng.

## Quay lui

Cách đưa hệ thống về trạng thái trước đó nếu bước làm gây hại, và giới hạn của việc quay lui.

## Leo thang

Khi nào gọi cấp 2 hoặc nâng mức sự cố, gọi ai (theo sơ đồ gọi trực của OPS-13).

## Liên quan

Sổ tay, tài liệu thiết kế và mã nguồn liên quan.
