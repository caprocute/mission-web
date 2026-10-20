# DV-10-05 · SVC-10/ADR-06 Nhà cung cấp Ban Cơ yếu cho bản cách ly qua `CryptoProvider`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-10-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-10-01; NEN-015 (lớp `CryptoProvider` trong `vt-platform-starter`) phải gộp trước (bổ sung) |
| Truy vết | QT-09, QT-11 (bản cách ly) → API `POST /api/v1/esign/requests` (provider `BAN_CO_YEU`) → SVC-10 → bảng `esign.sign_request`, `esign.sign_item` → EVT-18 → ADR-06 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Cho bản cài cách ly (LLVT, Công an) ký số và mã hoá bằng thuật toán, thiết bị của Ban Cơ yếu Chính phủ thông qua lớp trừu tượng `CryptoProvider` với giá trị cấu hình `crypto.provider = bcy`, không đổi mã nghiệp vụ. Khi xong, cùng một bản build chạy được ở chế độ `standard` và `bcy`, chế độ `bcy` không gọi bất kỳ thuật toán tiêu chuẩn nào ngoài danh sách được phép.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Ranh giới pháp lý với bí mật nhà nước" (L2341), dòng "Luật Bảo vệ bí mật nhà nước 2018" (L220), cấu trúc gói `crypto` (L400), ADR-06 (L3442), "Các bộ kết nối" (L1409).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng ba mô hình phân phối (L106, khoá `crypto.provider`) và chương bản cách ly (từ L129).
- `00-co-so-thiet-ke-chung.md` ADR-06; `contracts/openapi/modules/dossiers.yaml` `EsignProvider`.
- Thẻ NEN-015 để biết giao diện `CryptoProvider` đã có.

## 3. Phạm vi công việc

- Viết `BcyCryptoProvider` hiện thực giao diện `CryptoProvider` của NEN-015 bằng thư viện Ban Cơ yếu (ký, kiểm chữ ký, mã hoá phong bì nếu thư viện hỗ trợ) và `BcySignatureProvider` (id `bcy`) trong SPI chữ ký dùng `CryptoProvider` đó.
- Đặt điều kiện nạp theo cấu hình `crypto.provider`: `standard` nạp nhà cung cấp tiêu chuẩn, `bcy` chỉ nạp nhà cung cấp Ban Cơ yếu và từ chối khởi động nếu thiếu thư viện hoặc thiết bị.
- Định dạng chữ ký và dấu thời gian theo quy định của Ban Cơ yếu; nếu không phải PAdES thì lưu `signature_format` phù hợp trong bộ giá trị TL-03 hoặc ghi điểm lệch cần mở rộng CHECK (không tự thêm giá trị khi chưa được duyệt).
- Viết bộ kiểm thử hợp đồng cho `CryptoProvider` chạy được với cả hai hiện thực (một bộ ca, hai lần chạy), để mọi hiện thực mới phải qua cùng bộ ca.
- Ghi hướng dẫn cấu hình chế độ `bcy` cho bản cách ly vào wiki (trang hạ tầng hoặc trang 14), không ghi thông tin thiết bị thật.

## 4. Ngoài phạm vi

- Đóng gói Helm ngoại tuyến và Harbor nội bộ cho bản cách ly (INF-23).
- Xử lý bí mật nhà nước trên cell SaaS (không được phép theo TL-01).
- Nối và kiểm thử với thư viện, tài liệu giao diện mật mã thật của Ban Cơ yếu Chính phủ trên máy thử cách ly khi có; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `backend/libs/vt-platform-starter/**/crypto/**` (chỉ thêm hiện thực `bcy`, không đổi giao diện)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh ở chế độ `standard`; bộ kiểm thử hợp đồng `CryptoProvider` chạy xanh với hiện thực tiêu chuẩn.
- Bộ kiểm thử hợp đồng `CryptoProvider` chạy xanh ở chế độ `bcy` với thư viện Ban Cơ yếu giả lập (cùng giao diện bọc); đường dẫn thư viện, cấu hình khe khoá và tham số nạp nằm trong cấu hình triển khai, nên thay bằng thư viện thật chỉ đổi cấu hình, không đổi mã.
- Khởi động ở chế độ `bcy` mà thiếu thư viện thì dịch vụ không sẵn sàng và nhật ký nêu lý do bằng tiếng Việt.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
