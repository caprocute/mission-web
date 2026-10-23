# iOffice giả lập (DV-13-04)

Ánh xạ WireMock của giao diện văn bản đến iOffice (VNPT) mà bộ kết nối của integration-gateway
kỳ vọng. Giao diện là suy luận vì chưa có tài liệu giao diện chính thức của đối tác (wiki trang
14, mục DV-13-04); khi có tài liệu hoặc bản ghi tương tác thật đã che dữ liệu, sửa các ánh xạ này
và tệp hợp đồng `pacts/integration-gateway-ioffice.json` cho khớp, rồi chạy lại `IOfficeContractTest`.

- `rest-trang-dau.json`, `rest-het.json`: REST `GET /api/v1/van-ban-den?tuThoiDiem=&soLuong=200`,
  trang đầu một văn bản, trang sau rỗng.
- `soap-trang-dau.json`: SOAP 1.1 `POST /ws/VanBanDenService`, thao tác `layDanhSachVanBanDen`,
  một văn bản có hai nơi nhận.
- `soap-loi.json`: SOAP Fault.

Mã thông báo giả lập là `ma-gia-lap` (không phải bí mật thật). Dữ liệu là giả lập, không có thông
tin cá nhân thật. QA-02 dùng lại thư mục này khi dựng iOffice giả lập cho kiểm thử đầu cuối.
