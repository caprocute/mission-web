# Hợp đồng sự kiện (Avro, Apicurio Registry)

> Nguồn: baseline mục 7 (danh mục EVT, quy ước topic, trường bắt buộc); TL-03 "Lược đồ sự kiện" ("Phong bì sự kiện", "Danh mục lược đồ EVT-01 đến EVT-26", "Cấu hình topic"); TL-01 "Quy ước topic, CloudEvents và AsyncAPI"; ADR-03; `docs/quy-dinh/BACKEND.md` mục 2. Task: NEN-007.

Thư mục này là nguồn sự thật của mọi sự kiện nội bộ trên Kafka. Mã máy chủ không tự định nghĩa thân sự kiện mà dùng lớp Java sinh từ đây (`backend/libs/vt-events`). Khi mã và hợp đồng lệch nhau, sửa mã; khi hợp đồng thật sự sai, mở task sửa hợp đồng riêng và chạy kiểm tương thích.

## Cấu trúc

```
contracts/events/
├── catalog.yaml            26 sự kiện EVT-01..EVT-26 và 8 topic kỹ thuật: mã, tên nghiệp vụ, topic,
│                           khoá, outbox nguồn, phân loại, bên phát, bên tiêu thụ, tệp lược đồ, artifactId
├── avro/<miền>/<Tên>.avsc  một lược đồ cho mỗi topic; <miền> là đoạn đầu của topic,
│                           namespace vn.vtokr.<miền>.v1, tên record là tên nghiệp vụ
└── tools/doi-chieu-tl.py   đối chiếu catalog.yaml và tệp .avsc với bảng EVT của baseline và TL-03
```

## Phong bì nằm ở tiêu đề Kafka, không nằm trong thân Avro

Theo TL-03 "Phong bì sự kiện", mọi sự kiện nội bộ có phong bì CloudEvents 1.0 ánh xạ vào **tiêu đề Kafka (chế độ nhị phân)**; chỉ phần dữ liệu là Avro. Vì vậy các thuộc tính sau **không** được khai báo trong tệp `.avsc`:

| Thuộc tính phong bì | Ý nghĩa | Tương ứng baseline mục 7 |
|---|---|---|
| `id` | UUIDv7, là `event_id` | `eventId` |
| `source` | `urn:vt-okr:<cell>:<service>` | |
| `type` | tên topic | |
| `time` | thời điểm xảy ra | `occurredAt` |
| `subject` | `<aggregateType>/<aggregateId>` | |
| `tenantid` | tenant | `tenantId` |
| `actorid` | người thực hiện | `actorId` |
| `traceparent` | W3C Trace Context | `traceparent` |
| `policyversion` | phiên bản Policy Pack (nếu có) | `policyVersion` |
| `classification` | phân loại cao nhất của dữ liệu | `classification` |
| `aggregateversion` | phiên bản thực thể gốc | |

Kiểm thử `EventCatalogTest.khongCoThuocTinhPhongBiTrongThan` chặn các tên này trong thân. Hai ngoại lệ có chủ đích, đều do TL quy định:

- `aggregateVersion` có trong thân mọi sự kiện đi qua outbox, đúng mẫu Avro EVT-05 của TL-03 (consumer cần nó sau khi giải mã để ghi `inbox_aggregate_version`). Phần mở rộng `aggregateversion` ở tiêu đề vẫn được đặt để định tuyến, lọc mà không phải giải mã.
- `actorId` của EVT-11 là trường dữ liệu theo cột "Trường dữ liệu chính" của TL-03 (người hoàn tất bước), khác `actorid` của phong bì.

Ra ngoài hệ thống, SVC-13 chuyển sự kiện sang CloudEvents JSON chế độ cấu trúc; việc đó thuộc NEN-008 (AsyncAPI) và task của SVC-13, không thuộc thư mục này.

## Quy tắc viết lược đồ

- Kiểu theo bảng "Kiểu dữ liệu chuẩn" của TL-03: định danh `string` + `logicalType: uuid`; thời điểm `long` + `timestamp-micros`; ngày `int` + `date`; điểm `decimal(6,3)`; hệ số, tỷ lệ `decimal(6,4)`; giá trị đo `decimal(18,4)` (decimal trên `bytes`); băm `bytes`; mã nghiệp vụ và trạng thái là `string` (không dùng `enum` của Avro để thêm giá trị mới không phá tương thích); cấu trúc mềm `jsonb` là `string` chứa JSON.
- Trường tuỳ chọn là union `["null", T]` có `"default": null`.
- Không trường nào mang dữ liệu C2 (TL-03: "Không sự kiện nào mang nội dung C2; khi cần, sự kiện chỉ mang mã tham chiếu"). Kiểm thử `khongCoTruongC2` chặn tên trùng các cột C2 của TL-03 và hậu tố `Enc`.
- Chỗ TL không mô tả trường (đa số topic kỹ thuật) ghi `"doc": "(suy luận) ..."`, chờ người phụ trách duyệt.
- Tiến hoá (TL-03 "Quy tắc tiến hoá lược đồ"): chỉ thêm trường có giá trị mặc định; không đổi kiểu, không đổi tên, không xoá trường bắt buộc; thay đổi phá vỡ tạo topic `.v2` chạy song song ít nhất một quý.

