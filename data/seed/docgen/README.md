# Mẫu biểu sinh hồ sơ số (DL-05)

> Nguồn: TL-01 mục "SVC-11 document-service" và chương Policy Pack mục "Cấu trúc gói"; TL-02b QT-09 (ngoại lệ "không tự đánh giá", SCR-0903), QT-10 (BR-10-07), QT-11 (BR-11-02 đến BR-11-05, bảng 13 trường hợp đặc thù, SCR-1102); TL-03 schema `appraisal`, `dossier`, `docgen`; hợp đồng `contracts/openapi/modules/dossiers.yaml`.

## Thư mục này chứa gì

Thư mục này giữ phần dùng chung cho bộ mẫu biểu mà document-service (SVC-11) trộn dữ liệu để sinh Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3 và bảng chấm điểm ở định dạng PDF/A-2b (tiêu chí H5). Bản thân từng mẫu nằm trong Policy Pack, ở `data/policy-pack/<khối>/<tên>/templates/<mẫu>/`, vì mẫu có phiên bản theo gói: khi văn bản hướng dẫn đổi mẫu, kỳ cũ vẫn sinh lại đúng mẫu cũ.

| Tệp | Vai trò |
|---|---|
| `kiem_mau.py` | Kiểm tra tĩnh mọi tệp kê khai và tệp DOCX của bộ mẫu. |
| `test_kiem_mau.py` | Kiểm thử cho tập lệnh kiểm tra. |
| `nhan-dung-chung.json` | Bảng nhãn in ra biểu mẫu: tên bốn mức xếp loại, nhãn tiêu chí chung theo từng mẫu (BR-11-04), câu ghi chú cố định. |

## Trạng thái hiện tại: khung chờ bản mẫu chính thức

Dự án chưa nhận được bản mẫu chính thức (DOCX hoặc PDF) của các mẫu trên cùng số hiệu văn bản ban hành. Theo phạm vi của DL-05, khi chưa có bản chính thức thì chỉ dựng khung kê khai trường từ đặc tả màn hình SCR-0903, SCR-1102 và từ điển dữ liệu TL-03, không tự sáng tác bố cục văn bản pháp lý. Vì vậy mỗi mẫu hiện chỉ có tệp `manifest.json` ở trạng thái `KHUNG_CHO_BAN_MAU`, phiên bản `0.1.0-khung`, chưa có tệp `.docx`. Tập lệnh kiểm tra coi việc thiếu DOCX ở trạng thái này là cảnh báo, không phải lỗi.

Bảng đối chiếu với bản chính thức (sẽ điền khi người dùng cung cấp bản mẫu):

| Mẫu | Mã | Số hiệu văn bản | Ngày ban hành | Trạng thái |
|---|---|---|---|---|
| Mẫu 1A | `MAU_1A` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Mẫu 1B | `MAU_1B` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Mẫu 1C | `MAU_1C` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Mẫu 1D | `MAU_1D` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Mẫu 2 | `MAU_2` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Mẫu 3 | `MAU_3` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |
| Bảng chấm điểm | `BANG_CHAM_DIEM` | Chờ cung cấp | Chờ cung cấp | Khung kê khai |

## Cấu trúc tệp kê khai `manifest.json`

Mỗi tệp kê khai mô tả một mẫu: mã biểu mẫu (`form_code`, đúng danh mục `dossier.form_instance.form_code`), trạng thái (`KHUNG_CHO_BAN_MAU`, `CHO_DUYET`, `HIEU_LUC`), phiên bản mẫu (`template_version`, in thành tem chân trang và lưu vào `dossier.form_instance.template_version`), tên tệp DOCX (`tep_mau`), thông tin văn bản gốc (`ban_chinh_thuc`), luồng áp dụng, đối tượng (cá nhân, tập thể hay danh sách), bảng nhãn tiêu chí chung được dùng và danh sách căn cứ truy vết.

