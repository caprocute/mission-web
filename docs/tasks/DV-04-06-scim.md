# DV-04-06 · SVC-04 SCIM 2.0 cho HRM và IdP khách hàng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-04-04 |
| Truy vết | QT-01 bước đồng bộ → SVC-04 `/scim/v2/Users`, `/scim/v2/Groups` (RFC 7643, RFC 7644) → bảng `org.person`, `org.assignment`, `org.scim_sync_log` → EVT-15, EVT-16; BR-01-11, BR-01-12; tác nhân AC-11 (client credentials) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

HRM và IdP của tỉnh, bộ ngành đẩy thay đổi nhân sự gần thời gian thực qua chuẩn SCIM 2.0 thay vì chỉ chờ đồng bộ đêm. Khi xong, một client SCIM đã đăng ký cho tenant tạo, sửa, vô hiệu hoá người và nhóm; mọi lệnh được ghi `org.scim_sync_log`, người được khớp bằng chỉ mục mù, số định danh lưu mã hoá, và thay đổi phát EVT-15, EVT-16.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-04 org-service" (dòng 1028–1046: SCIM 2.0 `/scim/v2/Users`, `/scim/v2/Groups`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 tác vụ nền (dòng 488, 515: SCIM gần thời gian thực), BR-01-11, BR-01-12.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.scim_sync_log` (dòng 1295–1310), `org.person` (dòng 1159–1180), mục "Chỉ mục mù" (dòng 4160).
- Hợp đồng: không có tệp OpenAPI cho SCIM trong `contracts/openapi/` (xem điểm lệch); chuẩn RFC 7643, RFC 7644.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` (AC-11), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực tài nguyên `Users` (GET lọc `userName eq`, `externalId eq`; POST; PUT; PATCH; DELETE là vô hiệu hoá, không xoá vật lý) và `Groups` (ánh xạ sang đơn vị hoặc vị trí, ghi rõ cách ánh xạ), cùng `ServiceProviderConfig`, `ResourceTypes`, `Schemas`.
- Xác thực bằng token client credentials (AC-11) gắn với một tenant và một nguồn; client chỉ ghi được trong tenant của mình.
- Ánh xạ thuộc tính: số định danh và ngày sinh ghi vào cột `_enc` và `_bidx` qua thư viện mã hoá nền (BR-01-12), không lưu rõ; khớp người hiện có bằng `national_id_bidx` + ngày sinh; không khớp thì tạo người mới hoặc đẩy vào bảng lệch theo tham số nguồn.
- Mọi lệnh ghi một dòng `org.scim_sync_log` (`source_system`, `operation`, `resource_type`, `external_id`, `internal_id`, `request_hash`, `status`, `error_detail`); khử trùng lệnh lặp bằng `request_hash`.
- Thay đổi người và phân công đi qua dịch vụ miền của DV-04-04, phát EVT-15, EVT-16 qua outbox.
- Lỗi theo định dạng lỗi SCIM (RFC 7644 mục 3.12) và vẫn ghi `scim_sync_log`.
- Kiểm thử: bộ ca tuân thủ SCIM cơ bản (tạo, lọc, PATCH, vô hiệu hoá); client tenant A ghi tenant B bị 403; số định danh không xuất hiện dạng rõ trong CSDL và nhật ký.
- Điểm lệch cần lưu ý: BR-01-11 (TL-02a) ghi "dữ liệu ngoài chỉ vào qua integration-gateway; org-service không nhận ghi từ client ngoài", còn TL-01 đặt SCIM trực tiếp trên org-service. Chọn theo TL-01 cho vị trí endpoint nhưng chỉ công bố tuyến SCIM cho client đã đăng ký qua cổng (không mở cho người dùng), và ghi điểm lệch vào wiki trang 14 để người phụ trách xác nhận. Hợp đồng chưa có tệp mô tả SCIM: được phép thêm tệp mới `contracts/openapi/modules/scim.yaml` mô tả đúng RFC 7644 (chỉ thêm, không sửa tệp cũ), chạy `openapi-diff` và nêu trong PR để duyệt.

## 4. Ngoài phạm vi

- Đồng bộ đêm từ CSDL CBCCVC, CSDL đảng viên (DV-13-06, DV-13-07); đối soát lệch (DV-04-07).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `contracts/openapi/modules/scim.yaml` (tệp mới)
- `deploy/charts/api-gateway/**` (chỉ route `/scim/v2/*` cho client credentials)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/07-xac-thuc-phan-quyen.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers và bộ ca SCIM ở mục 3.
- Lint hợp đồng (`redocly lint` theo `contracts/redocly.yaml`) đạt với tệp `scim.yaml` mới; `openapi-diff` cho các tệp cũ không đổi.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
