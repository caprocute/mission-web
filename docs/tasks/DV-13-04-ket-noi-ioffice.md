# DV-13-04 · SVC-13/EVT-24 Bộ kết nối iOffice và hệ văn bản khác

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-03 |
| Truy vết | QT-05 bước 1b → SVC-13 → bảng `integ.connector`, `integ.inbox_message`, `integ.mapping_xref`, `integ.dead_letter` → EVT-24 |
| Trạng thái | Bị chặn bởi bên ngoài: cần môi trường thử iOffice (VNPT) và tài liệu giao diện của đối tác |

## 1. Mục tiêu

Thêm bộ kết nối chiều vào cho iOffice (VNPT) và các hệ điều hành văn bản của hãng khác theo REST hoặc SOAP của đối tác, dùng lại translator văn bản chỉ đạo của DV-13-03 để mọi văn bản đến đều hội tụ về EVT-24 cùng cấu trúc. Khi xong, tenant dùng iOffice nhận văn bản chỉ đạo vào nền tảng như tenant dùng vOffice.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1409–1426, dòng "iOffice (VNPT) và hệ văn bản khác": REST/SOAP đối tác, Pact, chiều vào), "Lớp chống ô nhiễm" (L1358).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 bước 1b (L1425).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Đợt 3: quy mô quốc gia" (L3170, bộ kết nối cho văn phòng số của hãng khác).
- Thẻ DV-13-03 và mã translator đã gộp.

## 3. Phạm vi công việc

- Viết adapter iOffice theo giao diện đối tác (REST qua `camel-rest` hoặc SOAP qua `camel-cxf`), kéo theo `sync_cursor` hoặc nhận đẩy tuỳ đối tác; chống trùng theo mã văn bản và phiên bản của iOffice.
- Viết translator riêng cho cấu trúc iOffice sang mô hình chuẩn, dùng chung bước phát EVT-24 và ánh xạ đơn vị của DV-13-03; không phát cấu trúc riêng đối tác vào Kafka nội bộ.
- Cấu hình bộ kết nối theo tenant trong `integ.connector` (giao thức, lịch, `secret_path`), không cần đổi mã khi thêm tenant dùng iOffice.
- Viết kiểm thử hợp đồng Pact hướng người tiêu dùng cho kỳ vọng của SVC-13 với iOffice; khi đối tác chưa hợp tác, kiểm ngược trên bản ghi tương tác thật đã che dữ liệu (TL-01 L1426).
- Ghi vào wiki (trang 14 hoặc trang tích hợp) danh sách trường iOffice đã ánh xạ và trường bỏ qua.

## 4. Ngoài phạm vi

- Chiều ra tới iOffice (chưa có yêu cầu trong TL); Trục liên thông EdXML (DV-13-05).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử với iOffice giả lập: văn bản đến sinh EVT-24 có cấu trúc giống hệt văn bản vOffice tương đương (so khớp lược đồ Avro).
- Hợp đồng Pact được công bố lên Pact Broker của QA-02 (hoặc lưu tệp hợp đồng nếu Broker chưa có, ghi rõ).
- Báo cáo ghi rõ đã hay chưa kiểm với iOffice thật.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
