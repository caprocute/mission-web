# NEN-007 · ADR-03 Lược đồ Avro cho EVT-01..EVT-26 và topic kỹ thuật, sinh lớp Java, kiểm tương thích

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-007` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-03 → baseline mục 7 (EVT-01..EVT-26) → TL-03 "Danh mục lược đồ EVT-01 đến EVT-26" → `<schema>.outbox_event.payload` → Apicurio Registry |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết hợp đồng sự kiện dạng mã: 26 lược đồ Avro cho EVT-01..EVT-26 và lược đồ cho các topic kỹ thuật, kèm một tệp danh mục máy đọc được (mã EVT, topic, khoá, outbox nguồn, phân loại, bên phát, bên nhận). Từ đó sinh lớp Java dùng chung cho mọi service Java, và CI kiểm tương thích lược đồ trước khi gộp. Khi xong, NEN-011 (outbox) và NEN-012 (consumer) có kiểu dữ liệu thật để tuần tự hoá, NEN-008 có nguồn sinh AsyncAPI, INF-10 có danh sách topic để khai báo dạng mã.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 (bảng EVT, quy ước topic và trường bắt buộc).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Lược đồ sự kiện" (L4406–4475): "Phong bì sự kiện" (mẫu Avro EVT-05, quy tắc tiến hoá), "Danh mục lược đồ EVT-01 đến EVT-26" (L4435–4470), "Cấu hình topic".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Quy ước topic" (L1279–1283), "Phong bì sự kiện" (L1285–1315).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-03.md` L71–74 (EVT-26, topic kỹ thuật); `infra/dev/scripts/create-topics.sh` (26 topic hiện có ở dev).

## 3. Phạm vi công việc

- Tạo `contracts/events/catalog.yaml`: 26 dòng EVT với `code`, `name` (tên nghiệp vụ như `TienDoDaCapNhat`), `topic`, `key` (`tenant:<aggregateId>`), `outbox` (bảng outbox nguồn), `classification` (C0/C1), `producer`, `consumers`, lấy đúng bảng TL-03 L4439–4466 và baseline mục 7; thêm phần `technicalTopics` cho `sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`, `registry.tenant.changed.v1`, `org.bootstrap.v1`, `period.drain.marker`.
- Viết 26 tệp `contracts/events/avro/<miền>/<TenNghiepVu>.avsc` theo mẫu EVT-05 của TL-03: namespace `vn.vtokr.<miền>.v1`, trường chính đúng cột "Trường dữ liệu chính", kiểu `uuid`, `decimal`, `timestamp-micros` như mẫu, trường tuỳ chọn là union có `default: null`, có `aggregateVersion` khi sự kiện đi qua outbox; không trường nào mang dữ liệu C2 (TL-03 L4437).
- Viết lược đồ cho các topic kỹ thuật mà TL mô tả trường; với topic TL không mô tả trường (ví dụ `period.drain.marker`) viết lược đồ tối thiểu, ghi `"doc": "(suy luận) ..."` và liệt kê trong báo cáo để người phụ trách duyệt.
- Không đưa thuộc tính phong bì CloudEvents (`id`, `source`, `type`, `tenantid`, `actorid`, `traceparent`, `policyversion`, `classification`, `aggregateversion`) vào thân Avro, vì TL-03 đặt chúng ở header Kafka chế độ nhị phân; ghi rõ điều này trong `contracts/events/README.md`.
- Tạo mô-đun `backend/libs/vt-events/` dùng `avro-maven-plugin` sinh lớp Java (kiểu chuỗi `String`, `decimal` → `BigDecimal`), kèm lớp hằng `EventTopics` sinh từ `catalog.yaml` (suy luận về cách sinh, có thể dùng một bước Maven đơn giản).
- Kiểm tương thích: profile `-Pschema-compat` dùng `apicurio-registry-maven-plugin` (Apicurio 3.x chạy bằng Testcontainers hoặc Docker trong CI) đăng ký toàn bộ lược đồ với quy tắc `BACKWARD_TRANSITIVE`, rồi `test-update` lược đồ đang sửa; kèm một kiểm thử JUnit đọc mọi `.avsc`, phân tích bằng `Schema.Parser` và đối chiếu `catalog.yaml` (đủ 26 mã, topic khớp regex `^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$`, không trùng tên).
- Điểm lệch cần lưu ý: TL-01 L1287 ghi tương thích `BACKWARD`, TL-03 L4410 và quy tắc tiến hoá ghi `BACKWARD_TRANSITIVE`; task chọn `BACKWARD_TRANSITIVE` (chặt hơn, thoả cả hai) và ghi vào báo cáo; `iam.auth.event.v1` giữ nguyên tên dù không có tiền tố `sys.`.

## 4. Ngoài phạm vi

- AsyncAPI cho topic công bố: NEN-008.
- Ghi outbox và consumer: NEN-011, NEN-012; topic dạng mã trên Strimzi: INF-10; topic dev: NEN-020.
- Đổi nội dung sự kiện so với TL (thêm trường mới ngoài TL) – chỉ làm khi task nghiệp vụ cần và ghi rõ.

## 5. Tệp được phép sửa

- `contracts/events/**` (mới: `catalog.yaml`, `README.md`, `avro/**`)
- `backend/libs/vt-events/**`, `backend/libs/pom.xml`, `backend/pom.xml` (chỉ thêm mô-đun), `backend/platform-bom/pom.xml` (chỉ thêm phiên bản Avro, plugin Apicurio)
- `wiki/18-su-kien-va-dong-bo-du-lieu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-events -am verify` chạy xanh; `./mvnw -q -pl libs/vt-events -Pschema-compat verify` chạy xanh với Apicurio thật trong container.
- Kiểm thử âm: đổi kiểu một trường bắt buộc trong bản sao lược đồ thì bước tương thích thất bại.
- Bảng đối chiếu 26 dòng (mã, topic, khoá, phân loại) giữa `catalog.yaml` và TL-03 khớp hoàn toàn; script đối chiếu hoặc kết quả `diff` có trong báo cáo.
- Không có trường C2 trong mọi lược đồ (kiểm bằng danh sách trường đối chiếu cột phân loại TL-03).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 18 (nếu đã có) đường dẫn tới `contracts/events/`.

## 7. Báo cáo

Theo AGENTS.md mục 6.