Phần chính là danh sách `truong`. Mỗi trường là một vùng trộn trong DOCX, có `tag` dạng `snake_case` trùng với tag của content control trong tệp mẫu, nhãn tiếng Việt, kiểu (`van_ban`, `so`, `nhan_muc`, `bang`…), nguồn dữ liệu, mức dữ liệu, cờ khoá số liệu và vùng trên trang (`dau_trang`, `than`, `ky`, `chan_trang`). Trường kiểu `bang` khai báo các cột trong `cot`; trong DOCX, các cột này là content control của dòng mẫu được lặp. Nguồn dữ liệu viết theo dạng `schema.bảng.cột` của TL-03, hoặc `suy_ra:<tên>` cho giá trị tính khi trộn (ví dụ số thứ tự dòng, cờ "không tự đánh giá" suy ra từ việc không có mức tự đề xuất). Khi một nguồn cần chọn đúng dòng (ví dụ bốn dòng `appraisal.rating_proposal` phân biệt bằng `level_kind`), điều kiện chọn ghi ở `ghi_chu`.

Cần lưu ý rằng document-service không đọc CSDL của module khác: tác vụ sinh nhận dữ liệu đã chuẩn hoá trong thông điệp (TL-03, schema `docgen`). Cột nguồn trong kê khai là để truy vết và để MOD-06 biết phải đưa trường nào vào thông điệp, không phải câu truy vấn.

## Các quy tắc mà tập lệnh kiểm tra

Tập lệnh `kiem_mau.py` bảo đảm các quy tắc nghiệp vụ được thể hiện đúng trong mẫu. Vùng số liệu điểm và mức xếp loại phải khoá, vì biểu mẫu chỉ được sinh từ dữ liệu đã quyết định và không ai được nhập tay điểm, mức (BR-11-02). Chân trang phải có tem phiên bản bộ quy tắc và phiên bản mẫu (BR-11-03). Nhãn tiêu chí chung phải tra từ bảng nhãn của đúng mẫu, vì Mẫu 1 dùng "Đảm bảo, Không đảm bảo" còn Mẫu 2 dùng "Không vi phạm, Vi phạm" trên cùng một dữ liệu gốc (BR-11-04). Không trường nào được lấy dữ liệu mức C3, vì dữ liệu C3 chỉ xử lý theo luồng mã hoá đầu cuối (ADR-06). Mọi cột nguồn phải có trong từ điển dữ liệu TL-03; khi TL-03 bổ sung cột, cập nhật danh sách `NGUON_HOP_LE` trong tập lệnh.

Khi đã có tệp DOCX, tập lệnh còn mở tệp như một gói OOXML, đọc tag của mọi content control trong thân văn bản, đầu trang và chân trang, rồi so với tập tag trong kê khai: thiếu hay thừa một vùng trộn đều là lỗi.

## Cách chạy

```bash
python -I data/seed/docgen/kiem_mau.py data/policy-pack                 # thiếu DOCX ở khung chỉ cảnh báo
python -I data/seed/docgen/kiem_mau.py data/policy-pack --nghiem-ngat   # thiếu DOCX là lỗi (dùng khi đã có bản chính thức)
python -I -m unittest discover -s data/seed/docgen -p 'test_*.py'
```

## Khi nhận được bản mẫu chính thức

1. Dựng tệp DOCX bám đúng bố cục bản chính thức, đặt cạnh `manifest.json` với tên ghi ở `tep_mau`.
2. Với mỗi ô dữ liệu, chèn content control và đặt tag trùng `tag` trong kê khai; thêm hoặc bỏ trường trong kê khai cho khớp bản chính thức.
3. Điền `ban_chinh_thuc` (số hiệu, ngày ban hành, cơ quan ban hành), nâng `template_version` lên `1.0.0`, chuyển `trang_thai` sang `CHO_DUYET`, ghi vào `CHANGELOG.md` của gói, rồi chạy kiểm tra ở chế độ nghiêm ngặt.
4. Xác nhận phông chữ được phép nhúng vào PDF/A trước khi phát hành.
