# Thử tải: CELL-PERF và cụm sinh tải perf-gen (chỉ mục tiêu)

Theo YC-PT-19, giai đoạn hiện tại chỉ phát triển; không dựng `CELL-PERF`, không dựng cụm sinh tải và không chạy kịch
bản PERF. Tệp này ghi mục tiêu để giai đoạn sau làm (INF-21 phần thử tải). Nguồn: TL-05 mục 7.2 và chương 12, TL-04 mục 7.3.

- `CELL-PERF` dùng hồ sơ L trên phần cứng cùng loại với CELL-HCM (kết quả đo chỉ có giá trị khi phần cứng giống sản
  xuất). Ở QM-1 đến QM-3 dùng hồ sơ M; nâng L trước khi CELL-HCM vào vận hành ở QM-4.
- Tầng dữ liệu `CELL-PERF` là khoản mua sắm cố định: 14 máy vật lý (M) hoặc 22 máy (L). Tầng ứng dụng 31 đến 41 node VM,
  chỉ dựng khi chạy chiến dịch thử tải (trước mỗi đợt mở rộng và mỗi quý một lần) trên năng lực dự trữ.
- `perf-gen`: PERF-03 cho một cell hạng L cần 60.000 người dùng ảo ở 6.000 RPS; k6 cần khoảng 150 vCPU, tức 10 máy ảo
  VM-16-32, chỉ tồn tại trong thời gian chạy thử.
- Bộ sinh dữ liệu thử nằm ở `infra/perf/datagen/`. Bảng tài nguyên chung các môi trường phi sản xuất ở
  `deploy/environments/staging/README.md`.
