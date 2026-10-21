# pg_partman 5.1.0 cho kiểm thử

Hai tệp `pg_partman.control` và `pg_partman--5.1.0.sql` là bản cài của tiện ích mở rộng
[pg_partman](https://github.com/pgpartman/pg_partman) 5.1.0 (giấy phép PostgreSQL License), lấy
nguyên vẹn từ thư mục `share/postgresql/16/extension` của ảnh Spilo 16.4.0. pg_partman 5.x viết
hoàn toàn bằng PL/pgSQL (không có `module_pathname`), nên chép hai tệp này vào thư mục extension
của ảnh `postgres:17-alpine` là đủ để `CREATE EXTENSION pg_partman` chạy trong Testcontainers;
tiến trình nền `pg_partman_bgw` không dùng (bảo trì do ứng dụng hoặc CronJob gọi).

Đây chỉ là giải pháp cho kiểm thử khi môi trường dựng không tải được gói hệ điều hành. Ảnh
PostgreSQL dev (NEN-020) và production (INF, CloudNativePG) cài pg_partman bằng gói chính thức
theo TL-03 "Kiểu dữ liệu chuẩn" (tiện ích phải có trong ảnh do đơn vị vận hành ký).
