# CORE-03-04 · BR-07-13 Minh chứng là văn bản đã ban hành trên vOffice, kiểm chữ ký số gốc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-03-03, DV-13-03 |
| Truy vết | QT-07 → SCR-0701 → API `GET /api/v1/evidences/external-docs` (`searchEvidenceExternalDocs`) → MOD-03 → SVC-13 (vOffice) → `product.evidence.source_type`, `product.evidence.external_doc_ref` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người nộp tìm được văn bản đã ban hành của đơn vị trong vOffice và dùng làm minh chứng mà không tải lại tệp; máy chủ lấy tệp và chữ ký số gốc qua SVC-13, kiểm chữ ký khi nhận và lưu tham chiếu. Việc này bảo đảm BR-07-13 và giảm minh chứng bị chỉnh sửa so với bản gốc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 "Quy tắc nghiệp vụ" (dòng 1917, BR-07-13) và "SCR-0701. Nộp minh chứng" (dòng 1949, chức năng chọn văn bản đã ban hành, dòng 1980).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương tích hợp: danh sách bộ kết nối (vOffice) và Anti-Corruption Layer.
- TL-03 bảng `product.evidence` (dòng 2532, `source_type`, `external_doc_ref`).
- `contracts/openapi/modules/products.yaml`: `searchEvidenceExternalDocs`, `createEvidence`, `createEvidenceVersion`.
- Bản trích `tl02a §QT-07 BE-07-6`.

## 3. Phạm vi công việc

- Hiện thực `searchEvidenceExternalDocs`: gọi cổng của SVC-13 (DV-13-03) có thời hạn chờ, ngắt mạch và thử lại theo thư viện nền; chỉ trả văn bản thuộc đơn vị trong phạm vi ABAC của người gọi; phân trang con trỏ `limit ≤ 200`.
- Khi tạo minh chứng nguồn `VAN_BAN`: lưu `external_doc_ref`, yêu cầu SVC-13 chuyển tệp gốc sang SVC-08 (tham chiếu đối tượng, không tải lại từ trình duyệt), đặt `source = 'VAN_BAN_HE_THONG'` cho phiên bản.
- Kiểm chữ ký số gốc khi nhận: chữ ký không hợp lệ hoặc tệp bị thay đổi thì từ chối gắn và trả lỗi RFC 9457 trỏ BR-07-13; ghi kết quả kiểm vào trường mô tả của phiên bản và sổ cái qua outbox.
- Điền sẵn `doc_number`, `doc_date`, `doc_summary` từ siêu dữ liệu vOffice; các trường này không cần xác nhận AI vì không do AI điền.
- Không cho sửa nội dung tệp gốc; nộp lại chỉ tạo phiên bản mới (BR-07-01).
- Viết kiểm thử với bản giả SVC-13 (WireMock) cho các trường hợp: văn bản hợp lệ, chữ ký sai, SVC-13 không phản hồi.

## 4. Ngoài phạm vi

- Bộ kết nối vOffice và ACL (DV-13-03); kiểm thử với vOffice thật (bị chặn bởi môi trường bên ngoài, làm khi có kết nối).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; phản hồi khớp `products.yaml`.
- Kiểm thử chứng minh văn bản có chữ ký sai không trở thành minh chứng; SVC-13 lỗi trả 503 có `Retry-After`, không treo luồng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
