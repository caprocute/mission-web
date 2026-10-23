> Nguồn: TL-04 mục 9.7 "Tuân thủ TCVN 11930:2017 cấp độ 4", bảng SEC mục 9.2, mục 9.8; baseline NFR-08; task VH-07.

# Hồ sơ đề xuất cấp độ 4 theo TCVN 11930:2017

Thư mục này là bộ hồ sơ đề xuất cấp độ 4 cho nền tảng Mission (NFR-08). Hồ sơ dùng cho hai mục đích. Mục đích thứ nhất là thuyết minh với cơ quan thẩm định về lý do hệ thống thuộc cấp độ 4 và các biện pháp bảo đảm an toàn đã thiết kế. Mục đích thứ hai là giúp đội phát triển và vận hành biết chính xác yêu cầu nào đã có bằng chứng trong repo, yêu cầu nào mới chỉ có thiết kế và yêu cầu nào còn thiếu.

Mỗi yêu cầu trong hồ sơ đều trỏ tới bằng chứng truy được, gồm tệp cấu hình trong Git, kiểm thử tự động, runbook hoặc biên bản. Yêu cầu chưa có bằng chứng được ghi rõ là còn thiếu, kèm task chịu trách nhiệm và mốc dự kiến. Hồ sơ không chứa địa chỉ máy chủ, tên tài khoản, khoá hay mật khẩu. Bằng chứng có thông tin nhạy cảm, như báo cáo phiên PAM hay danh sách khoá Transit thật, được lưu ngoài repo, và hồ sơ chỉ ghi tham chiếu tới nơi lưu.

## Thành phần

| Tệp | Nội dung |
|---|---|
| [thuyet-minh.md](thuyet-minh.md) | Mô tả hệ thống, phạm vi, căn cứ phân loại cấp độ 4, các vùng mạng ở mức đủ cho hồ sơ |
| [anh-xa-yeu-cau.md](anh-xa-yeu-cau.md) | Chín nhóm yêu cầu TCVN 11930 ánh xạ tới tiêu chí và mã SEC, DR, OPS; bảng chi tiết từng mã kèm bằng chứng và trạng thái |
| [bang-chung/](bang-chung/) | Tám trang bằng chứng cho tám yêu cầu bổ sung đặc trưng của cấp độ 4 |
| [du-lieu-ca-nhan.md](du-lieu-ca-nhan.md) | Phần đóng góp kỹ thuật cho hồ sơ đánh giá tác động xử lý dữ liệu cá nhân (SEC-52) |
| [thieu-hut.md](thieu-hut.md) | Danh sách yêu cầu chưa có bằng chứng: nguyên nhân, task chịu trách nhiệm, mốc |
| [thu-bang-chung.sh](thu-bang-chung.sh) | Script thu bằng chứng tự động từ cụm thử (kind hoặc staging) vào thư mục có ngày |
| [kiem-lien-ket.py](kiem-lien-ket.py) | Kiểm mọi đường dẫn bằng chứng ghi trong hồ sơ đều tồn tại trong repo |

## Quy ước trạng thái

Hồ sơ dùng năm trạng thái thống nhất. **Đáp ứng** nghĩa là biện pháp đã có trong repo và có kiểm thử tự động hoặc kiểm tra tĩnh chứng minh. **Đáp ứng (giả lập)** nghĩa là biện pháp đã làm và kiểm trên bản giả lập (SoftHSM, cụm kind), còn chờ nối hệ thật. **Một phần** nghĩa là đã có một phần biện pháp, phần còn lại được nêu trong cột ghi chú và trong danh sách thiếu hụt. **Thiếu** nghĩa là chưa có biện pháp hoặc chưa có bằng chứng. **Hoãn** nghĩa là việc đo kiểm được dời sang giai đoạn sau theo quyết định của người dùng (YC-PT-19: giai đoạn hiện tại chỉ phát triển, chưa chạy thử tải).

## Cách cập nhật

Khi một task hạ tầng hoặc kiểm thử hoàn thành (ví dụ INF-19, INF-27, QA-14, QA-15), người làm task cập nhật dòng tương ứng trong `anh-xa-yeu-cau.md`, trang bằng chứng liên quan và `thieu-hut.md`, rồi chạy `python3 docs/attt/cap-do-4/kiem-lien-ket.py` để chắc mọi đường dẫn còn đúng. Ngày đánh giá ở đầu mỗi tệp được cập nhật theo lần sửa gần nhất.

## Liên quan

- `docs/attt/asvs-4.0.3-tu-danh-gia.md` (QA-13, tự đánh giá ASVS 4.0.3)
- `wiki/08-bao-mat-frontend.md`, `wiki/20-bao-mat-phia-may-chu.md`
- `docs/tasks/VH-07-ho-so-cap-do-4.md`
