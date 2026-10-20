# DV-15-05 · SVC-15/ADR-14 Superset nhúng với guest token theo phạm vi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-03; DV-15-04 (điểm cấp guest token đặt trong analytics-api, bổ sung) |
| Truy vết | QT-16 (BR-16-01, BR-16-02, BR-16-04) → SCR-1602 (dashboard tự dựng nâng cao) → SVC-15 Superset 4.x, Cube SQL API → ADR-14, ADR-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Triển khai Apache Superset 4.x làm công cụ dựng dashboard nâng cao nhúng vào giao diện web, chỉ nói chuyện với Cube (không nối thẳng ClickHouse), và cấp guest token ngắn hạn mang phạm vi tenant, đơn vị của người xem. Khi xong, dashboard nhúng chỉ hiện số liệu trong phạm vi người xem, không lộ cột C2 rõ, và người dùng không đăng nhập riêng vào Superset.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1194–1204), ADR-14 (L3522), bảng phiên bản phần mềm (L3736: Superset 4.x, Apache 2.0), mục cách ly (L357: ClickHouse và Superset có thể tắt).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Phân quyền phía đọc" (L4347–4349: "Superset nhúng chỉ nói chuyện với Cube").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng tài nguyên (L877: Superset 2/4 replica, 3 GiB, Gunicorn, `/health`) và namespace `analytics` (L305).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` SCR-1602 (L1728), "Quy tắc nghiệp vụ" QT-16 (L1655).
- Bản trích `extract/tl01-thanh-phan.md` mục C14.3.

## 3. Phạm vi công việc

- Viết cấu hình Superset (`superset_config.py`) trong `analytics/superset/`: bật `EMBEDDED_SUPERSET`, `GUEST_ROLE_NAME` chỉ có quyền xem dashboard nhúng, tắt SQL Lab và tải lên CSV cho mọi vai trò ngoài quản trị kỹ thuật, CSP và `TALISMAN` theo TL-01 mục CSP (chỉ cho phép khung từ origin web của nền tảng).
- Khai báo nguồn dữ liệu duy nhất là Cube SQL API (cổng Postgres wire của Cube) với tài khoản kỹ thuật; không khai báo kết nối ClickHouse hay PostgreSQL nghiệp vụ.
- Thêm vào analytics-api điểm cấp guest token nội bộ (gọi `/api/v1/security/guest_token/` của Superset) với `rls` gồm `tenant_id` và tập đơn vị trong phạm vi lấy từ JWT, thời hạn ngắn (≤ 5 phút, suy luận); endpoint này cần bổ sung hợp đồng trước khi hiện thực (xem điểm lệch).
- Đồng bộ phạm vi: Cube nhận `securityContext` tương ứng từ guest token để `queryRewrite` áp cùng bộ lọc như DV-15-03; thử chéo hai tenant.
- Đóng gói dashboard mẫu (xuất ZIP Superset) cho bảng điều hành cấp đơn vị để người dùng sao chép, lưu trong `analytics/superset/dashboards/`.
- Điểm lệch cần lưu ý: `platform.yaml` chưa có operation cấp guest token Superset cho giao diện; theo nguyên tắc API-first, task đề xuất bổ sung operation vào hợp đồng (người phụ trách hợp đồng duyệt) trước khi viết mã, không tự thêm endpoint ngoài hợp đồng.

## 4. Ngoài phạm vi

- Helm chart và tài nguyên cụm cho Superset (INF); thành phần web nhúng SDK Superset (WEB-072); bản cách ly tắt Superset (DL hoặc INF airgap).

## 5. Tệp được phép sửa

- `analytics/superset/**`
- `backend/services/analytics-api/**` (chỉ phần cấp guest token)
- `contracts/openapi/modules/platform.yaml` (chỉ khi người phụ trách hợp đồng đã duyệt bổ sung)
- `infra/dev/` (Superset trong profile phân tích, nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/analytics-api -am verify` xanh, có kiểm thử guest token chứa đúng `rls` theo JWT và hết hạn đúng hạn.
- Kiểm thử tích hợp (Docker Compose cục bộ: Superset + Cube + ClickHouse với dữ liệu mẫu hai tenant): dashboard nhúng với guest token tenant A không hiện số của tenant B; guest token không mở được SQL Lab hay API dataset.
- Quét cấu hình: không có chuỗi kết nối nào tới ClickHouse hoặc PostgreSQL trong Superset.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
