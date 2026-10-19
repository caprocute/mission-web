# CORE-02-04 · QT-04/SCR-0401 Nhập danh mục công việc từ Excel

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-03 |
| Truy vết | QT-04 → SCR-0401 → API `POST /api/v1/catalog/{catalogId}/imports` (202), `GET /catalog/{catalogId}/imports/latest`, `GET /catalog/imports/{jobId}`, `GET /catalog/imports/{jobId}/errors` → MOD-02 → bảng `task.work_catalog`, `task.catalog_item` → MSG-04-02 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho quản lý (AC-05) và người soạn chính sách nhập hàng trăm mục danh mục từ tệp Excel theo mẫu Bảng 3 bằng tác vụ dài 202: dòng hợp lệ được nhập, dòng lỗi được trả trong một tệp lỗi có cột lý do để sửa và nhập lại. Đây là đường đưa dữ liệu danh mục hiện có của đơn vị vào hệ thống nhanh nhất.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Ngoại lệ và xử lý" (nhập phần hợp lệ, trả tệp lỗi, L1241), SCR-0401 (API nhập L1280–1284), MSG-04-02.
- `contracts/openapi/modules/tasks.yaml`: `importCatalogExcel`, `getLatestCatalogImport`, `getCatalogImport`, `downloadCatalogImportErrors` và lược đồ kết quả nhập (`DANG_XU_LY`, `XONG`, `LOI`; loại lỗi `THIEU`, `MO_HO`, `KHAC`); `contracts/openapi/modules/products.yaml` nhóm `/files/*`.
- `wiki/06-quy-uoc-api.md` (tác vụ 202).

## 3. Phạm vi công việc

- Hiện thực `importCatalogExcel` nhận tệp `multipart/form-data` đúng hợp đồng, trả 202 + `Location` trỏ tới `/catalog/imports/{jobId}`; lưu tệp gốc qua SVC-08 nếu đã sẵn sàng (suy luận, để truy vết), rồi xử lý nền đọc tệp theo luồng (không nạp toàn bộ vào bộ nhớ), giới hạn kích thước và số dòng theo hợp đồng.
- Mỗi dòng đi qua đúng bộ kiểm tra của `addCatalogItem` (CORE-02-03): thiếu trường bắt buộc (`THIEU`), tên mơ hồ (`MO_HO`), mức khó hoặc điểm ngoài khoảng, mã trùng (`KHAC`); dòng hợp lệ ghi theo lô, mỗi lô một giao dịch.
- Ghi kết quả vào tác vụ: số dòng nhập, số dòng lỗi, liên kết tải tệp lỗi; `downloadCatalogImportErrors` trả tệp Excel gồm các dòng lỗi và cột lý do bằng tiếng Việt.
- Hiện thực `getLatestCatalogImport` và `getCatalogImport` để màn hình hiện lần nhập gần nhất và tiến độ.
- Chống nhập trùng: cùng `Idempotency-Key` không chạy lại tác vụ; tệp đã nhập xong lần hai chỉ thêm mục chưa có (mã đã có báo lỗi trùng).
- Thư viện đọc ghi Excel chưa có trong danh sách stack của `docs/quy-dinh/BACKEND.md`: chọn thư viện mã nguồn mở phổ biến, nêu lý do trong PR và xin người phụ trách duyệt trước khi thêm.

## 4. Ngoài phạm vi

- Quét mã độc và lưu tệp (SVC-08); giao diện tải mẫu và tải lên (task WEB).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/pom.xml` (chỉ thêm thư viện Excel đã được duyệt)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử với tệp mẫu 1.000 dòng có 37 dòng lỗi: 963 mục được tạo, tệp lỗi có đúng 37 dòng với lý do; tác vụ hoàn tất dưới 30 giây trên Testcontainers (suy luận).
- Tệp sai định dạng trả lỗi RFC 9457 rõ nghĩa trong kết quả tác vụ, không để tác vụ treo `DANG_XU_LY`.
- Kiểm thử hợp đồng cho bốn operationId xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
