# Bộ ca mẫu vàng của Policy Pack (DL-03)

> Nguồn: TL-01 chương "Policy Pack", mục "Bộ ca mẫu vàng" (định dạng `bo_ca_mau`, yêu cầu đạt 100%); TL-02b QT-15 (BR-15-07, BR-15-08), QT-09 (BR-09-01 đến BR-09-13), QT-10 (BR-10-02 đến BR-10-05), QT-11 (bảng 13 trường hợp đặc thù); TL-03 schema `policy` (bảng `golden_test_case`); hợp đồng `contracts/openapi/modules/policies.yaml` (`createGoldenTestCase`, `approveGoldenTestCase`).

## Trạng thái hiện tại: bộ ca giả lập, chưa ký

Theo quyết định của người dùng ngày 20/10/2026 (sổ tay yêu cầu YC-PT-14), công thức tính điểm và bảng xếp loại đang dùng bản giả lập soạn theo Quy định 366-QĐ/TW, dự thảo Quy định mới và Nghị định 335/2025/NĐ-CP. Bộ ca ở đây đi cùng bản giả lập đó: mọi ca do người soạn tính tay từ quy tắc nghiệp vụ (nguồn `BA`), có căn cứ ở `legal_ref`, dữ liệu đều là dữ liệu giả lập và không lấy từ hồ sơ thật (SEC-52). Tệp bộ ca ghi `gia_lap: true` và `trang_thai_ky: NHAP`; chưa ca nào được nghiệp vụ ký duyệt, nên theo BR-15-08 chưa ca nào được tính vào tỷ lệ đạt chính thức của gói.

Khi cơ quan có thẩm quyền ban hành công thức chính thức, gói được phát hành phiên bản mới; bộ ca được rà lại theo phiên bản đó, ca nào có kỳ vọng đổi thì soạn ca mới thay vì sửa ca đã ký.

| Gói | Tệp bộ ca | Số ca | Đã ký |
|---|---|---|---|
| Luồng Đảng `khoi-dang/quy-dinh-moi` | `data/policy-pack/khoi-dang/quy-dinh-moi/tests/bo-ca-mau-gia-lap.yaml` | 63 (35 ca quý, 15 ca năm, 13 ca trường hợp đặc thù) | 0 |
| Luồng Nhà nước `khoi-chinh-quyen/nd335` | `data/policy-pack/khoi-chinh-quyen/nd335/tests/bo-ca-mau-gia-lap.yaml` | 7 | 0 |
| Hồ sơ TWĐ `twd/diem-cong-viec` (DL-16) | `data/policy-pack/twd/diem-cong-viec/tests/bo-ca-mau-twd.yaml` | 1441 (việc, mục quản lý, 120 người hai chế độ, điểm năm, chuỗi xếp loại, ca lô, G và CT của UEB) | 0 |

Bộ ca TWĐ sinh bằng `twd/sinh_ca_twd.py` từ Phụ lục 01, 01G của UEB; các ca kiểm luồng, chặn, thông báo của UEB không chạy được ở đây nên nằm ở `twd/ca-nghiem-thu.yaml`, gắn task lõi. Gói này dùng thêm các loại ca `VIEC`, `QUAN_LY`, `CA_NHAN`, `NAM_DIEM`, `XEP_LOAI_CHUOI` và `XEP_LOAI_LO` (chạy lại một nhóm ca với tham số hoặc dữ liệu ghi đè, đếm số người theo mức); chú thích đầu `chay_ca_mau.py` mô tả đầu vào của từng loại.

## Thư mục này chứa gì

| Tệp | Vai trò |
|---|---|
| `chay_ca_mau.py` | Chạy mọi bộ ca của mọi gói, in ca không đạt và ma trận phủ; tuỳ chọn `--ghi-bang` sinh lại hai tệp dưới đây. |
| `test_chay_ca_mau.py` | Kiểm thử cho tập lệnh chạy, gồm cả kiểm thử chứng minh đổi ngưỡng trong bảng quyết định hay đổi tham số là đủ để thấy ca đổi kết quả. |
| `<khối>/<tên gói>/bang-doi-chieu.csv` | Bảng đối chiếu cho người ký: mã ca, mô tả, căn cứ, kết quả mong đợi, kết quả tính được, cột ký và ý kiến để trống. |
| `<khối>/<tên gói>/nap-ca.json` | Thân yêu cầu `createGoldenTestCase` cho từng ca (đầu vào đã ghép giá trị mặc định); `policyPackId` điền khi nạp. |

