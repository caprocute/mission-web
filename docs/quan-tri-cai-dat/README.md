# Tài liệu quản trị và cài đặt Mission

> Nguồn: TL-04 mục 2.1 đến 2.4 (ba mô hình triển khai), mục 9.9 (cài đặt cách ly), mục 11.6 và OPS-12 (bản LTS); TL-05 chương 9 (bản cài đám mây riêng và bản cài cách ly) và Phụ lục A (values bốn hồ sơ). Mã trong repo: `deploy/charts/vt-platform` (INF-02), `deploy/charts/vt-platform/sizing` (INF-03), `deploy/argocd` (INF-04), `infra/airgap` (INF-23), `ops/runbooks/`. Task: VH-06.

Bộ tài liệu này dành cho hai nhóm người đọc. Nhóm thứ nhất là đội triển khai của Viettel, những người dựng một bản cài mới cho khách hàng. Nhóm thứ hai là người quản trị hệ thống phía khách hàng, những người vận hành bản cài sau khi bàn giao. Tài liệu không thay thế các sổ tay vận hành chi tiết trong `ops/runbooks/`: mỗi khi một bước cần thao tác sâu (khởi tạo OpenBao, khôi phục PostgreSQL, chuyển tenant), tài liệu trỏ tới đúng sổ tay đó.

Mọi địa chỉ máy chủ, tên miền, tài khoản và khoá trong bộ tài liệu đều là chỗ giữ chỗ dạng `<tên>`. Giá trị thật của từng bản cài nằm trong kho bí mật OpenBao của cell, hoặc trong biên bản bàn giao giữa Viettel và khách hàng, không bao giờ nằm trong Git.

## Nguyên tắc: một mã nguồn, một bộ chart, ba cách phân phối

Cả ba mô hình đều dựng từ cùng một nhánh mã `main`, cùng một tập ảnh container đã ký và cùng một chart cha `vt-platform`. Khác biệt giữa các mô hình chỉ nằm ở tệp values của mô hình (`values-saas.yaml`, `values-private.yaml`, `values-airgap.yaml`) và ở kênh đưa gói tới hạ tầng. Hệ quả cho người cài đặt là không có "bản riêng" nào để vá tay: mọi tuỳ biến của một khách hàng phải được biểu diễn thành values của cell, và values đó được chồng theo thứ tự cố định mà CI đã kiểm:

```text
values.yaml (chung)  →  values-<mô hình>.yaml  →  sizing/values-sizing-<hồ sơ>.yaml  →  cells/<mã cell>/values.yaml
```

## Chọn mô hình phân phối

| Tiêu chí | Mô hình 1: SaaS | Mô hình 2: đám mây riêng | Mô hình 3: cài cách ly |
|---|---|---|---|
| Khách hàng điển hình | Tỉnh, thành phố, khối Đảng ở Trung ương, cấp xã, chi bộ, đơn vị sự nghiệp, doanh nghiệp | Tỉnh có trung tâm dữ liệu riêng, bộ ngành, bệnh viện lớn | Lực lượng vũ trang, Công an, đơn vị có dữ liệu thuộc bí mật nhà nước |
| Dữ liệu nằm ở đâu | Hai trung tâm dữ liệu của Viettel | Hạ tầng do khách hàng quản lý | Mạng nội bộ của khách hàng, không có Internet |
| Số cell | Nhiều cell, theo danh bạ SVC-03 | Một cell, một hoặc vài tenant | Một cell theo đơn vị |
| Hồ sơ năng lực | `L`, `M`, `S` theo cell | `compact`, `S` hoặc `M` | `compact` hoặc `S` |
| Kênh cập nhật | Liên tục theo đợt cell (OPS-02) | Theo quý, do khách hàng duyệt | LTS 6 tháng một bản, vá an ninh 18 tháng (OPS-12) |
| Mật mã | `standard` | `standard` | `bcy` (Ban Cơ yếu Chính phủ) |
| Tài liệu cài đặt | [mo-hinh-saas.md](mo-hinh-saas.md) | [mo-hinh-dam-may-rieng.md](mo-hinh-dam-may-rieng.md) | [mo-hinh-cach-ly.md](mo-hinh-cach-ly.md) và [cach-ly.md](cach-ly.md) |

