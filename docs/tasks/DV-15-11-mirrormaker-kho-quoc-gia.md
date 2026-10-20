# DV-15-11 · SVC-15 MirrorMaker 2 đẩy dữ liệu tổng hợp lên kho phân tích quốc gia

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-01, INF-10 |
| Truy vết | Baseline mục 8 cơ chế 7 (giữa các cell chỉ đẩy sự kiện tổng hợp, ẩn danh lên kho phân tích quốc gia) → SVC-15 → ClickHouse `agg_*` của cell → topic `agg.*` → Kafka kho phân tích quốc gia → ADR-14 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Sinh dữ liệu tổng hợp cấp tỉnh (không có mã người) từ ClickHouse của mỗi cell lên topic `agg.*`, và cấu hình Strimzi `KafkaMirrorMaker2` sao chép riêng các topic `agg.*` sang Kafka của kho phân tích quốc gia. Khi xong, kho quốc gia nhận số liệu tổng hợp chậm vài phút mà không topic nghiệp vụ chi tiết nào rời cell.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 8, cơ chế 7 (L172: chỉ đồng bộ danh bạ tenant, MirrorMaker 2 sự kiện tổng hợp/ẩn danh, gói chuyển hồ sơ).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` đoạn "phân tích quốc gia" (L4398: `agg.*` sinh từ ClickHouse của cell bằng job tổng hợp theo đơn vị cấp tỉnh, không có mã người), danh sách topic kỹ thuật (L4470).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` vùng toàn cục (L335: kho phân tích quốc gia nhận qua MirrorMaker 2).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Kafka: MirrorMaker 2" (L2114: `IdentityReplicationPolicy` cho DR, để phân biệt với luồng phân tích).
- Bản trích `extract/tl01-thanh-phan.md` mục C14.6.

## 3. Phạm vi công việc

- Viết job tổng hợp (SQL ClickHouse chạy định kỳ, suy luận mỗi giờ) đọc bảng `agg_*` của DV-15-01, xuất bản ghi theo đơn vị cấp tỉnh, kỳ, khối, luồng ra topic `agg.<chỉ_số>.v1` (đặt tên trong họ `agg.*` theo TL-03, tên cụ thể là suy luận) bằng Kafka engine hoặc producer nhỏ.
- Áp ngưỡng che nhóm nhỏ: ô tổng hợp có số người dưới ngưỡng (suy luận k ≥ 10) bị gộp hoặc bỏ để không suy ngược được cá nhân; ghi ngưỡng vào wiki.
- Viết manifest `KafkaMirrorMaker2` cho luồng phân tích: nguồn là Kafka của cell, đích là Kafka kho quốc gia, `topicsPattern: "agg\\..*"`, không sao chép offset nhóm tiêu thụ nghiệp vụ, mTLS hai chiều; tách biệt với luồng MirrorMaker 2 phục vụ DR của TL-04.
- Viết lược đồ Avro cho các topic `agg.*` trong `contracts/events/avro/` và đăng ký tương thích BACKWARD.
- Viết kiểm thử chặn: lược đồ `agg.*` không chứa trường nào có tên hay phân loại mã người (`person_key`, `user_id`…) hay C2.

## 4. Ngoài phạm vi

- Dựng kho phân tích quốc gia và cụm Kafka đích (ngoài phạm vi kiến trúc cell, cần bên vận hành quốc gia); luồng MirrorMaker 2 DR (INF, VH).

## 5. Tệp được phép sửa

- `analytics/clickhouse/**` (chỉ job tổng hợp `agg`)
- `analytics/mirrormaker/**`
- `contracts/events/avro/` (chỉ lược đồ `agg.*`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- Kiểm thử tích hợp (Docker Compose cục bộ: ClickHouse + hai cụm Kafka + MirrorMaker 2): bản ghi tổng hợp xuất hiện ở cụm đích trong vài phút; topic `cdc.*` và topic nghiệp vụ không xuất hiện ở cụm đích.
- `kubeconform` hoặc `kubectl apply --dry-run=server` hợp lệ cho manifest `KafkaMirrorMaker2`.
- Kiểm thử lược đồ: không trường mã người; ô dưới ngưỡng che nhóm nhỏ bị loại.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