## Tương thích trong Apicurio

Chế độ tương thích là `BACKWARD_TRANSITIVE` (TL-03 "Phong bì sự kiện" và quy tắc tiến hoá). TL-01 mục "Phong bì sự kiện" ghi `BACKWARD`; `BACKWARD_TRANSITIVE` chặt hơn và thoả cả hai nên được chọn. Artifact đăng ký trong nhóm `default` với artifactId `<topic>-value`, khớp chiến lược mặc định `TopicIdStrategy` của serde Apicurio (suy luận).

```bash
cd backend
./mvnw -pl libs/vt-events -am verify                     # sinh lớp, kiểm thử đối chiếu danh mục
./mvnw -pl libs/vt-events -Pschema-compat verify         # Apicurio 3.x trong Docker: register + test-update
# Kiểm bản đang sửa so với bản đã phát hành (CI: baselineDir lấy từ nhánh đích):
./mvnw -pl libs/vt-events -Pschema-compat verify \
  -Dschema.compat.baselineDir=/duong-dan/ban-phat-hanh/avro \
  -Dschema.compat.candidateDir=/duong-dan/ban-dang-sua/avro
# Dùng registry có sẵn thay cho container:
./mvnw -pl libs/vt-events -Pschema-compat verify -Dschema.compat.docker.skip=true \
  -Dschema.compat.registryUrl=http://<registry>/apis/registry/v3
```

Khi thêm hoặc đổi tên một lược đồ, cập nhật đồng thời `catalog.yaml`, tệp `.avsc` và danh sách artifact trong profile `schema-compat` của `backend/libs/vt-events/pom.xml`; `EventCatalogTest` báo lỗi nếu ba nơi không khớp. Đối chiếu với TL:

```bash
python3 contracts/events/tools/doi-chieu-tl.py \
  /mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md \
  /mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md
```

## Dùng trong service Java

Phụ thuộc `vn.viettel.okrkpi:vt-events`; lớp sinh nằm ở `vn.vtokr.<miền>.v1`, tên topic ở `vn.viettel.okrkpi.events.EventTopics` (ví dụ `EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT`). Avro từ 1.12.1 chỉ nạp lớp sinh nằm trong danh sách tin cậy; service gọi `EventContracts.trustContractPackages()` một lần khi khởi động, nếu không `SpecificDatumReader` ném `SecurityException`. Lược đồ và danh mục cũng được đóng vào jar ở `classpath:contracts/events/`.

## Hợp đồng AsyncAPI 3.0 cho các topic công bố qua `SVC-13` (`NEN-008`)

Các topic nghiệp vụ được công bố hoặc liên thông ra ngoài qua `SVC-13` (`integration-hub-svc`, `MOD-13`) có hợp đồng dành cho đối tác tiêu thụ tại [`../asyncapi/mission-events.asyncapi.yaml`](../asyncapi/mission-events.asyncapi.yaml):

| Mã | Topic (`channel.address`) | Lược đồ Avro gốc | Đối tác tiêu thụ qua `SVC-13` |
|---|---|---|---|
| `EVT-04` | `task.task.assigned.v1` | `avro/task/NhiemVuDaGiao.avsc` | Hệ thống quản lý văn bản (VOffice), lịch công tác, cổng thông báo |
| `EVT-12` | `appraisal.rating.decided.v1` | `avro/appraisal/XepLoaiDaQuyetDinh.avsc` | CSDL Quốc gia về CBCCVC (Bộ Nội vụ), CSDL Đảng viên 3.0 (Ban Tổ chức TW) |
| `EVT-19` | `ledger.seal.created.v1` | `avro/ledger/SoCaiDaNiemPhong.avsc` | Hệ thống giám sát kiểm toán độc lập, cổng đối soát chữ ký TSA |

Kiểm tra cú pháp AsyncAPI 3.0 và đối chiếu chéo tự động với `catalog.yaml` + `.avsc`:

```bash
cd contracts
npm run asyncapi:lint
```

## Liên quan

- `wiki/18-su-kien-va-dong-bo-du-lieu.md`
- `backend/libs/vt-events/`
- `contracts/asyncapi/mission-events.asyncapi.yaml`
- NEN-008 (AsyncAPI), NEN-011 (outbox), NEN-012 (consumer, inbox), NEN-021 (topic kỹ thuật), INF-10 (topic dạng mã), NEN-020 (topic dev)
