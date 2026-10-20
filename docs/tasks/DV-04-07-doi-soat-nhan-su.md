# DV-04-07 · QT-01/SCR-0104 Đối soát nhân sự: khớp người bằng HMAC và ngày sinh, bảng lệch, phân loại, API giải quyết

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-04-04, DV-13-06 |
| Truy vết | QT-01 → SCR-0104 → `GET/POST /api/v1/org/sync-runs` (`listOrgSyncRuns`, `startOrgSyncRun`), `POST /api/v1/org/sync-runs/{id}/retry` (`retryOrgSyncRun`), `GET/PUT /api/v1/org/sync-schedules` (`listOrgSyncSchedules`, `saveOrgSyncSchedules`), `GET /api/v1/org/discrepancies` (`listOrgDiscrepancies`), `POST /api/v1/org/discrepancies/resolve` (`resolveOrgDiscrepancies`), `POST /api/v1/org/discrepancies/{id}/map` (`mapOrgDiscrepancy`), `POST /api/v1/org/discrepancies/{id}/assign` (`assignOrgDiscrepancy`) → SVC-04, SVC-13 → bảng `org.sync_discrepancy`, `org.scim_sync_log`, `integ.mapping_xref`, `integ.dead_letter` → EVT-15, EVT-16, EVT-21; BR-01-10, BR-01-11, BR-01-12; MSG-01-12..15 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mỗi lần đồng bộ từ nguồn nhân sự (CSDL CBCCVC, CSDL đảng viên, HRM), org-service so dữ liệu nguồn với dữ liệu đang dùng, ghi từng mục lệch có mức Cao, Vừa, Thấp, và cho quản trị tenant giải quyết hàng loạt: lấy theo nguồn, giữ nội bộ, ánh xạ mã, giao người xử lý, chạy lại thông điệp lỗi. Khi xong, SCR-0104 vận hành được với dữ liệu thật và kích hoạt tenant bị chặn khi còn lệch Cao (BR-01-10).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01: phân loại lệch (dòng 519), trùng tên khác ngày sinh (dòng 565), SCR-0104 (dòng 700–742, bảng chức năng dòng 727–735, áp dụng hàng loạt cần AAL2 dòng 707), BR-01-10..12, MSG-01-12..15.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.sync_discrepancy` (dòng 1312–1328), `org.scim_sync_log` (dòng 1295–1310), schema `integ` (dòng 3856), mục "Chỉ mục mù" (dòng 4160).
- Hợp đồng: `contracts/openapi/modules/org.yaml` các thao tác ở bảng (enum mức `CAO`, `VUA`, `THAP`, trường `OrgDiscrepancyField`).
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0104), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực bộ so khớp: nhận lô bản ghi nguồn từ integration-gateway (DV-13-06), khớp người bằng `national_id_bidx` + ngày sinh; hai hồ sơ trùng tên khác ngày sinh là lệch Cao "không khớp người" (MSG-01-15).
- Phân loại lệch: Cao (chức vụ lãnh đạo, không khớp người, đơn vị chính), Vừa (sai thời điểm), Thấp (thông tin phụ); ghi `org.sync_discrepancy` với giá trị nguồn và hiện tại ở cột `_enc` (C2), `suggested_action`, `sync_run_id`.
- Hiện thực `listOrgDiscrepancies` (lọc `severity`, `unresolved`, đếm theo mức), `resolveOrgDiscrepancies` (Idempotency-Key, AAL2 khi hàng loạt; lấy theo nguồn → cập nhật người, phân công với `valid_from` theo quyết định nguồn, phát EVT-15, EVT-16, trả số liệu cho MSG-01-13; giữ nội bộ → đánh dấu bỏ qua tới khi nguồn đổi), `mapOrgDiscrepancy` (ghi ánh xạ mã qua API của integration-gateway vào `integ.mapping_xref`, phát EVT-16), `assignOrgDiscrepancy` (phát EVT-21 cho người được giao).
- Hiện thực `startOrgSyncRun` (202), `listOrgSyncRuns`, `retryOrgSyncRun` (chạy lại từ `integ.dead_letter` qua integration-gateway), `listOrgSyncSchedules`, `saveOrgSyncSchedules`; lỗi đồng bộ trả thông tin cho MSG-01-14.
- Cung cấp API nội bộ đếm lệch Cao theo tenant cho kiểm tra kích hoạt (BR-01-10, MSG-01-12) của DV-03-05.
- Org-service không nhận dữ liệu nguồn từ client ngoài nào khác integration-gateway (BR-01-11).
- Kiểm thử: bộ dữ liệu nguồn giả có đủ ba mức lệch; giải quyết hàng loạt 1.000 mục trong một lệnh; gọi lặp cùng khoá không áp hai lần; đo đồng bộ 100.000 bản ghi ≤ 1 giờ (TL-02a dòng 488) trên môi trường kiểm thử thu nhỏ và nội suy, ghi rõ cách đo.
- Gợi ý tách: (a) so khớp, phân loại, ghi bảng lệch; (b) API giải quyết, ánh xạ, giao việc; (c) lần chạy, lịch, chạy lại.

## 4. Ngoài phạm vi

- Bộ kết nối nguồn và `integ.sync_cursor` (DV-13-06, DV-13-07, DV-13-08); màn hình SCR-0104 (WEB-019).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers PostgreSQL + Kafka, kiểm thử hợp đồng các thao tác ở bảng, `openapi-diff` không phá tương thích; độ phủ dòng `domain`, `application` ≥ 80%.
- Không có giá trị C2 dạng rõ trong bảng lệch, phản hồi danh sách (trừ trường được phép theo quyền) và nhật ký.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
