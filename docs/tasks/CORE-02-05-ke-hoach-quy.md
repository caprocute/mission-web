# CORE-02-05 · QT-04/SCR-0402 Kế hoạch quý: máy trạng thái phiên bản, soạn, sao chép, gợi ý từ OKR

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-03, CORE-01-02; cần CORE-08-03 (chặn ghi kỳ đã khoá) (bổ sung) |
| Truy vết | QT-04 → SCR-0402 → API `GET/POST /api/v1/plans`, `GET /plans/{id}/versions`, `GET/PUT /plans/{id}/versions/{v}`, `POST /plans/{id}/versions/{v}/copy-from`, `GET /plans/{id}/suggestions`, `GET /plans/items` → MOD-02, MOD-01 (`OkrApi`) → bảng `task.plan`, `task.plan_version`, `task.plan_item`, `task.catalog_item` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho quản lý (AC-05) lập kế hoạch công tác quý theo Bảng 1: tạo kế hoạch cùng phiên bản 1 ở `NHAP`, soạn dòng từ danh mục hoặc ngoài danh mục, sao chép việc thường xuyên và việc chưa xong của quý trước, nhận gợi ý một dòng cho mỗi KR đã duyệt chưa có nhiệm vụ. Máy trạng thái `task.plan_version` được hiện thực trong domain để các task gửi duyệt, duyệt, điều chỉnh dùng chung.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Mô tả các bước" (L1178–1194, tạo phiên bản 1 ở bước 2), "Trạng thái đối tượng" (L1195–1209), SCR-0402 (L1291–1341, API L1325–1331).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.plan", "Bảng task.plan_version", "Bảng task.plan_item".
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1 (trạng thái kế hoạch).
- `contracts/openapi/modules/tasks.yaml`: `listPlans`, `createPlan`, `listPlanVersions`, `getPlanVersion`, `savePlanVersion`, `copyPlanFromPreviousPeriod`, `suggestPlanItemsFromOkr`, `listApprovedPlanItems`.

## 3. Phạm vi công việc

- Viết domain `PlanVersion` với máy trạng thái thuần: (chưa có) → `NHAP` → `CHO_DUYET` → `TRA_LAI` → `CHO_DUYET` → `DA_DUYET`; chỉ `NHAP` và `TRA_LAI` sửa được; `DA_DUYET` bất biến; phiên bản hiệu lực là phiên bản `DA_DUYET` có số lớn nhất (`is_current`); mã từ chối dùng chung kiểu của máy trạng thái nhiệm vụ (TRANSITION_NOT_ALLOWED, PERIOD_LOCKED).
- Hiện thực `createPlan` (một kế hoạch tập thể mỗi đơn vị mỗi kỳ, hoặc cá nhân, đúng UQ NULLS NOT DISTINCT) tạo luôn phiên bản 1 `NHAP`; trùng trả 409.
- Hiện thực `savePlanVersion` (If-Match, Idempotency-Key): ghi đè danh sách dòng của phiên bản (`item_no` tuần tự), lấy điểm, mức khó, đơn vị tính từ `catalog_item` khi dòng thuộc danh mục; dòng ngoài danh mục để trống điểm (chấm ở bước duyệt, BR-04-05).
- Hiện thực `copyPlanFromPreviousPeriod`: sao chép dòng nhóm thường xuyên và dòng chưa hoàn thành của phiên bản hiệu lực quý trước, dịch hạn sang kỳ hiện tại theo cùng độ lệch ngày trong quý (cách dịch là suy luận, ghi rõ), đặt `origin_item_id`.
- Hiện thực `suggestPlanItemsFromOkr` qua `OkrApi`: mỗi KR thuộc mục tiêu `DA_DUYET` của đơn vị trong kỳ mà chưa có dòng kế hoạch hoặc nhiệm vụ liên kết trả một dòng gợi ý có `key_result_id`.
- Hiện thực `listPlans`, `listPlanVersions`, `getPlanVersion` (kèm `ETag`) và `listApprovedPlanItems` (chỉ dòng của phiên bản `DA_DUYET` hiệu lực, dùng cho giao việc BR-05-04).
- Gợi ý tách: (a) domain máy trạng thái, `createPlan`, `savePlanVersion`, các API đọc; (b) sao chép quý trước và gợi ý từ OKR.

## 4. Ngoài phạm vi

- Bộ kiểm tra BR-04-02..07 (CORE-02-06); gửi duyệt, trả lại (CORE-02-08); duyệt ký số (CORE-02-09); điều chỉnh và so sánh (CORE-02-10).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh; ArchUnit xác nhận `task` chỉ dùng `okr.api`.
- Kiểm thử đơn vị máy trạng thái phủ mọi cặp (trạng thái, lệnh) hợp lệ và không hợp lệ; độ phủ lớp `domain` ≥ 80%.
- Kiểm thử tích hợp: tạo kế hoạch hai lần cùng đơn vị, kỳ trả 409; lưu phiên bản `DA_DUYET` trả 409; sao chép quý trước chỉ lấy dòng thường xuyên và dòng chưa xong; gợi ý trả đúng số KR chưa có nhiệm vụ.
- Lưu một phiên bản 300 dòng phản hồi p95 ≤ 500 ms trên Testcontainers (suy luận).
- Kiểm thử hợp đồng cho tám operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
