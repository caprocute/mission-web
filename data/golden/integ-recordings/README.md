# Bản ghi tương tác đã che với hệ ngoài (DV-13-11)

Thư mục này chứa bản ghi yêu cầu và phản hồi giữa integration-gateway (SVC-13) với từng hệ ngoài, đã che dữ liệu cá nhân, để xác minh hợp đồng Pact theo chiều ngược lại khi đối tác chưa có môi trường thử. Mỗi thư mục con mang tên hệ ngoài trùng trường `provider` của tệp hợp đồng ở `backend/services/integration-gateway/src/test/resources/pacts/`. Kiểm thử `XacMinhPhiaDoiTacTest` dựng một máy phát lại từ các bản ghi này rồi phát lại mọi tương tác của hợp đồng tới đó; nếu hợp đồng đòi một trường mà bản ghi không có, hoặc đòi kiểu khác, kiểm thử đỏ.

## Trạng thái hiện tại

Chưa đối tác nào cấp môi trường thử hay phiên ghi thật (quyết định 20/10/2026, YC-PT-13), nên các bản ghi ở đây là phiên ghi giả lập soạn theo dạng phản hồi mà bộ kết nối đang đọc, rồi đi qua công cụ che như một phiên ghi thật. Trường `nguon` của từng tệp ghi rõ điều đó. Khi có phiên ghi thật, thay tệp tương ứng bằng bản đã che của phiên đó.

| Thư mục | Hệ ngoài | Tương tác |
|---|---|---|
| `voffice-van-ban/` | vOffice (DV-13-03) | văn bản chỉ đạo theo con trỏ, văn bản đã ban hành, tệp gốc, 404 |
| `hrm-nhan-su/` | HRM qua REST (DV-13-08) | nhân sự theo con trỏ, chỉ số chuyên môn |
| `lgsp-chi-tieu-ktxh/` | LGSP, chỉ tiêu KT-XH (DV-13-06) | chỉ tiêu theo thời điểm cập nhật |
| `ioffice-van-ban-den/` | iOffice qua REST và SOAP (DV-13-04) | văn bản đến theo con trỏ thời điểm, cả hai giao thức |

## Định dạng

Mỗi tệp JSON có `provider`, `ghiLuc`, `nguon`, `daChe: true` và mảng `exchanges`; mỗi phần tử gồm `request` (`method`, `path`, `query` dạng tên → mảng giá trị, `headers`) và `response` (`status`, `headers`, `body` là JSON hoặc chuỗi). Tệp thiếu `daChe: true` bị kiểm thử từ chối.

## Che dữ liệu trước khi đưa vào repo

Bản ghi thô không bao giờ commit. Chạy công cụ che `RecordingMasker` (mã ở `backend/services/integration-gateway/src/test/java/vn/viettel/okrkpi/integ/contract/`) với khoá HMAC lấy từ OpenBao qua biến `VT_MASK_KEY`:

```bash
cd backend && mvn -q -pl services/integration-gateway -am test-compile \
  dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test
cd services/integration-gateway
VT_MASK_KEY=<khoá base64 từ OpenBao> java -cp target/test-classes:target/classes:$(cat /tmp/cp.txt) \
  vn.viettel.okrkpi.integ.contract.RecordingMasker <thư mục bản ghi thô> ../../../data/golden/integ-recordings/<hệ ngoài>
```

Công cụ thay họ tên, số định danh, số điện thoại, thư điện tử, địa chỉ theo tên trường (kể cả tên trường tiếng Việt có dấu như "Họ và tên"), giữ ngày sinh ở mức năm, thay số định danh, số điện thoại, thư điện tử nằm trong văn bản tự do, đổi `Authorization` thành mã giả lập và xoá cookie. Cùng một giá trị gốc cho cùng một giá trị giả trong một lần che. Họ tên nằm lẫn trong văn bản tự do (ví dụ trích yếu) không tự nhận ra được; người đưa bản ghi vào repo phải đọc lại các trường văn bản tự do. Kiểm thử `BanGhiKhongCoDuLieuCaNhanTest` quét lại toàn thư mục theo mẫu CCCD, số điện thoại, thư điện tử và header, cùng bước gitleaks của pipeline.

## Liên quan

- `wiki/11-kiem-thu-va-chat-luong.md`, mục "Kiểm thử hợp đồng Pact với hệ ngoài".
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-13-11.
