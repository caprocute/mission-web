# Dữ liệu khởi tạo: khối đối tượng và chức danh (DL-07)

Nguồn dữ liệu cho bảng `org.subject_group` và `org.job_title` (TL-03 mục "Schema org (SVC-04 org-service)") của tenant hệ thống. Tenant thường đọc qua `listSubjectGroups`; BR-01-05 chặn đơn vị không thuộc khối nào; cờ `is_central_managed` dùng ở BR-11-11.

## Trạng thái: dữ liệu giả lập, đã có migration

TL-02a chỉ ghi căn cứ "PL 2A dự thảo Quy định mới (9 khối đối tượng)" và TL-02b ghi "40 chức danh PL 2B", không ghi tên khối, luồng áp dụng hay danh sách chức danh. Ngày 22/10/2026 người dùng chấp nhận dùng dữ liệu giả lập (YC-PT-14), nên:

- `subject_group.csv` có đủ 9 khối `K1`..`K9` với tên, luồng áp dụng, mô tả và ngày hiệu lực giả lập.
- `job_title.csv` có 23 chức danh giả lập, trong đó 10 chức danh mang cờ `is_central_managed = true` thay cho 40 chức danh của PL 2B.

Mọi dòng ghi `giả lập` ở cột `ghi_chu`. Khi có PL 2A, PL 2B, chỉ cần sửa hai tệp CSV rồi sinh lại migration.

## Cột

`subject_group.csv`: `code` (UQ `(tenant_id, code)`), `name`, `flow` (`DANG`, `NHA_NUOC`, `CA_HAI`), `description`, `valid_from`, `valid_to` (trống là còn hiệu lực), cùng `nguon`, `ghi_chu` (chỉ để truy vết, không nạp).

`job_title.csv`: `code`, `name`, `level`, `is_central_managed` (`true` cho 40 chức danh diện Trung ương quản lý theo PL 2B), `subject_group_code` (mã khối; bộ nạp đổi sang `subject_group_id`), `valid_from`, `valid_to`, cùng `nguon`, `ghi_chu`.

`tenant_id` và các cột chung do bộ nạp gắn.

## Cách nạp đề xuất

Migration lặp Flyway `R__org_danh_muc_quoc_gia.sql` trong `backend/services/org-service/src/main/resources/db/migration/org/`, sinh từ CSV bằng `python3 -I data/seed/org/sinh_migration.py` (thêm `--kiem-tra` để chỉ so tệp đã sinh với CSV), chạy bằng vai trò `mig_org` trong Job (tiêu chí K1, TL-03), `INSERT … ON CONFLICT (tenant_id, code) DO UPDATE`. Lý do chọn như lịch nghỉ, xem `data/seed/config/README.md`. Bảng `job_title` một trục thời gian nên sửa danh mục bằng cập nhật `valid_to`, không xoá dòng đã có tham chiếu. Mã bản ghi là UUID tên (uuid5) theo mã danh mục nên chạy lại không đổi id; mục bị bỏ khỏi CSV được khép `valid_to` chứ không xoá. Tenant hệ thống tạm dùng UUID rỗng; tenant thường chưa đọc được danh mục này theo RLS hiện hành (xem wiki trang 14).

## Mẫu nhập cây tổ chức từ Excel (DV-04-05)

`mau-nhap-cay-to-chuc.xlsx` là mẫu cho chức năng "Nhập từ Excel" của SCR-0103 (`POST /api/v1/org/imports`). Trang "Đơn vị" chỉ có dòng tiêu đề, không chứa dữ liệu; trang "Hướng dẫn" ghi cột bắt buộc và cách nhập. Tiêu đề cột: Mã đơn vị, Tên đơn vị, Tên viết tắt, Loại đơn vị, Mã đơn vị cấp trên, Khối đối tượng, Luồng đánh giá, Cấp, Ngày hiệu lực; `org-service` so khớp tiêu đề không phân biệt hoa thường và dấu (`ImportColumns`). Khối đối tượng nhập theo mã `K1`..`K9` của `subject_group.csv`, nên chỉ nhập được sau khi danh mục khối đã nạp.
