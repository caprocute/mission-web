# DV-15-02 · SVC-15 Connector Debezium phân tích và giả danh hoá cột C2

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-01, INF-10; NEN-030 (COMMENT phân loại cột bắt buộc) đã gộp (bổ sung) |
| Truy vết | QT-14 (BR-14-06), QT-16 (BR-16-04) → SVC-15 → publication `pub_analytics`, slot `dbz_cdc_<db>`, topic `cdc.<db>.<schema>.<table>` → nguồn các schema `okr`, `task`, `product`, `appraisal`, `scoring`, `evidence`, `config`, `org_replica` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cấu hình connector Debezium thứ hai cho mỗi CSDL (tách khỏi connector outbox) đẩy thay đổi bảng sang topic `cdc.*`, đồng thời loại bỏ cột C2/C3 và băm có muối theo tenant mã người, với danh sách cột sinh tự động từ COMMENT phân loại bằng công cụ `cdc-config-gen`. Khi xong, không cột C2 dạng rõ hay C3 nào rời PostgreSQL theo đường phân tích, và thêm cột mới không cần sửa tay cấu hình CDC.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "CDC phân tích sang ClickHouse" (L4313, "Đường đi dữ liệu": slot, publication, `ExtractNewRecordState`, `column.exclude.list`, `column.mask.hash.SHA-256.with.salt`), "Bảng tổng hợp xử lý theo phân loại" (L4172), "Transactional Outbox và Debezium" (L4205, để không trùng cấu hình).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` đoạn cấu hình connector Debezium (L780–806: mỗi CSDL một connector outbox và một connector phân tích) và mục "SVC-15 analytics" (L1194–1204).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14 "Quy tắc nghiệp vụ" (L1202, BR-14-06), QT-16 (L1655, BR-16-04).
- Bản trích `extract/tl03-phan2.md` mục B4, B7, F.1.

## 3. Phạm vi công việc

- Viết công cụ `cdc-config-gen` (Python trong `analytics/cdc/`) đọc `pg_description` của các schema nguồn, sinh cho mỗi CSDL: `table.include.list`, `column.exclude.list` (cột C2 không có cách giả danh và mọi cột C3, cột `_enc`), `column.mask.hash.SHA-256.with.salt.<muối_tenant>` cho cột mã người; lỗi khi gặp cột thiếu COMMENT `cls`.
- Viết mẫu cấu hình KafkaConnector (Strimzi) cho connector phân tích: `pgoutput`, slot `dbz_cdc_<db>`, publication `pub_analytics`, topic `cdc.<db>.<schema>.<table>`, transform `ExtractNewRecordState` thêm `__op`, `__lsn`, `__source_ts_ms`, `__deleted`; muối lấy từ OpenBao qua cấu hình bí mật.
- Viết lệnh tạo publication `pub_analytics` trong migration hoặc script quản trị cho từng CSDL (phối hợp chủ sở hữu schema; không sửa migration đã gộp, chỉ thêm mới).
- Thêm bước CI chạy `cdc-config-gen` và so sánh với cấu hình đã commit; khác nhau thì thất bại.
- Mỗi CSDL có đúng một connector phân tích với slot riêng (TL-01 L806, TL-03 L4253); câu "mỗi schema có connector và slot riêng" mà TL-03 dẫn lại từ TL-01 bản cũ không còn áp dụng, task ghi việc này vào `wiki/14` để tránh hiểu nhầm.

## 4. Ngoài phạm vi

- Connector outbox (INF-10, NEN-011); bảng đích ClickHouse (DV-15-01); giám sát độ trễ slot (INF-15).

## 5. Tệp được phép sửa

- `analytics/cdc/**`
- `deploy/charts/` chỉ thư mục chart Kafka Connect nếu INF-10 đặt connector ở đó (ghi rõ đường dẫn trong báo cáo)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd analytics/cdc && pytest` xanh; kiểm thử tích hợp Testcontainers PostgreSQL 17 + Kafka Connect Debezium: ghi một dòng có cột C2 và mã người, thông điệp trên topic `cdc.*` không có cột C2 và mã người là băm 32 byte khác nhau giữa hai tenant cho cùng giá trị.
- Cột mới thiếu COMMENT làm `cdc-config-gen` thất bại; cấu hình sinh lại trùng với tệp đã commit.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