Khi một khách hàng có thể đi theo hai mô hình, quy tắc chọn như sau. Nếu không có yêu cầu pháp lý buộc dữ liệu nằm trên hạ tầng của khách hàng, chọn SaaS, vì đây là mô hình có SLO 99,9% do Viettel cam kết và có bản sao ấm ở trung tâm dữ liệu thứ hai. Nếu dữ liệu phải nằm ở hạ tầng của khách hàng nhưng mạng vẫn có đường ra ngoài theo chính sách, chọn đám mây riêng. Chỉ chọn cài cách ly khi dữ liệu thuộc bí mật nhà nước hoặc bí mật quân sự, vì mô hình này nhận cập nhật chậm hơn một chu kỳ và mọi thao tác chuyển gói đều qua phương tiện vật lý.

## Chọn hồ sơ năng lực theo quy mô

Bảng dưới lấy từ TL-05 mục 9.1, áp cho bản cài đơn cell (đám mây riêng và cách ly). Cột "Hạ tầng tối thiểu" là cấu hình tại một địa điểm; mã máy chủ chuẩn (`VM-32-128`, `PHY-DB-S`…) giải thích ở [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md).

| Quy mô khách hàng | Hồ sơ | Hạ tầng tối thiểu tại một địa điểm | Ghi chú |
|---|---|---|---|
| Đến 10.000 tài khoản | `compact` mức tối thiểu | 3 máy 32 vCPU, 192 GiB, 4 × 1,92 TB NVMe, 2 × 8 TB HDD, mọi thành phần chạy hội tụ | Control plane chạy chung trên 3 máy, không có trợ lý AI |
| 10.000 đến 50.000 | `compact` | 3 × VM-32-128, 3 × PHY-DB-S có HDD, 3 × VM-16-64 cho quản trị | Như TL-05 mục 8.1 |
| 50.000 đến 300.000 | `S` | Như hồ sơ S, cộng cụm quản trị 3 × VM-32-128 | Có thể dùng máy ảo cho tầng dữ liệu nếu bảo đảm NVMe chuyên dụng |
| 300.000 đến 800.000 | `M` | Như hồ sơ M, cộng cụm quản trị 4 × VM-32-128 | Cần 8 máy Ceph nếu dùng erasure coding |

Hồ sơ `compact` được thiết kế cho thí điểm và đơn vị nhỏ. Hồ sơ này chấp nhận ba ngoại lệ so với thiết kế chuẩn (một vùng sự cố, vùng bảo mật chạy chung node dữ liệu, gộp cụm PostgreSQL), mỗi ngoại lệ có biện pháp bù. Ngoại lệ thứ nhất làm RTO khi mất địa điểm là 48 giờ thay vì 4 giờ, và con số này phải được ghi vào hợp đồng dịch vụ. Chi tiết ở [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md), mục "Ngoại lệ của hồ sơ compact".

## Thứ tự đọc

1. [yeu-cau-ha-tang.md](yeu-cau-ha-tang.md): phần cứng, mạng, phần mềm nền cần có trước khi cài.
2. [cai-dat-chung.md](cai-dat-chung.md): các bước giống nhau ở mọi mô hình, từ cụm Kubernetes tới kiểm tra sau cài.
3. Tài liệu của mô hình đã chọn: [mo-hinh-saas.md](mo-hinh-saas.md), [mo-hinh-dam-may-rieng.md](mo-hinh-dam-may-rieng.md) hoặc [mo-hinh-cach-ly.md](mo-hinh-cach-ly.md).
4. [quan-tri-thuong-ngay.md](quan-tri-thuong-ngay.md): tạo tenant, sao lưu và khôi phục thử, xoay vòng khoá, giám sát, nâng cấp.
5. [ma-tran-trach-nhiem.md](ma-tran-trach-nhiem.md): ai giữ khoá, ai vận hành, ai trực ở từng mô hình.

Thư mục [vi-du/](vi-du/) chứa hai tệp mẫu `cell.yaml` và `cell-rieng-compact.yaml` cho bản cài đám mây riêng hồ sơ compact. Hai tệp này đã được render cùng chart `vt-platform` và kiểm schema (xem [mo-hinh-dam-may-rieng.md](mo-hinh-dam-may-rieng.md), mục "Kiểm tra trước khi đồng bộ").

## Trạng thái của bộ tài liệu

Bộ tài liệu được viết trong giai đoạn phát triển (YC-PT-19: chỉ phát triển, chưa đo tải, chưa diễn tập DR). Mọi lệnh render, kiểm schema và kiểm Markdown trong tài liệu đã chạy thật trên máy phát triển. Việc cài đầy đủ lên một cụm thật (k3d, kind hoặc cụm của khách hàng) chưa làm được trong đợt này, nên các bước tương tác với cụm được viết theo mã trong repo và sổ tay đã kiểm trên kind của từng task hạ tầng; phần nào chưa kiểm được ghi rõ "(chưa chạy thử trên cụm)".
