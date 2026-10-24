# DV-08-02 · QT-07/SVC-08 Tải lên nhiều phần có tiếp tục, SHA-256 khi nhận, Object Lock, URL tải ký 5 phút

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | DV-08-01 |
| Truy vết | QT-07 → SCR-0701 → `POST /api/v1/files/uploads` (`createFileUpload`), `GET`/`DELETE /api/v1/files/uploads/{uploadId}` (`getFileUpload`, `cancelFileUpload`), `PUT /api/v1/files/uploads/{uploadId}/parts/{n}` (`uploadFilePart`), `POST /api/v1/files/uploads/{uploadId}/complete` (`completeFileUpload`), `GET /api/v1/files/{objectId}/download-url` (`getFileDownloadUrl`) → SVC-08 → `evidence.upload_session`, `evidence.object_meta`, `evidence.object_version` → sự kiện kỹ thuật `evidence.object.stored.v1`; BR-07-01, BR-07-02; MSG-07-03; ADR-13; SEC-48 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người nộp minh chứng tải tệp lớn theo từng phần, mất mạng thì tải tiếp từ phần dở, máy chủ tính SHA-256 trong khi nhận và đối chiếu với mã băm khai báo, rồi ghi vào Ceph RGW với Object Lock để không sửa, không xoá được. Khi xong, MOD-03 nhận sự kiện kỹ thuật để gắn đối tượng với phiên bản minh chứng, và người có quyền nhận URL tải về có chữ ký sống 5 phút.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 "Quy tắc nghiệp vụ" (BR-07-01, BR-07-02, dòng 1917–1933), "Đặc tả màn hình" SCR-0701 (dòng 1947–1990), MSG-07-03.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-08 evidence-service" (dòng 1086–1106) và đoạn cơ chế toàn vẹn minh chứng (dòng 2197).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema evidence (SVC-08)" (dòng 3391–3471).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` SEC-48 (bảng SEC, dòng 2572; mục 9.5).
- Hợp đồng: `contracts/openapi/modules/products.yaml` (`/files/uploads*`, `/files/{objectId}/download-url`).

## 3. Phạm vi công việc

- `createFileUpload` (Idempotency-Key): kiểm loại tệp cho phép, kích thước khai báo ≤ 50 MB mặc định hoặc giới hạn tenant ≤ 200 MB (SEC-48), tạo multipart trên RGW và dòng `upload_session` (`MO`, `expires_at`); trả `uploadId`, kích thước phần 5–16 MB.
- `uploadFilePart`: nhận luồng nhị phân không đệm toàn bộ vào bộ nhớ, gửi lại cùng phần là an toàn; chỉ người khởi tạo phiên được gửi; `getFileUpload` trả các phần đã nhận để tiếp tục; `cancelFileUpload` huỷ multipart và đặt `HUY`.
- `completeFileUpload`: hoàn tất multipart, tính lại SHA-256 ở máy chủ, sai thì từ chối và trả lỗi tương ứng MSG-07-03 (BR-07-02); kiểm nội dung thật khớp kiểu khai báo (SEC-48); ghi `object_meta`, `object_version`, outbox `evidence.object.stored.v1` trong một giao dịch; trả `objectId`, `sha256`.
- Ghi đối tượng với Object Lock compliance; nộp lại tạo phiên bản mới, không ghi đè (BR-07-01).
- Tệp C3 (`e2ee: true`): nhận bản mã, băm khai báo là băm bản mã, đánh dấu không quét và không khử trùng.
- `getFileDownloadUrl`: kiểm quyền qua OPA theo quan hệ người xem với minh chứng, ghi nhật ký truy cập, trả URL ký trước sống 5 phút.
- Job dọn `upload_session` quá `expires_at`: huỷ multipart dở dang trên RGW, đặt `HUY`.
- Điểm lệch cần lưu ý: TL-01 cho phần đi qua service (`PUT .../parts/{n}`), TL-03 mô tả URL ký trước 15 phút để tệp đi thẳng tới RGW; hợp đồng theo TL-01 nên chọn đi qua service, ghi wiki trang 14. Tên sự kiện kỹ thuật cũng lệch: `evidence.object.stored.v1` (TL-01 SVC-08), `sys.evidence.object.stored.v1` (TL-01 mục AI) và `evidence.object.scanned.v1` (TL-03); chọn `evidence.object.stored.v1` cho hoàn tất tải lên; nếu `contracts/events/` chưa có lược đồ thì dừng và báo người phụ trách hợp đồng, không tự thêm.
- Gợi ý tách: (a) phiên tải lên, phần, hoàn tất, Object Lock; (b) URL tải về, job dọn phiên.

## 4. Ngoài phạm vi

- Khử trùng (DV-08-03), quét (DV-08-04); gắn đối tượng với phiên bản minh chứng ở MOD-03 (CORE-03).

## 5. Tệp được phép sửa

- `backend/services/evidence-service/**`
- `deploy/charts/evidence-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh gồm kiểm thử hợp đồng sáu thao tác, Testcontainers (PostgreSQL, Kafka, MinIO có Object Lock): tải 3 phần, ngắt ở phần 2 rồi tiếp tục; băm sai bị từ chối; xoá đối tượng bị RGW từ chối; outbox có đúng một sự kiện.
- Tải tệp 50 MB không làm bộ nhớ heap của pod tăng quá kích thước một phần (đo và ghi số).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
