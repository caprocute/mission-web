# DV-13-06 · SVC-13/QT-01 LGSP, NDXP và CSDL quốc gia cán bộ, công chức, viên chức (đồng bộ đêm, `sync_cursor`)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-13-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-13-01; DV-04-04 (org-service có phân công để nhận dữ liệu nhân sự) nên gộp trước (bổ sung); DV-04-07 (khớp người, bảng lệch) phụ thuộc ngược vào task này |
| Truy vết | QT-01 bước 7 (BR-01-11, BR-01-12), QT-16 (BR-16-05) → API `GET/POST /api/v1/org/sync-runs`, `POST /api/v1/org/sync-runs/{id}/retry`, `GET/PUT /api/v1/org/sync-schedules` (SVC-04 gọi SVC-13) → SVC-13 → bảng `integ.connector`, `integ.sync_cursor`, `integ.inbox_message`, `integ.mapping_xref`, `integ.dead_letter` → EVT-15, EVT-16 (do SVC-04 phát) |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Kéo hồ sơ cán bộ, công chức, viên chức từ CSDL quốc gia CBCCVC qua NDXP theo lịch đêm có con trỏ, chuyển sang mô hình chuẩn nhóm 1 (Subject, Position, Assignment) và chuyển cho org-service khớp người, ghi hai trục thời gian; đồng thời nhận chỉ tiêu KT-XH qua LGSP tỉnh/bộ kèm cờ đã công bố. Khi xong, 100.000 bản ghi nhân sự đồng bộ trong ≤ 1 giờ và không dữ liệu hệ ngoài nào vào nền tảng mà không qua SVC-13 (BR-01-11).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 (L476), bảng bước (L505–517, bước 7), "Quy tắc nghiệp vụ" (BR-01-10..12, L554–556), SCR-0104.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1409–1426, dòng LGSP/NDXP và CSDL quốc gia CBCCVC), "Mô hình dữ liệu chuẩn theo tám nhóm PL 4" (L1461).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Tích hợp hệ ngoài qua SVC-13" (L4384), từ điển `integ.sync_cursor` (L3910), "Chỉ mục mù" (L4160).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16 "Quy tắc nghiệp vụ" (L1655, BR-16-05 cờ `da_cong_bo`).
- `contracts/openapi/modules/org.yaml` các operation `listOrgSyncRuns`, `startOrgSyncRun`, `retryOrgSyncRun`, `listOrgSyncSchedules`, `saveOrgSyncSchedules`; bản trích `extract/tl02a-may-chu.md` QT-01 (BE-01-13).

## 3. Phạm vi công việc

- Viết adapter NDXP (REST qua nền tảng tích hợp, xác thực máy-máy bằng chứng thư tổ chức) và route kéo `cbccvc-persons` theo lịch đêm trong `connector.schedule_cron`, con trỏ `sync_cursor` (stream theo đơn vị), ghi `lag_seconds`, `last_success_at`.
- Viết translator hồ sơ CBCCVC sang mô hình chuẩn nhóm 1: số định danh chỉ chuyển dạng mã hoá trường hoặc HMAC theo BR-01-12 (không ghi rõ ở bất kỳ bảng hay nhật ký nào), đơn vị qua `mapping_xref` `DON_VI`, người qua `mapping_xref` `NHAN_SU`.
- Chuyển lô đã chuẩn hoá cho SVC-04 (lệnh qua Kafka nội bộ hoặc cổng nội bộ, theo cách chốt cùng DV-04-07; không đặt mã EVT mới) để org-service khớp người, ghi `org.person`, `org.assignment` và phát EVT-15, EVT-16.
- Hỗ trợ "Đồng bộ ngay" và "Chạy lại thông điệp lỗi" của SCR-0104: use case nhận yêu cầu từ SVC-04, trả trạng thái lần chạy (số đã nhận, đã chuyển, lỗi, số thư chết) cho `listOrgSyncRuns`.
- Viết adapter LGSP nhận chỉ tiêu KT-XH (QĐ 10.2.2) kèm cờ `da_cong_bo` và thời điểm công bố, phát thành dữ liệu nguồn cho kho phân tích (BR-16-05); thông điệp chưa công bố vẫn lưu nhưng đánh dấu.
- Viết job đo thời gian đồng bộ 100.000 bản ghi giả lập và metric độ trễ con trỏ.
- Gợi ý tách nếu quá lớn: (a) NDXP + CSDL CBCCVC; (b) LGSP chỉ tiêu KT-XH.

## 4. Ngoài phạm vi

- Khớp người, bảng lệch, phân loại mức lệch ở org-service (DV-04-07); SCIM 2.0 từ IdP tỉnh (DV-04-06).
- Chia sẻ ngược EVT-12 lên CSDL CBCCVC (DV-13-09); CSDL đảng viên (DV-13-07).
- Nối và kiểm thử với NDXP, LGSP và dịch vụ chia sẻ của CSDL quốc gia CBCCVC thật khi có môi trường; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử với NDXP giả lập: 100.000 bản ghi qua 500 trang, gián đoạn giữa chừng rồi chạy tiếp từ con trỏ không trùng, không sót; thời gian ghi vào báo cáo so với mốc ≤ 1 giờ.
- Kiểm thử chứng minh không có số định danh dạng rõ trong `inbox_message`, thông điệp Kafka và nhật ký.
- Chỉ tiêu KT-XH chưa công bố không được đánh dấu `da_cong_bo`.
- Địa chỉ NDXP, LGSP, dịch vụ CSDL CBCCVC, chứng thư và tham số kết nối nằm trong bản ghi `integ.connector` và giá trị triển khai, không nằm trong mã; đổi từ giả lập sang hệ thật chỉ đổi cấu hình kết nối, không đổi mã.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
