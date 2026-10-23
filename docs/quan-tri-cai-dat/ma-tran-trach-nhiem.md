# Ma trận trách nhiệm giữa Viettel và khách hàng

> Nguồn: TL-04 mục 2.2 đến 2.4, 8.7 (miền bảo mật HSM, 3 trong 5 thẻ), 9.9 (bảng khác biệt SaaS và cách ly), chương 11 (OPS-12); TL-05 mục 9.1; `ops/runbooks/openbao-van-hanh.md` (khoá phục hồi 3 trong 5), `openbao-pki.md` (nghi thức CA gốc). Task: VH-06. Các ô không có nguồn trực tiếp được đánh dấu "(suy luận)" và cần được chốt trong hợp đồng của từng khách hàng.

Ma trận này trả lời ba câu hỏi cho từng mô hình: ai giữ khoá, ai vận hành, ai trực khi có sự cố. Ký hiệu trong bảng: **V** là Viettel, **K** là khách hàng, **V+K** là hai bên cùng làm (thường là nghi thức bốn mắt hoặc một bên làm, bên kia duyệt).

## Giữ khoá

| Khoá, bí mật | SaaS | Đám mây riêng | Cách ly |
|---|---|---|---|
| HSM và khoá mở OpenBao | V (HSM của trung tâm dữ liệu) | K giữ thiết bị, V cấu hình lúc cài (suy luận) | K |
| Thẻ sao lưu miền bảo mật HSM (3 trong 5 thẻ) | V | Giao K, V giữ không quá 2 thẻ để không tự mở được (suy luận) | K giữ đủ 5 thẻ |
| Khoá phục hồi OpenBao (5 phần, ngưỡng 3) | V | V+K: chia cho người giữ của hai bên theo biên bản, không bên nào đủ ngưỡng một mình (suy luận) | K |
| CA gốc ngoại tuyến của bản cài | V | V+K: nghi thức có hai người quản trị khoá và một người của khách hàng | K, V chứng kiến khi được mời |
| KEK theo tenant, DEK | Trong OpenBao của cell, không ai xuất được | Như SaaS | Như SaaS, thuật toán Ban Cơ yếu |
| Khoá ký ảnh và gói phát hành | V (OpenBao Transit, HSM phát hành) | V | V ký; K chỉ giữ khoá công khai `vt-release.pub` |
| Khoá GPG ký commit kho GitOps | V | V cho nhánh phát hành, K cho commit gộp vào `main` của kho nhân bản | Không dùng (cài từ gói) |
| PIN HSM, mật khẩu robot Harbor | V | K (suy luận) | K |

## Vận hành

| Việc | SaaS | Đám mây riêng | Cách ly |
|---|---|---|---|
| Cung cấp máy chủ, mạng, phòng máy | V | K | K |
| Dựng cụm Kubernetes và phần mềm nền | V | V hoặc K tuỳ hợp đồng (TL-04 mục 2.3) | V tại chỗ cùng K (suy luận) |
| Cài nền tảng lần đầu | V | V | V+K |
| Duyệt và áp bản cập nhật | V, theo đợt cell (OPS-02) | K duyệt, Argo CD tại chỗ đồng bộ | K, theo bản LTS (OPS-12) |
| Đóng gói và ký bản phát hành, bản vá | V | V | V |
| Chuyển gói qua trạm kiểm định một chiều | Không áp dụng | Không áp dụng | K |
| Tạo, kích hoạt, tạm ngừng tenant | V (quản trị nền tảng) | K (quản trị nền tảng của khách hàng) (suy luận) | K |
| Theo dõi sao lưu, xử lý cảnh báo sao lưu | V | K, V hỗ trợ cấp 2 (suy luận) | K |
| Diễn tập khôi phục hằng quý | V | V+K | K, V hỗ trợ |
| Kho sao lưu ngoài địa điểm | V (trung tâm dữ liệu phía Nam) | K cung cấp địa điểm thứ hai | K |
| Nạp cơ sở dữ liệu quét hằng tuần | Tự động | Tự động qua egress gateway, hoặc K nạp tay (suy luận) | K |

## Trực và xử lý sự cố

| Việc | SaaS | Đám mây riêng | Cách ly |
|---|---|---|---|
| Giám sát an ninh (SOC) | SOC Viettel | Theo hợp đồng; mặc định SOC của K, nhận cảnh báo SIEM từ cell (suy luận) | SOC của K |
| Trực cảnh báo vận hành cấp 1 | V | K | K |
| Hỗ trợ cấp 2, cấp 3 | V | V từ xa | V từ xa qua kênh được phép hoặc tại chỗ |
| Cam kết SLO | 99,9% mỗi cell mỗi tháng (NFR-04) | Theo hợp đồng; hồ sơ compact có RTO 48 giờ khi mất địa điểm | Theo hợp đồng |
| Quyết định chuyển vùng hoặc khôi phục toàn cell | V | K quyết, V thực hiện (suy luận) | K |

## Cách dùng ma trận

Ma trận là khung mặc định. Mỗi bản cài đám mây riêng và cách ly có một bản ma trận đã điền tên người, vai trò và số liên lạc thật, ký cùng biên bản bàn giao; bản đó không đưa vào Git vì chứa thông tin cá nhân. Khi hợp đồng của một khách hàng lệch khỏi ô mặc định, ghi lệch vào bản ma trận của khách hàng đó, không sửa trang này. Khi một ô "(suy luận)" được chốt chung cho mọi khách hàng, sửa trang này và bỏ dấu suy luận.

## Liên quan

- [README.md](README.md), [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md)
- `ops/runbooks/openbao-van-hanh.md`, `openbao-hsm.md`, `openbao-pki.md`
