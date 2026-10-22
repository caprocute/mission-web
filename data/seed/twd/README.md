# Dữ liệu khởi tạo của hồ sơ TWĐ (DL-16, giả lập)

Thư mục này chứa các thành phần dữ liệu của hồ sơ tập khách hàng `twd/ban-to-chuc-ueb` (BS-03 "Hồ sơ TWĐ", ADR-33) mà bản kê `data/policy-pack/twd/ban-to-chuc-ueb/ho-so.yaml` trỏ tới. Mọi tệp là **giả lập** theo YC-PT-14: giá trị lấy từ bộ tài liệu bàn giao UEB-BTCTW-QTCV-02 v1.2 của đơn vị tư vấn, dựa trên dự thảo Quy định kiểm điểm, đánh giá, xếp loại chưa ban hành, và mọi dòng mang nhãn "Thí điểm – dùng để đối chiếu". Không giá trị nào ở đây là mặc định của lõi hay của hồ sơ khác (YC-TK-11); lõi đọc chúng qua cấu hình của hồ sơ.

| Tệp | Nội dung | Bảng, API đích |
|---|---|---|
| `danh-muc/work_catalog.csv`, `danh-muc/catalog_item.csv` | Danh mục điểm chuẩn `TWD-DM-25` ở phạm vi `KHOI`, 112 dòng của bản dự thảo số 25 mà cây mô phỏng dùng tới (gồm các dòng tách 22a, 34b…), khung chỉnh điểm ±20% (DC-02) | `task.work_catalog`, `task.catalog_item` (CORE-02-27) |
| `danh-muc/danh-muc-dung-chung.yaml` | Trục TR1–TR6, chỉ tiêu CT1–CT5, điều kiện công nhận kiểu mốc có bậc, tiêu chí chung 12/10/11, loại căn cứ sự việc, loại sự việc dẫn tới loại trừ, tám nguyên nhân điểm nghẽn kèm nhóm | danh mục của CORE-01-14, CORE-04-13, CORE-02-20 |
| `diem-nghen.yaml` | Bộ quy tắc cảnh báo `twd/diem-nghen`: chín quy tắc của UEB 11.6, điều kiện viết bằng CEL, mức, người nhận, mẫu nội dung, tham số và cờ bật tắt | `task.task_blocker` với `origin = TU_DONG` |
| `twd-tham-quyen.yaml` | Bộ tham số luồng `twd-tham-quyen`: bảng thẩm quyền duyệt thay đổi (Phụ lục B.5), hạn từng bước đợt quý 2/2/1/1, thẩm quyền rà soát, thẩm định, quyết định (15.6) | mẫu luồng `duyet-de-nghi`, SCR-1504 (BS-01) |
| `sinh_danh_muc_twd.py` | Sinh lại hai tệp CSV danh mục từ Phụ lục 01 của UEB, tự kiểm điểm dòng tách khớp dòng gốc | – |
| `kiem_seed_twd.py`, `test_kiem_seed_twd.py` | Kiểm tra tĩnh mọi tệp trên và bản kê hồ sơ, kể cả phủ ca UEB giữa bộ ca vàng và danh mục ca nghiệm thu | – |

Danh mục chỉ gồm các dòng cây mô phỏng dùng tới vì bộ tài liệu bàn giao không kèm toàn văn bản dự thảo số 25; khi có bản đầy đủ, nạp thêm các dòng còn lại theo cùng định dạng. Nhóm mặc định của một dòng lấy theo nhóm được dùng nhiều hơn trên cây, vì nhóm thực tế là của từng việc.

## Cách chạy

```bash
python -I data/seed/twd/kiem_seed_twd.py .                               # kiểm tĩnh, in KẾT QUẢ: ĐẠT
python -I -m unittest discover -s data/seed/twd -p 'test_*.py'            # kiểm thử của bộ kiểm tra
python -I data/seed/twd/sinh_danh_muc_twd.py <Phụ lục 01 .xlsx>           # sinh lại danh mục (cần openpyxl)
```

Tệp nguồn của UEB và mật khẩu giải nén không nằm trong repo và không được commit. Bộ kiểm tra không thông dịch CEL; việc đánh giá điều kiện bằng vt-cel thuộc job tự sinh điểm nghẽn của lõi.

## Liên quan

- `data/policy-pack/twd/diem-cong-viec/`: gói điểm công việc và bộ ca vàng của hồ sơ.
- `data/golden/twd/ca-nghiem-thu.yaml`: danh mục ca nghiệm thu không tính điểm.
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục "Ghi chú và quyết định từ hồ sơ TWĐ (DL-16)".
