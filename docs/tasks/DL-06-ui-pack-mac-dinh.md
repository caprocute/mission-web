# DL-06 · QT-18/NFR-11 UI Pack mặc định, bộ token người cao tuổi, danh mục công việc mẫu theo khối

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-06` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | CORE-08-11 (bổ sung: CORE-02-04 cho phần nhập danh mục công việc) |
| Truy vết | QT-18 (BR-18-01, BR-18-02, BR-18-08, BR-18-09), QT-04 (Bảng 3 danh mục công việc), NFR-07, NFR-11 → API `PUT /api/v1/ui/packs/{id}/versions/{v}` (`updateUiPackVersion`), `POST .../submit` (`submitUiPackVersion`), `POST .../publish` (`publishUiPackVersion`), `GET /api/v1/ui/packs/current` (`getCurrentUiPack`), `POST /api/v1/catalog/{catalogId}/imports` (`importCatalogExcel`) → MOD-08, MOD-02 → bảng `config.ui_pack`, `config.ui_pack_version` (`tokens_json`, `layout_json`, `elder_mode`, `few_steps`), `task.work_catalog`, `task.catalog_item` → EVT-23 |
| Trạng thái | Chưa bắt đầu (danh mục công việc mẫu theo khối cần nghiệp vụ rà trước khi dùng cho tenant thật) |

## 1. Mục tiêu

Mỗi tenant mới có sẵn một UI Pack mặc định đã qua kiểm tra tự động của QT-18 (tương phản, kích thước, registry), trong đó có bộ token phụ "elder" cho chế độ Đảng viên cao tuổi, và có danh mục công việc mẫu theo khối để cơ quan tổ chức sửa thay vì nhập từ đầu. Điều này giúp đưa tenant mới vào vận hành ≤ 1 ngày (NFR-07) và bảo đảm chế độ cao tuổi chạy giống nhau trên web và di động (NFR-11).

## 2. Đầu vào bắt buộc đọc

- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-18 "Quy tắc nghiệp vụ" (BR-18-01..BR-18-12), "Trạng thái đối tượng" (`ui_pack_version`), đặc tả SCR-1801 và SCR-1803 (chú thích token "elder" và cờ "ít bước").
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, mục "Chế độ chữ lớn và người dùng cao tuổi"; QT-04 (Bảng 3 danh mục công việc).
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "Quản trị phiên bản UI Pack và Policy Pack", "Kiểm thử hồi quy giao diện theo tenant".
- TL-03 mục "Bảng config.ui_pack và config.ui_pack_version"; mục "MOD-02 task" (`work_catalog`, `catalog_item`).
- Hợp đồng `contracts/openapi/modules/config.yaml` (nhóm `/ui/*`), `contracts/openapi/modules/tasks.yaml` (nhóm `/catalog/*`).
- Mã hiện có: token của `frontend/projects/design-system` (preset Aura); `wiki/03-giao-dien-theo-tenant.md`, `wiki/09-hieu-nang-va-trai-nghiem.md`.

## 3. Phạm vi công việc

- Viết UI Pack mặc định dạng W3C Design Tokens khớp token hiện có của `@vt/design-system`, mọi cặp màu chữ/nền đạt tương phản ≥ 4,5 : 1 (BR-18-01).
- Viết bộ token phụ "elder": chữ cơ sở 17–19 px, vùng chạm ≥ 48 px, viền 2 px, tương phản ≥ 7 : 1 (BR-18-09); bật cờ `few_steps` ở mức tenant theo mặc định tắt.
- Viết `layout_json` trang chủ mặc định chỉ tham chiếu component có trong `config.component_registry`, tối đa 12 widget, không HTML hay JS (BR-18-02).
- Bảo đảm gói ≤ 30 KB gzip (BR-18-08) và ghi kích thước đo được vào `CHANGELOG`.
- Soạn danh mục công việc mẫu theo khối (K1..K9 theo PL 2A, dùng mã khối của DL-07) ở định dạng tệp Excel mà `importCatalogExcel` nhận; mỗi mục đánh dấu là mẫu, có nhóm thường xuyên/then chốt và đơn vị tính.
- Viết tập lệnh nạp mặc định cho tenant mới (gọi API, không ghi thẳng CSDL) và mô tả cách dùng trong `data/seed/README.md`.
- Ghi chú trong wiki trang 03 rằng di động (MOB-01, MOB-08) đọc cùng bộ token này.

## 4. Ngoài phạm vi

- Biên dịch token, ký, đẩy CDN, EVT-23 (CORE-08-11); trình soạn UI Pack trên web (WEB-050).
- Lưu tuỳ chọn hiển thị theo từng người ở máy chủ (đang chờ người dùng chốt theo nhóm cá nhân hoá).

## 5. Tệp được phép sửa

- `data/seed/ui-pack/**`
- `data/seed/catalog/**`
- `data/seed/README.md`
- `wiki/03-giao-dien-theo-tenant.md`

## 6. Tiêu chí hoàn thành

- Kiểm tra lược đồ token và bố cục bằng tập lệnh trong `data/seed/ui-pack/` chạy xanh; tương phản đo tự động đạt ngưỡng cho cả bộ thường và bộ "elder".
- Với CORE-08-11 đã gộp: trên Testcontainers, `submitUiPackVersion` cho trạng thái `CHO_DUYET` không lỗi kiểm tra, `publishUiPackVersion` phát EVT-23, `getCurrentUiPack` trả ETag.
- Nhập danh mục mẫu của một khối qua `importCatalogExcel` trả 202 và tệp lỗi rỗng.
- Kích thước UI Pack ≤ 30 KB gzip được ghi trong báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 03 được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
