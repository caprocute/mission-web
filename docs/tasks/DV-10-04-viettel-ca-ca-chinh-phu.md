# DV-10-04 · SVC-10 Nhà cung cấp Viettel-CA và CA chuyên dùng Chính phủ

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-10-01; DV-10-03 (client TSA dùng chung) nên gộp trước (bổ sung) |
| Truy vết | QT-09, QT-11, QT-13, QT-15 → API `POST /api/v1/esign/requests` (provider `VIETTEL_CA`, `CA_CHINH_PHU`) → SVC-10 → bảng `esign.sign_request`, `esign.certificate_ref` → EVT-18 |
| Trạng thái | Bị chặn bởi bên ngoài: cần môi trường thử của Viettel-CA và CA chuyên dùng Chính phủ (phần mã và kiểm thử với giả lập làm được ngay) |

## 1. Mục tiêu

Bổ sung hai nhà cung cấp `viettel-ca` và `gov-ca` vào SPI `SignatureProvider` để tenant dùng chứng thư số của Viettel-CA hoặc chứng thư chuyên dùng Chính phủ ký các mốc pháp lý, kèm kiểm tra trạng thái chứng thư theo chuỗi gốc tương ứng. Khi xong, quản trị tenant chọn được nhà cung cấp theo cấu hình và luồng ký, trạng thái, EVT-18 giữ nguyên như MySign.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-10 esign-service" (L1118–1140), "Các bộ kết nối" (L1409, dòng "MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu": API ký từ xa, PKCS#11, CSC API), trục L dòng L9 (L3221).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng khác biệt ba mô hình phân phối (L106–108, dòng "Định danh, ký số").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `esign.certificate_ref` (L3737).
- `contracts/openapi/modules/dossiers.yaml` `EsignProvider`, `listMyEsignCertificates`.

## 3. Phạm vi công việc

- Viết `ViettelCaProvider` (id `viettel-ca`) theo giao thức ký từ xa mà Viettel-CA cung cấp (CSC API nếu có; nếu là API riêng thì bọc trong adapter cùng SPI), dùng chung khung thăm dò, ánh xạ trạng thái và nhúng PAdES của DV-10-02.
- Viết `GovCaProvider` (id `gov-ca`) cho chứng thư chuyên dùng Chính phủ: hỗ trợ ký qua dịch vụ ký từ xa của Ban Cơ yếu Chính phủ hoặc qua token/HSM PKCS#11 tại đơn vị tuỳ cấu hình tenant (suy luận vì TL-01 chỉ liệt kê "API ký từ xa, PKCS#11, CSC API").
- Nạp và cập nhật kho chứng thư gốc tin cậy cho từng nhà cung cấp; kiểm OCSP/CRL trước khi ký và khi lưu `certificate_ref` (`last_ocsp_status`, `last_ocsp_at`); chứng thư thu hồi trả 422 `ESIGN-422-CERTIFICATE-INVALID`.
- Cấu hình chọn nhà cung cấp theo tenant (danh sách nhà cung cấp được bật, mặc định); `listMyEsignCertificates` trả chứng thư của người gọi ở mọi nhà cung cấp được bật.
- Bọc lời gọi ra ngoài bằng Resilience4j riêng mỗi nhà cung cấp; bí mật kết nối lấy từ OpenBao.
- Viết giả lập cho hai nhà cung cấp phục vụ kiểm thử; khi có môi trường thử, chạy ký thật và ghi kết quả.

## 4. Ngoài phạm vi

- Nhà cung cấp Ban Cơ yếu cho bản cách ly qua `CryptoProvider` (DV-10-05).
- Giao diện chọn nhà cung cấp và chứng thư (WEB tương ứng).

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `.env.example`, `infra/dev/.env.example` (tên biến, không có giá trị thật)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh, gồm kiểm thử tích hợp với giả lập hai nhà cung cấp: ký thành công, từ chối, hết hạn, chứng thư bị thu hồi (OCSP giả lập trả `revoked`).
- PDF ký bằng chứng thư thử của từng nhà cung cấp được công cụ độc lập xác nhận hợp lệ theo chuỗi gốc đã nạp.
- Đổi nhà cung cấp mặc định của tenant chỉ bằng cấu hình, không đổi mã.
- Báo cáo ghi rõ đã hay chưa kiểm với môi trường thử thật của từng CA.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
