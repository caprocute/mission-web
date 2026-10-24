# NEN-021 · ADR-03/SVC-08 Lược đồ Avro cho sự kiện kỹ thuật `evidence.object.*` và các topic kỹ thuật còn thiếu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-021` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B1 |
| Cỡ việc | S |
| Phụ thuộc | NEN-007 |
| Truy vết | ADR-03 → TL-03 "Lược đồ sự kiện" (topic kỹ thuật L4470, cấu hình topic L4472–4486) → SVC-08 phát `evidence.object.stored.v1`, `evidence.object.scanned.v1`, MOD-03 tiêu thụ → SVC-02 `iam.auth.event.v1`/`iam.audit.v1` → SVC-15 `agg.*` → `contracts/events/catalog.yaml`, `contracts/events/avro/**` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

NEN-007 viết lược đồ cho 26 sự kiện nghiệp vụ và tám topic kỹ thuật đã liệt kê, nhưng khi viết thẻ DV-08 và DV-02 lộ ra các sự kiện kỹ thuật khác đã có người phát, người nhận mà chưa có lược đồ, cùng chỗ tên topic lệch nhau giữa TL-01, TL-03 và TL-04. Task này chốt tên và viết lược đồ Avro cho phần còn thiếu, để evidence-service, MOD-03 và Keycloak listener có hợp đồng sự kiện trước khi viết mã (DV-08-02 đang ghi "nếu chưa có lược đồ thì dừng").

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Lược đồ sự kiện" (L4406–4470, đoạn topic kỹ thuật L4470), bảng MOD-03 (L400: nhận `evidence.object.scanned.v1`), bảng SVC-08 (L519: phát `evidence.object.scanned.v1`), bảng SVC-02 (L322–323: phát `iam.audit.v1`), "Giữa các cell" (L4398: `agg.*` đã ẩn danh).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: SVC-08 (L1106: `evidence.object.stored.v1` qua outbox), mục AI (L3094: `sys.evidence.object.stored.v1`), SVC-02 (L997: `iam.auth.event.v1`), "Quy ước topic" (L1279–1283).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng cấu hình topic (L760–768: nhóm "Hệ thống" có `sys.evidence.object.stored.v1`, `iam.auth.event.v1`).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-03.md` mục 6 "Sự kiện và topic".
- Kết quả NEN-007: `contracts/events/catalog.yaml` (phần `technicalTopics`), `contracts/events/README.md`; thẻ `docs/tasks/DV-08-02-*.md`, `DV-08-04-*.md`, `DV-08-05-*.md`, `DV-02-05-*.md`.

## 3. Phạm vi công việc

- Chốt tên topic hoàn tất tải lên: Điểm lệch cần lưu ý: TL-01 SVC-08 ghi `evidence.object.stored.v1`, TL-01 mục AI, TL-03 L4470 và TL-04 ghi `sys.evidence.object.stored.v1`. Chọn `sys.evidence.object.stored.v1` (ba nơi, gồm TL-03 là nguồn chi tiết và TL-04 là nơi định cỡ topic), sửa dòng tương ứng trong `catalog.yaml` nếu NEN-007 đã ghi khác, ghi vào `wiki/14` và báo DV-08-02 dùng tên này.
- Viết lược đồ `ObjectStored` (`objectId`, `version`, `tenantId` chỉ ở header, `sha256`, `sizeBytes`, `contentType`, `bucketRef`, `e2ee`, `retentionUntil`, `uploadedBy`, `ownerRef` loại và mã đối tượng nghiệp vụ) và `ObjectScanned` cho `evidence.object.scanned.v1` (`objectId`, `version`, `verdict` theo CHECK của `evidence.scan_result`, `engine`, `engineVersion`, `scannedAt`); trường lấy theo cột TL-03 schema `evidence`, trường không có cột tương ứng ghi `"doc": "(suy luận) ..."`.
- Xử lý tên topic sự kiện xác thực: Điểm lệch cần lưu ý: TL-01 và TL-04 ghi `iam.auth.event.v1`, bảng SVC-02 của TL-03 ghi `iam.audit.v1`. Giữ `iam.auth.event.v1` (TL-01, TL-04, NEN-007, DV-02-05 cùng dùng), ghi `iam.audit.v1` là tên lệch trong `wiki/14`, không tạo topic thứ hai.
- Viết lược đồ khung cho họ topic `agg.*` (tổng hợp theo đơn vị cấp tỉnh, không có mã người): một lược đồ dùng chung `AggregateMetric` (`cellId`, `provinceUnitRef`, `periodId`, `metricKey`, `value`, `count`, `computedAt`) đánh dấu "(suy luận)" vì TL không liệt kê trường; ghi rõ cần người phụ trách SVC-15 duyệt.
- Ghi vào `catalog.yaml` (phần `technicalTopics`) cho mỗi topic: tên, khoá, bên phát, bên nhận, phân loại (C0/C1), số phân vùng và thời gian giữ theo TL-04 L760–768; ghi chú họ `cdc.<db>.<schema>.<table>` do Debezium sinh lược đồ, `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>`, `<topic>.replay.<consumer>` dùng lại lược đồ của topic gốc nên không viết lược đồ riêng.
- Không trường nào mang dữ liệu C2, C3 (tên tệp gốc không đưa vào sự kiện vì có thể chứa thông tin cá nhân, suy luận); mở rộng kiểm thử JUnit của NEN-007 để đọc thêm các lược đồ mới và đối chiếu `catalog.yaml`.
- Chạy kiểm tương thích Apicurio `BACKWARD_TRANSITIVE` của NEN-007 cho các lược đồ mới.

## 4. Ngoài phạm vi

- Phát và tiêu thụ sự kiện (DV-08-02, DV-08-04, DV-08-05, CORE-03); khai báo topic dạng mã trên Strimzi (INF-10); AsyncAPI cho topic công bố (NEN-008).
- Sự kiện nghiệp vụ EVT mới: không tạo mã EVT.

## 5. Tệp được phép sửa

- `contracts/events/catalog.yaml`, `contracts/events/avro/**`, `contracts/events/README.md`
- `backend/libs/vt-events/src/test/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục topic kỹ thuật)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-events -am verify` và `./mvnw -q -pl libs/vt-events -Pschema-compat verify` chạy xanh; lớp Java sinh cho lược đồ mới biên dịch được.
- Kiểm thử đối chiếu: mọi topic trong `technicalTopics` có lược đồ hoặc ghi chú "dùng lược đồ topic gốc/Debezium"; không có tên topic nào ngoài danh sách đã chốt.
- Bảng chốt tên (`sys.evidence.object.stored.v1`, `evidence.object.scanned.v1`, `iam.auth.event.v1`) có trong `wiki/14` và báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
