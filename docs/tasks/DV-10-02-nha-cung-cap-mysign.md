# DV-10-02 · SVC-10/EVT-18 Nhà cung cấp MySign (CSC API) ký từ xa bất đồng bộ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-10-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-10-01; DV-10-03 (dấu thời gian TSA cho PAdES-LTA) nên gộp trước khi bật chế độ LTA (bổ sung) |
| Truy vết | QT-04, QT-09, QT-11 → API `POST /api/v1/esign/requests` (provider `MYSIGN`) → SVC-10 → bảng `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref` → EVT-18 |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Hiện thực `MySignProvider` theo chuẩn Cloud Signature Consortium (CSC API) để người ký xác nhận trên ứng dụng MySign, esign-service thăm dò bất đồng bộ, nhúng chữ ký vào PDF/A thành PAdES mức B-LTA và phát EVT-18. Khi xong, các mốc pháp lý của cá nhân (tự đánh giá, thẩm định, quyết định, biên bản) ký được bằng MySign mà không có lời gọi đồng bộ nào trên đường găng của người dùng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-10 esign-service" (L1118–1140), "Các bộ kết nối" (L1409, dòng MySign/CSC API), "Ký số tại mốc pháp lý" (L2626), lớp 2 "Ký số tại mốc pháp lý" trong chương G3 (L2187).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `esign.sign_request`, `esign.sign_item`, `esign.certificate_ref` (L3704–3749).
- `TL-04-trien-khai-van-hanh.md` bảng SEC-36 (lưu lượng ra: đích MySign qua egress gateway).
- `contracts/openapi/modules/dossiers.yaml` `createEsignRequest`, `getEsignRequest` (thời hạn 120 giây, MSG-04-09).
- Tài liệu CSC API v2 công khai (tra trên mạng khi cần); bản trích `extract/tl01-thanh-phan.md` mục C9.

## 3. Phạm vi công việc

- Viết `MySignProvider` (id `mysign`) trong `infrastructure/provider/mysign`: xác thực client bằng OAuth2 client credentials, `credentials/list` và `credentials/info` để lấy chứng thư người ký, `signatures/signHash` ở chế độ bất đồng bộ (người ký xác nhận trên điện thoại), thăm dò trạng thái tới `expires_at`.
- Tính băm vùng ký PDF (ByteRange) phía esign, gửi băm (không gửi tài liệu) tới MySign; nhận chữ ký CMS, nhúng vào PDF thành PAdES-B-T với token TSA, thêm dữ liệu kiểm tra chứng thư (OCSP/CRL) để đạt B-LTA khi DV-10-03 sẵn sàng; dùng thư viện Apache 2.0 hoặc LGPL/MPL (không iText 7 vì AGPL).
- Lưu `certificate_ref` (chuỗi chứng thư, `last_ocsp_status`, `last_ocsp_at`) và kiểm chứng thư còn hiệu lực trước khi tạo yêu cầu (422 `ESIGN-422-CERTIFICATE-INVALID`).
- Bọc mọi lời gọi MySign bằng Resilience4j chuẩn (thời hạn 3 giây, ngắt mạch khi lỗi > 50% trong 20 lời gọi, bulkhead riêng nhà cung cấp); khi ngắt mạch trả 422 `ESIGN-422-PROVIDER-UNAVAILABLE` để giao diện cho thử lại sau.
- Ánh xạ trạng thái CSC sang `CHO_XAC_NHAN`, `DA_KY`, `TU_CHOI`, `HET_HAN`, `LOI`; khi `DA_KY` lưu `signature_value`, `signed_object_key` (tệp đã ký trên kho đối tượng) và phát EVT-18 kèm băm PDF đã ký để sổ cái ghi.
- Viết máy chủ CSC giả lập (WireMock hoặc Testcontainers) tái hiện các ca: người ký xác nhận sau 1–3 giây, từ chối, hết hạn, lỗi 5xx lặp; bí mật client lấy từ OpenBao qua cấu hình, không ghi vào repo.

## 4. Ngoài phạm vi

- Ký lô cho quyết định xếp loại (DV-10-06), ký bằng HSM của tổ chức (DV-10-03).
- Ứng dụng di động gọi MySign trực tiếp (SVC-18, task MOB).
- Nối và kiểm thử với MySign thật khi có tài khoản thử; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `backend/services/esign/**`
- `.env.example` hoặc `infra/dev/.env.example` (chỉ thêm tên biến cấu hình MySign, không có giá trị thật)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/esign -am verify` xanh, gồm kiểm thử tích hợp với máy chủ CSC giả lập cho đủ năm trạng thái.
- PDF đã ký trong kiểm thử được công cụ độc lập (ví dụ `pdfsig` của Poppler hoặc DSS demo) xác nhận chữ ký hợp lệ, có dấu thời gian; tài liệu không bị sửa ngoài vùng ký.
- Không có lời gọi đồng bộ tới MySign trong luồng xử lý `createEsignRequest` (kiểm bằng kiểm thử: API trả 202 trong ≤ 200 ms khi giả lập trễ 3 giây).
- Địa chỉ CSC, client và chứng thư MySign nằm trong cấu hình nhà cung cấp và giá trị triển khai (bí mật qua OpenBao), không nằm trong mã; đổi từ máy chủ CSC giả lập sang MySign thật chỉ đổi cấu hình kết nối, không đổi mã.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
