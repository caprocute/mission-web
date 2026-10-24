# DV-15-04 · SVC-15/SCR-1601, SCR-1602 analytics-api: proxy Cube theo token, đệm, API định nghĩa dashboard

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-03; NEN-019 (mẫu service Java) đã gộp (bổ sung); cần cổng nội bộ lưu `config.dashboard_def` ở MOD-08 (xem điểm lệch) |
| Truy vết | QT-16 (BR-16-01, BR-16-02, BR-16-08, MSG-16-01) → SCR-1601, SCR-1602 → API `POST /api/v1/analytics/query`, `GET /api/v1/analytics/semantic-model`, `GET/POST /api/v1/analytics/dashboards`, `GET/PUT /api/v1/analytics/dashboards/{id}`, `POST /api/v1/analytics/dashboards/{id}/shares` → SVC-15 → Cube, Valkey, bảng `config.dashboard_def` → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng `analytics-api` (Java 21, Spring Boot) làm điểm vào duy nhất của bảng điều hành và trình dựng dashboard: chuyển truy vấn tới Cube REST `/load` với `securityContext` lấy từ token người dùng, đệm kết quả theo tenant, đơn vị, kỳ, kiểm định nghĩa dashboard chỉ dùng chỉ số đã công bố. Khi xong, SCR-1601 và SCR-1602 chạy với API thật, mọi truy vấn lọc theo phạm vi người xem và trả thời điểm cập nhật dữ liệu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1200–1204: đệm Valkey 60 giây theo tenant, đơn vị, kỳ).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16 "Quy tắc nghiệp vụ" (L1655), "Ngoại lệ và xử lý" (L1668: không dự phòng truy vấn thẳng mission-core khi CDC trễ), SCR-1601 (L1678), SCR-1602 (L1728, ràng buộc dashboard: tên ≤ 100, ≤ 20 widget, ≤ 2 chiều).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng config.dashboard_def (bổ sung)" (L1661).
- `contracts/openapi/modules/platform.yaml` operation `queryAnalytics`, `getSemanticModel`, `listDashboards`, `createDashboard`, `getDashboard`, `updateDashboard`, `shareDashboard`.
- Bản trích `extract/tl02b-may-chu.md` QT-16 (BE-Q16-01, BE-Q16-03).

## 3. Phạm vi công việc

- Tạo `backend/services/analytics-api/` bằng `scripts/new-service.sh`; hiện thực `queryAnalytics`: kiểm truy vấn chỉ gồm measures/dimensions có trong lớp ngữ nghĩa (BR-16-02), dựng `securityContext` (tenant, `org_path`) từ JWT, gọi Cube `/load` với Resilience4j, trả kèm thời điểm dữ liệu; CDC trễ quá 5 phút thì trả cờ cảnh báo để giao diện hiện MSG-16-01, không dự phòng sang mission-core.
- Đệm kết quả trong Valkey theo khoá `t:<tenant>:analytics:...` gồm đơn vị, kỳ, băm truy vấn và phạm vi người xem; không đệm khi phạm vi khác nhau dù cùng truy vấn.
- Hiện thực `getSemanticModel` trả danh mục chỉ số, chiều theo phiên bản lớp ngữ nghĩa từ Cube meta.
- Hiện thực năm operation dashboard: tên ≤ 100 ký tự không trùng trong không gian người dùng, ≤ 20 widget, ≤ 2 chiều mỗi widget, chỉ lưu định nghĩa; chia sẻ chỉ trong tenant (`share_scope` `RIENG`, `DON_VI`, `TENANT`) và phát EVT-21 cho người được chia sẻ; `If-Match` cho cập nhật (409 khi lệch).
- Điểm lệch cần lưu ý: TL-01 ghi đệm Valkey 60 giây, bản trích TL-02b ghi "cache 5 phút" cho `POST /analytics/query` và TL-03 làm mới pre-aggregation mỗi 5 phút; task đặt TTL đệm 60 giây theo TL-01 (độ trễ tổng vẫn ≤ 5 phút theo BR-16-08) và ghi vào `wiki/14`. Hợp đồng giao API dashboard cho SVC-15 nhưng TL-03 đặt bảng `config.dashboard_def` ở schema `config` của mission-core; theo TL-03 chương 2 (L143–151, sở hữu dữ liệu theo service) analytics-api không ghi schema của module khác, nên lưu qua cổng nội bộ của MOD-08 (cần CORE-08 cung cấp, suy luận) và báo cáo nếu cổng chưa có.

## 4. Ngoài phạm vi

- Chuỗi truy vết, xuất báo cáo, nhắc và giao lại (DV-15-06); Superset (DV-15-05); nối màn hình với API thật (WEB-072).

## 5. Tệp được phép sửa

- `backend/services/analytics-api/**`
- `backend/pom.xml` (chỉ thêm module)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/analytics-api -am verify` xanh: Testcontainers (Cube giả lập hoặc thật + ClickHouse, Valkey), ArchUnit, so khớp phản hồi với `platform.yaml`, `openapi-diff` không phá tương thích.
- Kiểm thử chứng minh: measure ngoài danh mục bị 422; hai người khác phạm vi không dùng chung kết quả đệm; dashboard 21 widget bị từ chối; cập nhật với `If-Match` cũ trả 409.
- Bảng điều hành p95 ≤ 1 giây khi trúng đệm ở tải thử cục bộ (ghi số đo).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
