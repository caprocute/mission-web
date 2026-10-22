# Bộ sinh dữ liệu thử tải (DL-08)

Bộ công cụ này sinh dữ liệu giả lập có hình dạng giống một cell production (mặc định cell L của TL-04 mục G: 1,2 triệu tài khoản, 12.000 đơn vị, 36 triệu nhiệm vụ, 10 triệu phiên bản minh chứng) để chạy kiểm thử hiệu năng PERF-01..PERF-10 (QA-08). Dữ liệu sinh ra hoàn toàn tất định theo hạt giống: cùng hồ sơ, cùng hệ số và cùng hạt giống luôn cho ra các tệp giống hệt từng byte, nên hai lần thử tải có thể so sánh với nhau.

## Cách dùng

```bash
cd infra/perf/datagen
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
scripts/chuan-bi-csdl.sh            # dựng PostgreSQL 17 cục bộ (cổng 55432) và áp migration thật
.venv/bin/python -m datagen sinh --ho-so cell-l --he-so 0.001 --ra out/l0001
.venv/bin/python -m datagen kiem --ho-so cell-l --he-so 0.001 --vao out/l0001   # phân phối, quét dữ liệu cá nhân
.venv/bin/python -m datagen nap --vao out/l0001 [--thay-the]                    # nạp và kiểm RLS
.venv/bin/pytest                    # kiểm thử; ca đánh dấu "csdl" tự bỏ qua khi chưa dựng CSDL
scripts/chuan-bi-csdl.sh --xoa      # dọn container
```

Hồ sơ nằm ở `data/seed/perf/`: `cell-l`, `cell-m`, `cell-s` theo TL-04 và `cell-compact` cho CI. Hệ số `--he-so` nhân mọi đại lượng đếm, giữ nguyên tỷ lệ. `chuan-bi-csdl.sh` sinh mật khẩu ngẫu nhiên vào `.datagen-pg.env`; tệp này bị gitignore và không được commit.

## Dữ liệu sinh ra

Mỗi tenant có một thư mục `tenant-<stt>-<uuid>`, trong đó mỗi bảng là một thư mục chứa tệp COPY dạng văn bản, chia theo phân vùng kỳ (`p<bucket>.tsv`) cho các bảng `task.*` và `product.*`. Bộ sinh phủ 16 bảng theo thứ tự nạp: khoá DEK, kỳ, khối và chức danh (lấy từ danh mục DL-07), cây tổ chức 5 cấp có phiên bản thời gian, người với trường C2 ở dạng bản mã giả cùng chỉ mục mù, phân công (mỗi thời điểm đúng một phân công chính, 2% điều động giữa năm), nhiệm vụ theo quý với phân phối trạng thái của hồ sơ, người được giao, tiến độ, và minh chứng với phiên bản, đối tượng lưu trữ và kích thước tệp theo phân phối log-chuẩn. Họ tên dùng họ, đệm, tên tiếng Việt giả; số định danh dùng mã tỉnh `999` không tồn tại, nên bộ quét theo `mau-kiem.yaml` không bắt được số thật nào.

## Giả định và giới hạn

- Bảng hồ sơ đánh giá `appraisal.appraisal_profile` chưa có migration (chờ CORE-04-01), nên bộ sinh chưa sinh phần này; khi migration có, chỉ cần thêm cột vào `datagen/bang.py` và hàm sinh tương ứng.
- Dữ liệu tiến trình Flowable (`ACT_*`) để riêng cho DL-08b, vì lược đồ của Flowable do engine tự tạo.
- Việc nạp dùng tài khoản quản trị của CSDL thử, sau đó kiểm RLS bằng chính vai trò `svc_<schema>`: PostgreSQL không cho COPY FROM vào bảng có RLS với vai trò không có BYPASSRLS, và `svc_` không có quyền TEMP để dùng bảng đệm. Trên CELL-PERF cần một vai trò nạp riêng có BYPASSRLS; vai trò `mig_` mà TL-04 gợi ý không dùng được cho việc này.
- Phiên bản máy trạng thái ghi `1.0.0` (suy luận, chưa có nguồn chốt).

## Thời gian đo được

Đo ở hệ số 0,01 của cell L (12.000 tài khoản, 360.000 nhiệm vụ, khoảng 1,95 triệu dòng, 678 MB tệp TSV) trên container 4 nhân, có Maven chạy song song nên đây là cận trên: sinh mất 62 giây, nạp kèm tạo phân vùng và kiểm RLS mất 486 giây. Ngoại suy tuyến tính cho quy mô đầy đủ (hệ số 1, khoảng 195 triệu dòng, khoảng 68 GB tệp) là khoảng 1 giờ 45 phút để sinh và khoảng 13 giờ rưỡi để nạp bằng một kết nối. Nạp là nút thắt; trên CELL-PERF nên nạp song song theo tenant và theo phân vùng (các tệp `p<bucket>.tsv` độc lập nhau), tạm tắt chỉ mục phụ rồi tạo lại sau khi nạp, khi đó thời gian dự kiến giảm gần tỷ lệ với số luồng nạp (suy luận, chưa đo).
