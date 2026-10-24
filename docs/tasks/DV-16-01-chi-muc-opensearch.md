# DV-16-01 · SVC-16/ADR-15 Chỉ mục OpenSearch, bí danh lọc theo tenant, indexer tiêu thụ EVT-04, EVT-06, EVT-24

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-16-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | INF-11, NEN-012; NEN-007 (lược đồ Avro sự kiện) (bổ sung) |
| Truy vết | QT-00, QT-02 (nguồn nhiệm vụ), QT-05 (văn bản chỉ đạo) → SCR-0002 (ô tìm kiếm) → SVC-16 indexer → chỉ mục `t_<tenant>_task_v<n>` (silo, bridge), `pool_task_v<n>` (pool) → tiêu thụ EVT-04 NhiemVuDaGiao, EVT-06 TrangThaiNhiemVuDoi, EVT-24 VanBanChiDaoDaNhan và sự kiện danh mục → ADR-15 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng indexer của SVC-16: consumer Kafka dựng tài liệu tìm kiếm từ payload sự kiện (chỉ trường C0, C1, không đọc CSDL), ghi theo lô vào OpenSearch với phiên bản ngoài để sự kiện đến muộn không ghi đè tài liệu mới hơn, phân tích tiếng Việt không dấu, và cách ly tenant bằng chỉ mục riêng (silo, bridge) hoặc bí danh lọc `tenant_id` (pool). Khi xong, thay đổi nhiệm vụ tìm được trong ≤ 10 giây ở p99.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Đánh chỉ mục tìm kiếm OpenSearch" (L4351: `_bulk`, `version_type=external`, `version = aggregateVersion`, tên chỉ mục, `_routing`, `icu_tokenizer` + `icu_folding`, từ đồng nghĩa, dựng lại bằng hoán bí danh).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "SVC-16 search" (L1206), ADR-15 (L3532).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 danh mục EVT (L140 EVT-04, L142 EVT-06, L160 EVT-24).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Định cỡ Kafka, Valkey, OpenSearch, ClickHouse" (L753, đoạn OpenSearch L773).
- `contracts/openapi/modules/platform.yaml` schema `SearchResult`, `SearchResultType` (`TASK`, `SOURCE_DOC`, `CATALOG_ITEM`) để biết trường cần lập chỉ mục.

## 3. Phạm vi công việc

- Tạo `backend/services/search/` (Java 21, Spring Boot, suy luận theo nhóm dịch vụ Java của baseline; nếu baseline ghi ngôn ngữ khác thì theo baseline) với consumer dùng thư viện nền NEN-012 (inbox idempotent, thử lại, DLQ) cho EVT-04, EVT-06, EVT-24 và sự kiện danh mục.
- Dựng tài liệu từ payload: lọc trường theo phân loại (chỉ C0, C1), chuẩn hoá tiếng Việt; ghi `_bulk` theo lô với `version_type=external`, `version = aggregateVersion`.
- Mẫu chỉ mục (index template) cho `t_<tenant>_task_v<n>` và `pool_task_v<n>`: bộ phân tích `icu_tokenizer` + `icu_folding` + từ điển đồng nghĩa nghiệp vụ (KPI, chỉ tiêu; nhiệm vụ, công việc) lưu trong `backend/services/search/src/main/resources/opensearch/`.
- Ở cell pool: `_routing` theo `tenant_id` và một bí danh có bộ lọc `tenant_id` cho mỗi tenant, tạo khi nhận `sys.tenant.provisioned.v1`; ở silo, bridge: chỉ mục riêng mỗi tenant.
- Lệnh dựng lại chỉ mục: tạo chỉ mục `v<n+1>`, nạp lại từ dòng sự kiện (phát lại topic từ đầu kỳ lưu giữ hoặc ảnh chụp do chủ dữ liệu cung cấp, suy luận), hoán đổi bí danh nguyên tử.
- Đo độ trễ từ thời điểm sự kiện tới khi tìm được, xuất metric OpenTelemetry; mục tiêu p99 ≤ 10 giây.
- Điểm lệch cần lưu ý: bảng EVT của baseline không liệt kê SVC-16 là bên tiêu thụ EVT-06 và EVT-24, trong khi TL-01 L1206 và TL-03 L4351 ghi indexer tiêu thụ chúng; task theo TL-03 (chi tiết hơn) và ghi vào `wiki/14` để cập nhật danh mục bên tiêu thụ.

## 4. Ngoài phạm vi

- API tìm kiếm (DV-16-02); cài đặt cụm OpenSearch (INF-11); tìm kiếm minh chứng hay nội dung C2 (không có trong thiết kế).

## 5. Tệp được phép sửa

- `backend/services/search/**`
- `backend/pom.xml` (chỉ thêm module)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/search -am verify` xanh: Testcontainers OpenSearch 2.x + Kafka, ArchUnit.
- Kiểm thử tích hợp: tìm "giao duc" khớp "giáo dục"; sự kiện phiên bản cũ đến sau không ghi đè; tenant pool A qua bí danh của mình không thấy tài liệu tenant B; tài liệu không chứa trường C2.
- Dựng lại chỉ mục và hoán bí danh không làm truy vấn đang chạy lỗi; độ trễ p99 ≤ 10 giây ở tải thử cục bộ (ghi số đo).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
