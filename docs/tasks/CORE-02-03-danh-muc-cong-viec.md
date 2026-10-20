# CORE-02-03 · QT-04/SCR-0401 Danh mục công việc có phiên bản, kế thừa cấp trên

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-01, CORE-08-03; cần CORE-05-03 (hệ số mức khó trong Policy Pack) (bổ sung) |
| Truy vết | QT-04 → SCR-0401 → API `GET /api/v1/catalog`, `GET /catalog/items`, `GET/POST /catalog/{catalogId}/items`, `PUT /catalog/{catalogId}/items/{itemId}`, `POST /catalog/{catalogId}/versions`, `GET /catalog/{catalogId}/positions` → MOD-02 → bảng `task.work_catalog`, `task.catalog_item` → BR-04-13, BR-04-14; MSG-04-01 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực thư viện danh mục công việc theo Bảng 3: danh mục theo vị trí việc làm và đơn vị, có phiên bản, kế thừa danh mục mẫu của cấp trên, mỗi kỳ áp dụng đúng một phiên bản. Mục danh mục đã dùng ở kỳ đã khoá không sửa tại chỗ mà buộc tạo phiên bản mới, để điểm của kỳ cũ không bị thay đổi ngầm. Đây cũng là chức năng chuyển đầu tiên theo Strangler Fig, nên phải ổn định sớm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Quy tắc nghiệp vụ" BR-04-13, BR-04-14 (L1211–1230), SCR-0401 (L1245–1290, ràng buộc trường L1259–1276, API L1280–1284).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.work_catalog", "Bảng task.catalog_item".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục Strangler Fig với APISIX (ADR-20, L1428–1459).
- `contracts/openapi/modules/tasks.yaml`: `listWorkCatalogs`, `listApplicableCatalogItems`, `listCatalogVersionItems`, `addCatalogItem`, `updateCatalogItem`, `cloneCatalogVersion`, `listCatalogPositions`.

## 3. Phạm vi công việc

- Sinh interface controller từ `tasks.yaml` cho nhóm `/catalog` và viết hiện thực, entity, repository trong `task`; danh sách dùng con trỏ, đọc một đối tượng trả `ETag`.
- Hiện thực `addCatalogItem` và `updateCatalogItem` (If-Match, Idempotency-Key): mã `TC-xx-nn` duy nhất trong danh mục, tên không chứa từ mơ hồ (danh sách từ Policy Pack, dùng chung bộ của CORE-01-04), bắt buộc sản phẩm, đơn vị tính, tiêu chuẩn chất lượng, minh chứng yêu cầu; mức khó 1–5 và điểm chuẩn 0–100 (BR-04-14).
- Kiểm BR-04-13: mục đã được dùng bởi nhiệm vụ hoặc dòng kế hoạch thuộc kỳ đã khoá thì `updateCatalogItem` trả 409 kèm MSG-04-01 và gợi ý nhân bản phiên bản; `cloneCatalogVersion` tạo phiên bản n+1 ở `NHAP` sao chép toàn bộ mục.
- Kế thừa: danh mục có `parent_catalog_id` thấy các mục của danh mục mẫu cấp trên (chỉ đọc) cộng mục riêng; chặn tạo vòng kế thừa; `listApplicableCatalogItems` trả mục của phiên bản áp dụng cho kỳ và vị trí của người gọi.
- Gợi ý điểm chuẩn theo hệ số mức khó trong Policy Pack, chỉ là gợi ý khi nhập, không nhân hệ số khi tính điểm (L1259).
- Ánh xạ trạng thái giữa CSDL và hợp đồng theo quyết định ghi ở CORE-02-01 (`HIEU_LUC` ↔ `AP_DUNG`, `NGUNG` ↔ `HET_HIEU_LUC`) trong một lớp mapper duy nhất, có kiểm thử.
- Điểm lệch cần lưu ý: hợp đồng không có thao tác chuyển một phiên bản danh mục từ `NHAP` sang áp dụng; task ghi đề nghị bổ sung thao tác vào báo cáo và tạm cho phiên bản áp dụng qua dữ liệu seed hoặc nhập Excel (CORE-02-04), không tự mở endpoint ngoài hợp đồng.

## 4. Ngoài phạm vi

- Nhập Excel (CORE-02-04); chương trình, đề án (`task.program`) chưa có SCR riêng trong phạm vi này.
- Tuyến APISIX cho Strangler (task hạ tầng).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/pom.xml` (chỉ khai báo sinh mã từ `tasks.yaml` nếu chưa có)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm ArchUnit và `ApplicationModules.verify()`.
- Kiểm thử: sửa mục đã dùng ở kỳ `DA_KHOA` trả 409 kèm MSG-04-01; nhân bản tạo phiên bản mới với đủ mục và mã giữ nguyên; mức khó 0 hoặc điểm 101 trả 422; tạo kế thừa vòng bị chặn.
- Kiểm thử hợp đồng cho bảy operationId với `tasks.yaml` xanh; `openapi-diff` không báo phá tương thích.
- Báo cáo cuối task theo AGENTS.md mục 6, nêu đề nghị bổ sung thao tác áp dụng phiên bản danh mục.

## 7. Báo cáo

Theo AGENTS.md mục 6.
