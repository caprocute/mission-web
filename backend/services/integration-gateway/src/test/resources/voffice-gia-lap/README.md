# vOffice giả lập (hợp đồng phía tiêu dùng của DV-13-03)

Thư mục này là hợp đồng phía tiêu dùng mà integration-gateway (SVC-13) kỳ vọng ở vOffice, viết dưới dạng ánh xạ WireMock để QA-02 và môi trường dev chạy lại được: `java -jar wiremock-standalone-3.13.1.jar --root-dir <thư mục này> --port 35180`. `VOfficeContractTest` kiểm rằng bộ kết nối đọc đúng các phản hồi ở đây, nên khi đổi hợp đồng phải sửa cả hai.

- `GET /api/v2/directives?updatedSince=<ISO-8601>&limit=200`: văn bản chỉ đạo cập nhật từ thời điểm `updatedSince` (gồm cả mốc), kèm `nextUpdatedSince`. Mỗi phần tử có `id`, `version`, `docNumber`, `issuedDate`, `issuer`, `summary`, `recipientUnitId`, `updatedAt`, `attachments`; phần tử có trường `edxml` mang nguyên gói EdXML (NĐ 30/2020).
- `GET /api/v2/documents?unitId=&q=&limit=&cursor=`: văn bản đã ban hành của đơn vị, mỗi phần tử có `id` (tham chiếu tệp gốc), `docNumber`, `issuedDate`, `summary`, `signerName`, `signatureValid`, kèm `nextCursor`.
- Mọi lời gọi mang `Authorization: Bearer <mã thông báo>`, giá trị lấy từ OpenBao theo `integ.connector.secret_path`; bản giả lập chấp nhận mã `ma-gia-lap`.

Đây là giả lập theo quyết định 20/10/2026 (YC-PT-13); khi có môi trường thử vOffice thật, đối chiếu lại hợp đồng này rồi chỉ đổi bản ghi `integ.connector`.
