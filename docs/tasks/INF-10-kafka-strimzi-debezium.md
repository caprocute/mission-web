# INF-10 · ADR-03 Kafka bằng Strimzi, Apicurio, Kafka Connect Debezium, khai báo topic dạng mã

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-08, NEN-007 |
| Truy vết | ADR-03 → TL-04 mục 4.6 → TL-03 ch. "Transactional Outbox và Debezium", "Lược đồ sự kiện" → EVT-01..EVT-26 và topic kỹ thuật `sys.*` → bảng `<schema>.outbox_event`, `platform.dbz_heartbeat` → SEC-57, OPS-24 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng tầng sự kiện của một cell dạng mã: cụm Kafka KRaft bằng Strimzi, Apicurio Registry, Kafka Connect với Debezium đọc bảng outbox, và toàn bộ topic, người dùng, ACL khai báo bằng CR trong Git. Khi xong, trên kind một dòng ghi vào `outbox_event` của PostgreSQL thử được Debezium đưa sang đúng topic EVT kèm header phong bì, và mọi topic EVT-01..EVT-26 cùng topic kỹ thuật, retry, DLQ tồn tại với cấu hình đúng TL-04 và TL-03.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Định cỡ Kafka, Valkey, OpenSearch, ClickHouse" (753–775: broker, controller, nhóm topic).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Transactional Outbox và Debezium" (4205–4267, connector mẫu `outbox-td_hcm`), "Phong bì sự kiện" và danh mục topic (4406–4474).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 7 (EVT, quy ước topic, khoá `tenantId:aggregateId`).
- `contracts/events/avro/` (do NEN-007 tạo), `infra/dev/scripts/create-topics.sh`, `docs/quy-dinh/BACKEND.md` mục 2.

## 3. Phạm vi công việc

- Viết CR `Kafka` (KRaft) và `KafkaNodePool` cho cell hạng L: 6 broker (2 mỗi vùng) và 3 controller tách riêng, `rack.topologyKey: topology.kubernetes.io/zone`, `min.insync.replicas=2`, `default.replication.factor=3`, `unclean.leader.election.enable=false`, nén zstd, listener mTLS; kích thước lấy từ values hồ sơ (INF-03).
- Viết bộ sinh topic dạng mã: đọc danh mục EVT trong baseline mục 7 và topic kỹ thuật TL-03 (`sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`, `org.bootstrap.v1`, `registry.tenant.changed.v1`, …) để sinh `KafkaTopic` theo nhóm TL-04 mục 4.6 (nghiệp vụ cao 24 phân vùng 7 ngày, đầu vào tính điểm 48, nghiệp vụ thấp 6/30 ngày, `sys.ledger.append.v1` 12/14 ngày, CDC 12/3 ngày) và cặp `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>` 3 phân vùng 30 ngày (OPS-24).
- Viết `KafkaUser` cho từng service và cho Connect: ACL ghi chỉ cho tài khoản Connect với topic nghiệp vụ, ACL đọc theo nhóm tiêu thụ; `sys.audit.v1` chỉ Fluent Bit và `audit-writer` được ghi (SEC-57).
- Viết values Apicurio Registry (lưu trên PostgreSQL), đặt quy tắc tương thích toàn cục và đăng ký lược đồ từ `contracts/events/avro/` bằng Job.
- Viết `KafkaConnect` có plugin Debezium PostgreSQL và bộ sinh `KafkaConnector` theo CSDL đúng mẫu `outbox-td_hcm` (pgoutput, slot `dbz_outbox_<db>`, `publication.name=pub_outbox`, `snapshot.mode=never`, EventRouter `route.by.field=topic`, header `ce_type`, `ce_tenantid`, `traceparent`, `ce_classification`, heartbeat vào `platform.dbz_heartbeat`).
- Điểm lệch cần lưu ý: (1) TL-01 nêu một connector mỗi schema, TL-03 chọn một connector mỗi CSDL; chọn theo TL-03 (thiết kế dữ liệu chi tiết hơn). (2) TL-03 đặt tương thích `BACKWARD_TRANSITIVE`, TL-01 và `docs/quy-dinh/BACKEND.md` ghi `BACKWARD`; chọn `BACKWARD_TRANSITIVE` (chặt hơn, vẫn thoả `BACKWARD`). Ghi cả hai vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 4. Ngoài phạm vi

- Lược đồ Avro và lớp Java sinh (NEN-007), thư viện outbox/inbox (NEN-011, NEN-012).
- MirrorMaker 2 sang TTDL phía Nam (INF-18); KEDA theo độ trễ consumer (INF-16).

## 5. Tệp được phép sửa

- `deploy/charts/kafka-cell/**`, `deploy/charts/apicurio/**`, `deploy/charts/kafka-connect/**` (tên chart suy luận), `deploy/charts/vt-platform/values*.yaml` phần bật chart.
- `contracts/asyncapi/**` chỉ khi cần đồng bộ tên topic (không đổi lược đồ).
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm lint`, `helm template`, `kubeconform -strict` (schema CRD Strimzi) không lỗi; kiểm thử của bộ sinh topic (pytest hoặc Go test) so khớp đủ 26 topic EVT và topic kỹ thuật.
- Trên kind: Strimzi dựng cụm thu nhỏ (3 broker, 3 controller); Debezium đọc một dòng `outbox_event` từ PostgreSQL 17 thử (cụm CNPG nếu INF-11 đã gộp, nếu không thì một pod PostgreSQL) và topic đích nhận thông điệp có đủ header phong bì; đăng ký lại một lược đồ phá tương thích bị Apicurio từ chối.
- Kết nối không TLS hoặc tài khoản không có ACL bị từ chối.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