Hai tệp sinh ra không sửa tay: sửa tệp bộ ca trong gói rồi chạy lại với `--ghi-bang`.

## Tập lệnh chạy kiểm tra những gì

Tập lệnh là bản hiện thực tham chiếu, độc lập với scoring-service. Công thức của BR-09-01 đến BR-09-13 và BR-10-02 đến BR-10-05 được viết lại bằng số hữu tỷ chính xác rồi làm tròn nửa lên theo `rounding` của gói, nên kết quả không phụ thuộc sai số dấu phẩy động. Tham số đọc từ mục `params` của `policy.yaml`; ca nào cần thử giá trị khác thì ghi đè trong khoá `tham_so` của ca. Bảng quyết định đọc thẳng từ các tệp DMN bằng một bộ đánh giá FEEL rút gọn, chỉ hiểu các dạng ô đang có trong gói (dấu gạch, so sánh, khoảng, danh sách chuỗi, true, false); gặp dạng ô khác thì báo lỗi thay vì đoán. Nhờ vậy, khi nghiệp vụ đổi một ngưỡng hay một tham số, chỉ cần chạy lại để biết ca nào đổi kết quả mà không phải sửa mã.

Mỗi ca đạt khi mọi khoá trong `mong_doi` khớp giá trị tính được, gồm điểm, tỷ lệ nhiệm vụ vượt mục tiêu, chỉ số từng nhiệm vụ, dòng bảng quyết định được chọn, mức xếp loại, mức cuối sau quy tắc người đứng đầu và giới hạn theo mức công chức, và với ca hạ mức là danh sách điều kiện còn thiếu dùng để ghép câu giải thích. Tập lệnh còn in ma trận phủ: mỗi dòng của mọi bảng quyết định, mỗi trường hợp đặc thù trong 13 mục phải có ít nhất một ca chạm tới, thiếu là lỗi; các tham số được ca thử ở giá trị khác mặc định cũng được liệt kê.

Tập lệnh không thông dịch biểu thức CEL trong `policy.yaml`. Việc chạy cùng bộ ca trên cel-java và cel-js để so khớp (`engine_match`) thuộc bộ chạy chung của `vt-cel` và CORE-05-06, chưa có trong repo.

## Cách chạy

```bash
python -I data/golden/chay_ca_mau.py data/policy-pack              # chạy, in ma trận phủ
python -I data/golden/chay_ca_mau.py data/policy-pack --ghi-bang   # sinh lại bảng đối chiếu và tệp nạp ca
python -I -m unittest discover -s data/golden -p 'test_*.py'
```

Cần Python 3.12 và PyYAML (cùng yêu cầu với `data/policy-pack/kiem_goi.py`).

## Hướng dẫn cho người ký

Người ký là người phụ trách nghiệp vụ do cơ quan tổ chức chỉ định; người ký khác người soạn gói (BR-15-09). Agent và lập trình viên không ký thay và không sửa kỳ vọng của ca đã ký (BR-15-08).

1. Mở `bang-doi-chieu.csv` của gói cần ký. Mỗi dòng là một ca: đọc mô tả và căn cứ, rồi đối chiếu cột "mong_doi" với văn bản quy định. Cột "ket_qua_tinh" là kết quả máy tính được theo bộ quy tắc hiện tại, chỉ để tham khảo.
2. Ca nào kỳ vọng đúng thì ghi họ tên vào cột "nguoi_ky" và ngày vào cột "ngay_ky". Ca nào sai hoặc thiếu căn cứ thì ghi lý do vào cột "y_kien" và không ký; người soạn sẽ sửa ca rồi gửi lại.
3. Sau khi máy chủ có chức năng quản lý ca (CORE-05-06), các ca được nạp bằng `nap-ca.json` ở trạng thái chờ ký, và việc ký thực hiện trên màn hình quản lý bộ ca bằng chữ ký số; bảng đối chiếu khi đó chỉ dùng để rà soát.

## Liên quan

- `data/policy-pack/kiem_goi.py`: kiểm tra tĩnh cấu trúc gói.
- `wiki/11-kiem-thu-va-chat-luong.md`, mục "Bộ ca mẫu vàng của Policy Pack".
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục dữ liệu nghiệp vụ nền.
