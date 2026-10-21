# Ghi chú TL-05: điểm cần đồng bộ với TL-01..TL-04

TL-05 (`src/TL-05-dinh-co-he-thong.md`, dựng từ `src/TL-05/part-01.md` đến `part-06.md`) cấp mã mới GD-01..GD-26 (giả định định cỡ), QM-1..QM-5 (bậc quy mô), NG-01..NG-14 (ngưỡng nâng bậc); sơ đồ tiền tố `dc-`. Không đổi mã hay tên thành phần nào đã có.

Đề xuất sửa nhỏ TL-04 (chưa áp dụng vì thread kiến trúc đang cập nhật TL-01..04):
1. TL-04 mục 4.4, bảng theo hạng cell: M đổi 33/49 node, 640/896 vCPU thành 30/46, 568/824 (RAM 4,0 TiB giữ nguyên); S đổi 24/31, 352/464, 1,9 TiB thành 25/30, 392/472, 2,2 TiB; dòng Hoà Lạc toàn quốc đổi 288/418, 5.800/7.880 thành 274/402, 5.480/7.528 (RAM ≈ 36 TiB, 40 GPU giữ nguyên); dòng phía Nam ≈ 200 node, 4.560 vCPU thành 216 node, 4.360 vCPU, 20 TiB.
2. TL-04 mục 5.2: hồ sơ M gộp sáu cụm PostgreSQL ứng dụng lên 3 máy PHY-DB-L; hồ sơ compact gộp thành hai cụm CNPG (`pg-core`, `pg-ledger`) với database riêng.
3. Thêm TL-05 vào bảng "Tài liệu liên quan" của TL-01..04.
