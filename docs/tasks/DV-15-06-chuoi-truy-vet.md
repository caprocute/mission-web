# DV-15-06 · QT-16/SCR-1603 Đi xuống tới minh chứng: chuỗi truy vết, xuất bằng chứng và báo cáo 202, nhắc và giao lại

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-04; DV-11-01 (sinh PDF báo cáo), DV-09-02 (mã bản ghi sổ cái), API chi tiết của CORE-03, CORE-04 (bổ sung) |
| Truy vết | QT-16 (BR-16-03, BR-16-04, BR-16-07; MSG-16-02, MSG-16-03, MSG-16-06, MSG-16-07) → SCR-1601, SCR-1603 → API `GET /api/v1/analytics/trace/{objectType}/{id}` (`getTraceChain`), `POST /api/v1/analytics/trace/exports` (`exportTraceChain`), `POST /api/v1/analytics/reports` (`exportAnalyticsReport`), `POST /api/v1/tasks/{id}/reminders` (`remindTask`), `POST /api/v1/tasks/{id}/reassign` (`reassignTask`) → SVC-15, MOD-02 → topic `sys.audit.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực chuỗi truy vết tám mắt xích (điểm, cấu phần, KPI hoặc KR, sản phẩm, minh chứng, kế hoạch, mục tiêu O, căn cứ), trong đó ba mắt xích đầu lấy từ kho phân tích và các mắt xích sau lấy từ mission-core có kiểm quyền từng mắt xích; xuất chuỗi bằng chứng và báo cáo bảng điều hành dưới dạng tác vụ dài 202; và bảo đảm hành động nhắc, giao lại đi qua API của MOD-02 với quyền của người bấm. Khi xong, SCR-1603 lần được từ điểm tới minh chứng mà không lộ C2 từ kho phân tích.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16 "Quy tắc nghiệp vụ" (L1655, BR-16-03, BR-16-04, BR-16-07), thông điệp MSG-16-01..03 (L1724), SCR-1603 (L1774, MSG-16-06, MSG-16-07 ở L1815).
- `contracts/openapi/modules/platform.yaml` operation `getTraceChain`, `exportTraceChain`, `exportAnalyticsReport`, schema `TraceChain`, `TraceObjectType`, `TraceExportRequest`, `AnalyticsReportRequest`.
- `contracts/openapi/modules/tasks.yaml` operation `remindTask`, `reassignTask`; `contracts/openapi/modules/jobs.yaml` (theo dõi tác vụ dài).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-15 analytics" (L1204: đi xuống chuyển sang API lõi ở cấp hồ sơ cá nhân).
- Bản trích `extract/tl02b-may-chu.md` QT-16 (BE-Q16-04..07).

## 3. Phạm vi công việc

- Hiện thực `getTraceChain` trong analytics-api: ba mắt xích điểm, cấu phần, KPI/KR đọc từ Cube/ClickHouse theo mã; các mắt xích sau gọi API chi tiết của mission-core bằng token của người xem (chuyển tiếp, không dùng tài khoản dịch vụ), mắt xích bị 403 trả `masked = true` kèm MSG-16-06 và chuỗi vẫn tiếp tục (BR-16-04).
- Mắt xích minh chứng trả số phiên bản được tính điểm và MSG-16-07 khi có nhiều phiên bản; việc mở minh chứng được ghi sổ cái qua API của evidence-service, nội dung tệp mở qua `GET /evidences/{id}/versions/{v}/content` (không đi qua analytics).
- Hiện thực `exportTraceChain` trả 202 + `Location` tới tác vụ trong `jobs.yaml`: sinh JSON và PDF chỉ gồm mắt xích người xuất có quyền, kèm mã băm từng mắt xích và `ledgerRef`; ghi sự kiện `sys.audit.v1` cho việc xuất.
- Hiện thực `exportAnalyticsReport` trả 202: gọi Cube với phạm vi người xem, dựng PDF qua document-service (DV-11-01) hoặc XLSX, hoàn tất ≤ 1 phút cho mẫu `BANG_DIEU_HANH`; có `Idempotency-Key`.
- Kiểm `remindTask` và `reassignTask` của MOD-02 đáp ứng BR-16-07 (OPA kiểm quyền người bấm, phát EVT-21, trả nội dung MSG-16-02, MSG-16-03); nếu hai operation chưa được hiện thực ở thẻ CORE-02 nào thì hiện thực tại `backend/mission-core/.../task/` trong task này và ghi rõ trong báo cáo.
- Điểm lệch cần lưu ý: khung đặt API nhắc, giao lại của MOD-02 trong thẻ của SVC-15; theo ranh giới module, mã thuộc `task` của mission-core, nên task chỉ sửa thư mục module `task` cho đúng hai operation này.

## 4. Ngoài phạm vi

- Tóm tắt tuần bằng AI (DV-14); màn hình SCR-1603 (WEB-072); ghi sổ cái truy cập minh chứng (DV-09, evidence-service).

## 5. Tệp được phép sửa

- `backend/services/analytics-api/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**` (chỉ `remindTask`, `reassignTask` nếu chưa có) và kiểm thử tương ứng

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/analytics-api -am verify` và (nếu sửa MOD-02) `mvn -q -pl mission-core -am verify` xanh; so khớp phản hồi với `platform.yaml`, `tasks.yaml`.
- Kiểm thử: người xem ngoài phạm vi minh chứng nhận mắt xích `masked = true` và vẫn thấy mắt xích căn cứ; phản hồi chuỗi không chứa trường C2 nào lấy từ kho phân tích; xuất chuỗi trả 202, tác vụ hoàn tất và có sự kiện `sys.audit.v1`.
- Báo cáo `BANG_DIEU_HANH` hoàn tất ≤ 1 phút trên dữ liệu mẫu (ghi số đo); nhắc bởi người không có quyền với nhiệm vụ trả 403.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
