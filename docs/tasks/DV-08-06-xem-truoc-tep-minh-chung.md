# DV-08-06 · QT-07/SCR-0703 Xem trước tệp minh chứng (`getFilePreview`) qua Gotenberg, chỉ tệp đã quét sạch

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | DV-08-04 (kết luận quét `SACH`), DV-11-01 (Gotenberg 8 trong cell); DV-08-02 (URL ký 5 phút, kiểm quyền tải), CORE-00-05 (ghi vết truy cập C2) (bổ sung) |
| Truy vết | QT-07, QT-09, QT-16 → SCR-0703 vùng 1 ("trang 1 trên 6"), SCR-0902, SCR-1603 → API `GET /api/v1/files/{objectId}/preview` (`getFilePreview`, lược đồ `FilePreview`, `FilePreviewStatus`) → SVC-08 → bảng `evidence.object_version`, `evidence.scan_result`; bucket xem trước theo tenant → Gotenberg 8; BR-09-14; MSG-07-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Trình xem minh chứng theo trang ở SCR-0703 (nghiệm thu), SCR-0902 (thẩm định) và SCR-1603 (đi xuống tới minh chứng) cần hiện từng trang mà không tải thư viện dựng PDF hay Office lớn về trình duyệt, để giữ ngân sách tải đầu của remote. Hợp đồng đã có `getFilePreview`; task này hiện thực ở evidence-service: dựng ảnh từng trang ở tác vụ nền sau khi tệp quét sạch, lưu vào kho đối tượng, trả URL ký 5 phút theo cửa sổ trang, kiểm quyền và ghi vết như tải tệp gốc. Khi xong, mở một minh chứng DOCX 6 trang hiện trang đầu trong vài trăm mili giây khi ảnh đã dựng.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/modules/products.yaml` `getFilePreview` (L1281–1350: `version`, `fromPage`, `count ≤ 20`, `status` `DANG_TAO` kèm `Retry-After`, `KHONG_HO_TRO` cho C3, `409 FILE-409-NOT-CLEAN`, `Cache-Control: no-store`), lược đồ `FilePreview` (L2134), `getFileDownloadUrl` (quy tắc kiểm quyền dùng chung).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` SCR-0703 (L2034–2061, Hình 8.15 vùng 1 trình xem theo trang).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` SVC-08 (L1086–1106), SVC-11 công nghệ (L1150: Gotenberg 8 dựa trên LibreOffice), bảng thư viện (L3755: không dùng iText 7).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema evidence (SVC-08)" (L3397) và `evidence.scan_result`.
- Thẻ `docs/tasks/DV-08-02-tai-len-nhieu-phan.md`, `DV-08-04-quet-ma-doc.md`, `DV-11-01-khung-docgen-gotenberg.md`.

## 3. Phạm vi công việc

- Khi nhận kết luận quét sạch (sự kiện `evidence.object.scanned.v1` hoặc hàng đợi nội bộ của DV-08-04), xếp việc dựng xem trước cho tệp PDF, DOCX, XLSX, JPG, PNG; tệp `e2ee: true` đánh dấu `KHONG_HO_TRO` và không bao giờ gửi đi đâu.
- DOCX, XLSX chuyển sang PDF bằng Gotenberg (route LibreOffice) gọi qua mạng nội bộ của cell; PDF tách trang và rasterize thành ảnh độ phân giải vừa đủ đọc (khoảng 150 dpi, chiều rộng ~1240 px như ví dụ hợp đồng); ảnh gốc chỉ thu nhỏ và bỏ siêu dữ liệu EXIF.
- Thư viện rasterize PDF phía Java (ví dụ Apache PDFBox, Apache 2.0) là phụ thuộc mới: nêu lý do và xin duyệt trong PR theo AGENTS.md quy tắc 1; định dạng ảnh theo ví dụ hợp đồng là WebP, nếu thư viện mã hoá WebP chưa được duyệt thì dùng PNG và ghi vào báo cáo (hợp đồng không ràng buộc định dạng ngoài ví dụ).
- Lưu ảnh vào bucket xem trước của tenant (tên theo quy ước bucket của DV-08-01, có tiền tố tenant ở pool), cùng hạn lưu giữ với tệp gốc; ghi số trang và trạng thái vào bảng phụ hoặc cột bổ sung của `evidence.object_version` (TL-03 chưa có, thêm migration chỉ thêm, đánh dấu "bổ sung (suy luận)", ghi `wiki/14`).
- Hiện thực `getFilePreview`: kiểm quyền và phạm vi giống `getFileDownloadUrl`, kiểm tệp sạch (chưa quét hoặc cách ly trả `409 FILE-409-NOT-CLEAN`, MSG-07-02), trả cửa sổ trang với URL ký 5 phút, `Cache-Control: no-store`, ghi vết truy cập C2 qua cơ chế CORE-00-05; đang dựng trả `DANG_TAO` kèm `Retry-After`.
- Điểm lệch cần lưu ý: Gotenberg được TL-01 đặt cho document-service (SVC-11); evidence-service gọi chung một triển khai Gotenberg trong cell hay có bản riêng chưa được nêu. Chọn gọi bản Gotenberg dùng chung của cell qua NetworkPolicy cho phép riêng (suy luận), ghi vào `wiki/14`; tệp gửi Gotenberg chỉ là tệp đã quét sạch.
- Co giãn: worker dựng xem trước dùng chung hàng đợi KEDA với worker quét (TL-01 SVC-08), giới hạn đồng thời để không làm nghẽn Gotenberg của docgen.

## 4. Ngoài phạm vi

- Trình xem trên giao diện (WEB); xem tệp C3 giải mã tại trình duyệt (WEB, `@vt/e2ee`); OCR (DV-08-05).

## 5. Tệp được phép sửa

- `backend/services/evidence-service/**`
- `deploy/charts/evidence-service/**` (worker xem trước, NetworkPolicy tới Gotenberg)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh gồm Testcontainers Gotenberg 8 và kho S3 giả lập: DOCX 6 trang cho `pageCount = 6`; tệp chưa quét trả 409; tệp C3 trả `KHONG_HO_TRO`; người ngoài phạm vi bị 403; URL hết hạn sau 5 phút.
- Kiểm thử hợp đồng phản hồi `getFilePreview` khớp `products.yaml`; đo và ghi thời gian dựng một tệp DOCX 6 trang.
- `helm lint` và `kubeconform -strict` sạch; báo cáo cuối task theo AGENTS.md mục 6, nêu kết quả xin duyệt phụ thuộc mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
