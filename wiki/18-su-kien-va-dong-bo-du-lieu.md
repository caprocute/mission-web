> Nguồn: baseline mục 7 (EVT) và mục 8 (cơ chế đồng bộ); TL-01 chương "Kiến trúc tích hợp và dữ liệu luân chuyển" (Bảo đảm giao nhận: outbox và inbox, Thứ tự và phân vùng, Quy ước topic, Phong bì sự kiện, AsyncAPI), mục "SVC-05 mission-core" phần "Outbox", chương "Quan sát và nhật ký" (Tách nhật ký kiểm toán), Phụ lục A ADR-23; TL-03 chương "Cơ chế đồng bộ dữ liệu" (Tổng quan, Transactional Outbox và Debezium, Inbox idempotent, Event-carried State Transfer, CDC phân tích, Đánh chỉ mục tìm kiếm, Saga điều phối chốt kỳ, REST đồng bộ, Tích hợp hệ ngoài, Đồng bộ giữa các cell), chương "Lược đồ sự kiện" (Phong bì sự kiện, Danh mục lược đồ EVT-01 đến EVT-26, Cấu hình topic), chương "Thiết kế chi tiết các schema" mục "Bảng outbox_event", "Bảng inbox_processed và inbox_aggregate_version"; `_ghi-chu-TL-03.md` mục 6; TL-04 mục "Bảng cấu hình theo service"; `docs/quy-dinh/BACKEND.md`.

# Sự kiện và đồng bộ dữ liệu

Trang này là nơi tra nhanh cho mọi task có phát hoặc nhận sự kiện: tên topic, khoá, phong bì, bảng outbox và inbox, thứ tự xử lý của consumer, quy ước thử lại và thư chết, cùng tám cơ chế đồng bộ dữ liệu của baseline mục 8. Lược đồ Avro và tài liệu AsyncAPI thật nằm ở `contracts/events/` (task NEN-007, NEN-008); trang này không thay thế chúng. Quy tắc giao tiếp trong tiến trình (sự kiện ứng dụng Spring Modulith) ở [trang 21](21-kien-truc-mission-core-va-microservice.md); bảng dùng chung và vai trò `cdc_<schema>` ở [trang 17](17-thiet-ke-csdl-va-da-khach-thue.md). Chỗ các nguồn nói khác nhau được ghi một câu tại chỗ và gom ở [trang 14](14-quyet-dinh-va-cau-hoi-mo.md).

## Bảo đảm chung

Toàn hệ thống bảo đảm giao nhận ít nhất một lần (at-least-once) kèm xử lý idempotent, tương đương hiệu lực đúng một lần ở mức nghiệp vụ. Không cơ chế nào dùng giao dịch phân tán 2PC hay XA. Tính nhất quán giữa các service đạt được bằng bốn thứ kết hợp: ghi nguyên tử cục bộ (outbox), tiêu thụ idempotent (inbox), thứ tự theo khoá thông điệp, và đối soát định kỳ (TL-03 chương 17). Mã nghiệp vụ không bao giờ gửi Kafka trực tiếp; mọi thay đổi trạng thái nghiệp vụ được ghi vào outbox trong cùng giao dịch (`docs/quy-dinh/BACKEND.md` mục 3).

## Danh mục 26 sự kiện nghiệp vụ

Bảng dưới chép nguyên từ TL-03 chương "Lược đồ sự kiện", bảng "Danh mục lược đồ EVT-01 đến EVT-26". Khoá thông điệp ghi dạng `tenant:<aggregateId>`, tức `tenantId:aggregateId` theo baseline mục 7. Cột phân loại là mức cao nhất của dữ liệu trong sự kiện; không sự kiện nào mang nội dung C2, khi cần thì sự kiện chỉ mang mã tham chiếu để consumer có quyền đọc qua API. Bên phát và bên tiêu thụ chính của từng sự kiện ở baseline mục 7 và ở bảng service trên [trang 21](21-kien-truc-mission-core-va-microservice.md).

| Mã | Topic | Outbox nguồn | Khoá | Trường dữ liệu chính | Phân loại |
|---|---|---|---|---|---|
| EVT-01 | okr.objective.approved.v1 | `okr.outbox_event` | `tenant:objectiveId` | objectiveId, periodId, level, ownerUnitId, parentObjectiveId, basisIds, approvedBy | C1 |
| EVT-02 | okr.keyresult.checkedin.v1 | `okr.outbox_event` | `tenant:keyResultId` | keyResultId, objectiveId, value, confidence, checkinAt | C1 |
| EVT-03 | task.plan.approved.v1 | `task.outbox_event` | `tenant:planId` | planId, planVersionId, versionNo, periodId, ownerType, ownerId, itemCount, approvedBy | C1 |
| EVT-04 | task.task.assigned.v1 | `task.outbox_event` | `tenant:taskId` | taskId, planItemId, title, leadAssigneeId, assigneeIds, dueDate, weight, isKeyTask, isSecret | C1 |
| EVT-05 | task.task.progressed.v1 | `task.outbox_event` | `tenant:taskId` | taskId, progressId, progressPct, actualValue, evidenceCount, reportedAt | C1 |
| EVT-06 | task.task.statuschanged.v1 | `task.outbox_event` | `tenant:taskId` | taskId, fromStatus, toStatus, isOverdue, reasonCode, changedBy | C1 |
| EVT-07 | product.evidence.submitted.v1 | `product.outbox_event` | `tenant:evidenceId` | evidenceId, versionId, deliverableId, taskId, objectSha256, aiConfirmed, submittedBy | C1 |
| EVT-08 | product.evidence.accepted.v1 | `product.outbox_event` | `tenant:evidenceId` | evidenceId, versionId, taskId, qualityLevel, acceptedBy, acceptedAt | C1 |
| EVT-09 | config.period.locked.v1 | `config.outbox_event` | `tenant:periodId` | periodId, periodBucket, lockedAt, sagaId, policyPackVersionIds | C1 |
| EVT-10 | scoring.score.computed.v1 | `scoring.outbox_event` | `tenant:profileId` | scoreResultId, profileId, jobKind, totalScore, autoRatingCode, inputHash, policyContentHash | C1 |
| EVT-11 | appraisal.profile.stepcompleted.v1 | `appraisal.outbox_event` | `tenant:profileId` | profileId, fromStatus, toStatus, stage, actorId | C1 |
| EVT-12 | appraisal.rating.decided.v1 | `appraisal.outbox_event` | `tenant:profileId` | profileId, personId, flow, ratingCode, scoreSnapshotId, deciderId, signRequestId | C1 |
| EVT-13 | policy.pack.published.v1 | `policy.outbox_event` | `tenant:packId` | packId, versionId, contentHash, effectiveFrom, approvedBy, testRunId | C0 |
| EVT-14 | org.unit.changed.v1 | `org.outbox_event` | `tenant:unitId` | unitId, versionId, sourceVersion, trạng thái đầy đủ của đơn vị, validFrom, validTo, changeOrderId | C1 |
| EVT-15 | org.assignment.changed.v1 | `org.outbox_event` | `tenant:personId` | assignmentId, personId, unitId, positionId, jobTitleId, kind, isPrimary, validFrom, validTo, sourceVersion | C1 |
| EVT-16 | org.person.changed.v1 | `org.outbox_event` | `tenant:personId` | personId, displayName, subjectGroupId, isPartyMember, status, sourceVersion | C1 |
| EVT-17 | dossier.form.generated.v1 | `docgen.outbox_event` | `tenant:formInstanceId` | renderJobId, formInstanceId, formCode, objectKey, pdfSha256 | C1 |
| EVT-18 | esign.signature.completed.v1 | `esign.outbox_event` | `tenant:signRequestId` | signRequestId, purpose, sourceModule, sourceRefId, items (documentSha256, signedObjectKey), certSha256, signedAt | C1 |
| EVT-19 | ledger.seal.created.v1 | `ledger.outbox_event` | `tenant:sealId` | sealId, sealKind, windowStart, windowEnd, merkleRoot, prevSealRoot, tsaSerial | C1 |
| EVT-20 | analytics.anomaly.detected.v1 | Producer ClickHouse job (không có outbox PostgreSQL) | `tenant:subjectId` | alertKey, signalCode, severity, subjectType, subjectId, evidenceChain, modelVersion | C1 |
| EVT-21 | notify.message.requested.v1 | outbox của module yêu cầu | `tenant:recipientId` | recipientIds, templateCode, params, deepLink, priority | C1 |
| EVT-22 | oversight.period.reopened.v1 | `oversight.outbox_event` | `tenant:periodId` | periodId, reopenRequestId, scope, scopeIds, approverIds, executedAt | C1 |
| EVT-23 | config.uipack.published.v1 | `config.outbox_event` | `tenant:uiPackId` | uiPackId, versionId, contentHash, cdnPaths | C0 |
| EVT-24 | integ.document.received.v1 | `integ.outbox_event` | `tenant:documentId` | documentId, connectorCode, externalId, docNumber, issuedDate, issuer, summary, objectKey | C1 |
| EVT-25 | oversight.complaint.filed.v1 | `oversight.outbox_event` | `tenant:complaintId` | complaintId, profileId, targetStage, complainantId, filedAt, responseDeadline | C1 |
| EVT-26 | config.period.unlocked.v1 | `config.outbox_event` | `tenant:periodId` | periodId, periodBucket, lockOrderId, sagaId, reason, unlockedAt | C1 |

EVT-20 là ngoại lệ duy nhất không đi qua outbox PostgreSQL: job phát hiện bất thường chạy trên ClickHouse ghi kết quả vào bảng `anomaly_out`, một producer nhỏ đọc bảng này và phát lên Kafka với `alertKey` tất định (băm của tín hiệu, đối tượng và cửa sổ thời gian) làm `event_id`, nên chạy lại job không sinh cảnh báo trùng ở MOD-07. EVT-21 được ghi vào outbox của chính module yêu cầu thông báo. EVT-26 do TL-03 thêm cho bước bù trừ của saga chốt kỳ và đã có trong baseline mục 7.

Ngoài 26 sự kiện, TL-03 còn nêu ba sự kiện nội bộ không thuộc danh mục EVT: `dossier.form.requested.v1` (MOD-06 yêu cầu SVC-11 sinh biểu mẫu), `evidence.object.scanned.v1` (SVC-08 báo kết quả quét, MOD-03 tiêu thụ), và `workflow.task.created.v1`, `workflow.task.completed.v1` của SVC-06 cho hộp việc. Các sự kiện này cần lược đồ Avro như sự kiện thường nhưng không cấp mã EVT mới (suy luận, theo quy tắc không đặt mã mới).

## Topic kỹ thuật

Topic kỹ thuật không thuộc danh mục EVT và không được ứng dụng nghiệp vụ tiêu thụ. TL-03 liệt kê các topic sau, phần lớn đã có trong TL-01.

| Topic | Bên phát và mục đích |
|---|---|
| `sys.ledger.append.v1` | Đầu vào nội bộ của SVC-09 ghi sổ cái |
| `sys.audit.v1` | Sự kiện kiểm toán: đăng nhập quản trị, đổi quyền, xem C2 của người khác, xuất hàng loạt; SVC-09 ghi sổ, chi tiết ở [trang 20](20-bao-mat-phia-may-chu.md) |
| `sys.evidence.object.stored.v1` | SVC-08 báo tệp minh chứng đã lưu và khoá Object Lock, MOD-03 tiêu thụ |
| `sys.tenant.provisioned.v1` | SVC-03 báo tenant đã khởi tạo xong CSDL, realm, bucket |
| `iam.auth.event.v1` | Sự kiện xác thực của Keycloak qua Event Listener SPI, cho SOC và kiểm toán |
| `period.drain.marker` | Dòng outbox đánh dấu của saga chốt kỳ (bước 2) |
| `org.bootstrap.v1` | Phát lại toàn bộ trạng thái tổ chức của một tenant để dựng bản sao mới |
| `registry.tenant.changed.v1` | SVC-03 báo danh bạ tenant đổi, MirrorMaker 2 sao xuống mọi cell |
| `cdc.<db>.<schema>.<table>` | Thay đổi dòng cho phân tích, do connector CDC thứ hai sinh |
| `agg.*` | Số liệu tổng hợp đã ẩn danh, MirrorMaker 2 đẩy lên kho phân tích quốc gia |
| `<topic>.retry.<consumer>`, `<topic>.dlq.<consumer>` | Thử lại có độ trễ và thư chết của từng consumer |

Tên topic kỹ thuật chưa thống nhất ở ba chỗ, đều được ghi ở trang 14. TL-01 quy ước topic kỹ thuật dùng tiền tố `sys.`, nhưng `iam.auth.event.v1`, `period.drain.marker`, `org.bootstrap.v1`, `registry.tenant.changed.v1` không theo; TL-03 mục SVC-02 lại ghi Keycloak phát `iam.audit.v1`. TL-01 mục SVC-08 dùng `evidence.object.stored.v1` (không có `sys.`) trong khi chính TL-01 ở chỗ khác và TL-03 dùng `sys.evidence.object.stored.v1`. TL-04 dùng thêm `sys.siem.v1` (SEC-43) và `sys.security.runtime.v1` (SEC-50) mà TL-03 không liệt kê.

## Quy ước topic và cấu hình

Tên topic theo dạng `<miền>.<thực-thể>.<sự-kiện>.v<phiên-bản>`, chữ thường, trong đó miền là tên module hoặc service phát (TL-01 "Quy ước topic"). Thay đổi không tương thích tạo topic mới `.v2`, bên phát ghi song song hai topic ít nhất một quý trong thời gian chuyển tiếp.

TL-03 "Cấu hình topic" chốt cho topic nghiệp vụ: 24 phân vùng ở cell bridge và pool, 12 phân vùng ở cell silo; hệ số nhân bản 3, `min.insync.replicas=2`; giữ 7 ngày; nén `zstd`. TL-01 ghi thêm: giữ 30 ngày cho topic đầu vào sổ cái và tính điểm để phục vụ tính lại, và nén theo khoá cho topic trạng thái `org.*` (TL-03 đặt `cleanup.policy=compact,delete`, giữ 30 ngày). Số phân vùng lệch giữa các nguồn: TL-01 "Thứ tự và phân vùng" ghi 24 ở cell lớn, 12 ở cell nhỏ, 64 cho đầu vào ledger-service; TL-03 ghi 24 ở bridge và pool, 12 ở silo, tức CELL-HCM (silo nhưng là cell lớn nhất) rơi vào hai con số khác nhau; TL-04 bảng cấu hình service ghi `sys.ledger.append.v1` có 12 phân vùng. Ba điểm này chờ người phụ trách chốt ở trang 14.

ACL Kafka cấp quyền ghi topic cho đúng tài khoản Kafka Connect của CSDL nguồn và quyền đọc cho đúng nhóm tiêu thụ ở cột "Tiêu thụ chính" của baseline. Ở cell pool, Kafka dùng chung cho mọi tenant; tách tenant dựa vào trường `tenantid` của phong bì và vào việc consumer đặt ngữ cảnh RLS từ trường này, không dựa vào topic riêng.

## Phong bì sự kiện và tiến hoá lược đồ

Sự kiện nội bộ dùng phong bì CloudEvents 1.0 ở chế độ nhị phân, tức các thuộc tính phong bì nằm trong header Kafka, còn phần dữ liệu là Avro đăng ký ở Apicurio Registry. Thuộc tính phong bì theo TL-03 gồm `id` (UUIDv7, chính là `event_id`), `source` (`urn:vt-okr:<cell>:<service>`), `type` (tên topic), `time`, `subject` (`<aggregateType>/<aggregateId>`), và các phần mở rộng `tenantid`, `actorid`, `traceparent`, `policyversion`, `classification`, `aggregateversion`. Tên phần mở rộng viết thường, không dấu gạch theo quy tắc của CloudEvents. Khi ra ngoài qua SVC-13, sự kiện được chuyển sang CloudEvents JSON chế độ cấu trúc, kênh truyền dùng mTLS và tải trọng được ký JWS. Ví dụ ra ngoài của TL-01 dùng `source` dạng `urn:vt-okrkpi:cell-hcm:appraisal` và `type` có tiền tố `vn.viettel.okrkpi.`, khác chuỗi `urn:vt-okr:` của TL-03; người viết lược đồ nên chốt một dạng (trang 14).

Trường `aggregateVersion` bằng `row_version` của đối tượng sau thay đổi; consumer dùng nó để bỏ sự kiện cũ đến muộn. Lược đồ mẫu EVT-05 của TL-03 dùng kiểu Avro logic `uuid`, `decimal` với độ chính xác khớp kiểu CSDL (`progressPct` là `decimal(6,3)`, `actualValue` là `decimal(18,4)`), và `timestamp-micros` cho thời điểm.

Quy tắc tiến hoá: chỉ thêm trường có giá trị mặc định; không đổi kiểu, không đổi tên, không xoá trường bắt buộc; thay đổi phá vỡ tạo topic `.v2` chạy song song ít nhất một quý, consumer chuyển dần. CI kiểm tương thích bằng plugin Maven của Apicurio trước khi phát hành. Mức tương thích lệch giữa các nguồn: TL-03 ghi `BACKWARD_TRANSITIVE`, TL-01 và `docs/quy-dinh/BACKEND.md` ghi `BACKWARD` (trang 14). Mọi topic công bố cho bên thứ ba được mô tả bằng AsyncAPI 3.0 sinh từ lược đồ Avro.

## Outbox và Debezium

Mỗi schema phát sự kiện có bảng `<schema>.outbox_event`. Ứng dụng chỉ chèn, không bao giờ sửa bảng này; bộ chuyển đổi chung `OutboxEventPublisher` của thư viện nền tuần tự hoá payload sang Avro theo lược đồ đã đăng ký rồi chèn một dòng trong cùng giao dịch JPA. Debezium PostgreSQL connector (trên Kafka Connect, plugin `pgoutput`) đọc WAL, chỉ lấy các bảng `*.outbox_event` qua publication `pub_outbox`, và dùng Outbox Event Router lấy cột `topic` làm tên topic, cột khoá làm khoá thông điệp, cột `payload` làm giá trị, các cột còn lại làm header.

Cột của bảng khác nhau giữa hai nguồn và phải chốt trước khi viết migration NEN-032. TL-01 mục "Outbox" có `id`, `tenant_id`, `aggregate_type`, `aggregate_key`, `topic`, `event_type`, `payload bytea`, `schema_id bigint`, `headers jsonb`, `occurred_at`, phân vùng RANGE theo `occurred_at`. TL-03 mục "Bảng outbox_event" có `aggregate_id uuid`, `aggregate_version bigint`, `event_key varchar(80)`, `schema_version int`, `created_at`, khoá chính `(id, created_at)`, ràng buộc `ck_outbox_event__topic`, chính sách `rls_outbox_event__tenant`. Theo nguyên tắc "TL-03 thắng với chi tiết CSDL", đề xuất dùng cột của TL-03 (trang 14). Hai bẫy khi viết theo TL-03. Thứ nhất, cấu hình connector mẫu của chính TL-03 tham chiếu các cột `event_id`, `trace_parent`, `classification`, `schema_id` không có trong bảng của TL-03 (bảng có `id`, `headers`, `schema_version`), nên ánh xạ trường của Event Router phải viết lại cho khớp cột thật. Thứ hai, ràng buộc `ck_outbox_event__topic` chỉ nhận topic đúng dạng `^[a-z]+\.[a-z]+\.[a-z]+\.v[0-9]+$`, tức sẽ từ chối dòng `period.drain.marker` mà saga yêu cầu module ghi vào outbox và topic bốn đoạn `sys.evidence.object.stored.v1`; cả hai điểm cần chốt ở trang 14 trước khi viết migration (suy luận từ biểu thức của ràng buộc).

Cấu hình connector của TL-03 có bốn điểm cần giữ: `snapshot.mode=never` vì outbox chỉ chứa sự kiện mới (TL-01 dùng `no_data`); `ByteArrayConverter` vì payload đã là Avro nhị phân; `heartbeat.action.query` ghi định kỳ vào bảng nhịp tim để slot tiến lên khi không có sự kiện; producer bật idempotence với `acks=all`. Tên connector và slot theo mẫu `outbox-<db>`, `dbz_outbox_<db>`. TL-03 và TL-01 mục "Outbox" (dòng giải thích sau cấu hình mẫu) đều chọn một connector outbox cho mỗi CSDL, liệt kê mọi bảng outbox của các schema trong `table.include.list`, vì mỗi slot sao chép logic giải mã toàn bộ WAL của CSDL; `_ghi-chu-TL-01.md` lại ghi mỗi schema một connector và slot riêng. Module tách thành microservice có CSDL riêng thì có connector riêng. Vai trò `cdc_<schema>` chỉ có quyền `SELECT` trên `outbox_event`; nếu bảng chưa tồn tại lúc chạy bootstrap vai trò thì migration tạo bảng phải tự cấp quyền (xem trang 17).

Dòng outbox không bị Debezium xoá. Bảng phân vùng theo ngày bằng pg_partman (TL-03: 7 phân vùng tạo trước, `p_retention = '3 days'`), job bảo trì tách và xoá phân vùng cũ, không sinh WAL cho từng dòng. TL-04 ghi giữ 72 giờ để phát lại sau chuyển vùng, tức cùng con số. Giám sát bắt buộc gồm độ trễ slot (`pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`), `MilliSecondsBehindSource` của Debezium và số dòng outbox chưa đọc; `max_slot_wal_keep_size = 64GB` để slot hỏng không làm đầy ổ đĩa. Ngưỡng cảnh báo lệch nhau: TL-03 đặt 1 GB WAL hoặc slot không hoạt động 5 phút và 10 giây độ trễ, TL-01 đặt 10 GB và 5 giây.

## Thứ tự

Khoá `tenantId:aggregateId` đưa mọi sự kiện của một đối tượng vào cùng một phân vùng Kafka, nên consumer nhận chúng đúng thứ tự ghi WAL. Sự kiện của hai aggregate khác nhau không có thứ tự tương đối; consumer hiếm khi cần thứ tự giữa aggregate thì dùng `occurredAt` và phiên bản aggregate. Sự kiện trùng xuất hiện khi connector khởi động lại trước khi kịp ghi offset, và được inbox chặn.

## Inbox idempotent và thứ tự xử lý của consumer

Mỗi consumer ghi `(consumer, event_id)` vào `<schema>.inbox_processed` trong cùng giao dịch với tác động nghiệp vụ, bằng `INSERT ... ON CONFLICT (consumer, event_id) DO NOTHING RETURNING event_id`; không có dòng trả về nghĩa là sự kiện đã xử lý, consumer bỏ qua và xác nhận offset. TL-03 thêm các cột `tenant_id`, `source_topic`, `kafka_partition`, `kafka_offset`. Bảng `inbox_aggregate_version (consumer, aggregate_type, aggregate_id, last_version)` xử lý sự kiện sai thứ tự: sự kiện có `aggregateVersion` nhỏ hơn hoặc bằng `last_version` bị bỏ qua vì đã có trạng thái mới hơn. Inbox giữ 14 ngày, dài hơn 7 ngày giữ của topic, nên mọi sự kiện có thể bị giao lại đều còn dấu vết.

Thứ tự xử lý chuẩn của một consumer là: nhận thông điệp; mở giao dịch; đặt ngữ cảnh tenant từ `tenantid`; chèn inbox; kiểm phiên bản trong `inbox_aggregate_version`; áp dụng thay đổi; commit; rồi mới xác nhận offset Kafka (trong Spring Kafka là `ack.acknowledge()` đăng ký ở `afterCommit`). Nếu tiến trình chết sau commit nhưng trước khi xác nhận offset, sự kiện được giao lại và bị inbox chặn.

Lỗi chia hai loại theo TL-03. Lỗi tạm thời (mất kết nối CSDL, hết thời gian chờ) được thử lại tại chỗ ba lần với độ trễ tăng dần, sau đó chuyển sang topic thử lại `<topic>.retry.<consumer>` với độ trễ 1, 5 và 30 phút. Lỗi vĩnh viễn (lược đồ không giải mã được, vi phạm bất biến nghiệp vụ) chuyển ngay sang `<topic>.dlq.<consumer>` và tạo cảnh báo; không bao giờ bỏ qua im lặng. TL-01 "Bảo đảm giao nhận" lại viết thông điệp lỗi lặp 5 lần thì sang `<topic>.dlq` của consumer đó, còn TL-01 "Quy ước topic" và `_ghi-chu-TL-03.md` dùng hậu tố `.dlq.<consumer>`; đề xuất theo TL-03 (trang 14).

## Tám cơ chế đồng bộ

Baseline mục 8 định nghĩa tám cơ chế dùng thống nhất; TL-03 chương "Cơ chế đồng bộ dữ liệu" chi tiết hoá thành chín mục, tách đánh chỉ mục tìm kiếm thành mục riêng. Bảng sau tóm tắt bảo đảm và độ trễ mục tiêu p99 của TL-03.

| Cơ chế | Dùng cho | Bảo đảm | Độ trễ p99 |
|---|---|---|---|
| Outbox và Debezium | Mọi sự kiện nghiệp vụ | Ít nhất một lần, có thứ tự theo khoá | 2 giây |
| Inbox idempotent | Mọi consumer | Hiệu lực đúng một lần | không áp dụng |
| ECST | Dữ liệu tổ chức sang `org_replica` | Hội tụ cuối cùng, phiên bản đơn điệu | 5 giây |
| CDC phân tích | ClickHouse, dashboard, dấu hiệu bất thường | Hội tụ cuối cùng | 60 giây |
| Đánh chỉ mục tìm kiếm | OpenSearch | Hội tụ cuối cùng | 10 giây |
| Saga điều phối | Chốt kỳ, mở lại, tốt nghiệp tenant | Hoàn tất hoặc bù trừ | phút |
| REST có Resilience4j | Truy vấn tức thời ngoài đường găng | Đồng bộ, chờ tối đa 3 giây | 300 ms |
| Tích hợp hệ ngoài | vOffice, CSDL CBCCVC và hệ khác | Ít nhất một lần, đối soát | phút |
| Giữa các cell | Danh bạ tenant, phân tích quốc gia, điều động | Hội tụ cuối cùng | phút đến giờ |

**Chuyển trạng thái qua sự kiện (ECST).** SVC-04 phát EVT-14, EVT-15, EVT-16 mang toàn bộ trạng thái mới của đơn vị, phân công hoặc nhân sự chứ không chỉ phần thay đổi. Consumer `org_replica` trong mission-core và trong các service cần dữ liệu tổ chức ghi đè bản sao bằng upsert có điều kiện `WHERE r.source_version < EXCLUDED.source_version`, nên sự kiện cũ không đè được bản mới. Khi thêm consumer mới hoặc dựng lại bản sao, SVC-04 chạy lệnh `org-republish` đẩy trạng thái hiện hành của mọi thực thể một tenant lên `org.bootstrap.v1`; consumer đọc topic này rồi chuyển sang topic thường. Bản sao được đối soát hằng đêm theo bucket băm. Không gọi đồng bộ để đọc dữ liệu tổ chức trên đường găng.

**CDC phân tích sang ClickHouse.** Mỗi CSDL có một connector Debezium thứ hai (slot `dbz_cdc_<db>`, publication `pub_analytics`) đẩy thay đổi dòng lên `cdc.<db>.<schema>.<table>`; ClickHouse tiêu thụ qua Kafka engine và materialized view vào bảng ReplacingMergeTree. `column.exclude.list` loại mọi cột C2, C3 (cột `_enc`, `_bidx` và cột C2 không mã hoá như `prospect_group.group_code`), còn mã người (`person_id`, `actor_id`, `created_by`) được thay bằng băm có muối theo tenant. Hai danh sách này do công cụ `cdc-config-gen` sinh từ `COMMENT ON COLUMN` trong CI, không viết tay. ClickHouse không có RLS theo người dùng cuối; phân quyền phía đọc thực thi ở Cube bằng `queryRewrite` thêm điều kiện `tenant_id` và tập đơn vị lấy từ JWT, cộng row policy theo `tenant_id` ở cell pool.

**Đánh chỉ mục tìm kiếm.** Indexer của SVC-16 là consumer của EVT-04, EVT-06, EVT-24 và sự kiện danh mục, không đọc CSDL; nó dựng tài liệu từ payload (chỉ trường C0, C1) và gọi `_bulk` với `version_type=external`, `version = aggregateVersion`, nên sự kiện đến muộn không ghi đè tài liệu mới hơn.

**Saga chốt kỳ.** SVC-06 điều phối saga lưu ở `flowable.saga_instance`, `flowable.saga_step`, khởi chạy từ một `config.period_lock_order` đã duyệt và chiếu tiến trình sang `config.period_lock_step`. Sáu bước là: đặt kỳ `DANG_KHOA` và chặn ghi (bù trừ: về `MO`, phát EVT-26); chờ bộ đệm trạng thái kỳ hết hạn (30 giây) và xả sự kiện đang bay bằng dòng outbox `period.drain.marker`; đặt `DA_KHOA` và phát EVT-09, là điểm không quay lại; tính điểm chốt kỳ, phát EVT-10; tạo ảnh chụp điểm `CHOT_KY`; niêm phong sổ cái đột xuất. Bước 4 đến 6 chỉ thử lại, không bù trừ; thất bại liên tục thì saga chuyển `LOI_CAN_XU_LY`. Sau EVT-09, kỳ chỉ mở lại bằng quy trình hậu kiểm bốn mắt (EVT-22, `MO_LAI_HAU_KIEM`).

**REST đồng bộ.** Chỉ cho truy vấn cần dữ liệu tức thời ngoài đường găng, qua client sinh từ OpenAPI bọc Resilience4j. Không lời gọi REST nào được làm thay đổi dữ liệu ở service đích trong khi một giao dịch CSDL cục bộ đang mở; mọi thay đổi chéo service đi qua sự kiện. Thông số cụ thể lệch giữa TL-03 (bulkhead 20, ngắt mạch khi lỗi trên 50% trong cửa sổ 20 lời gọi, thử lại tối đa hai lần chỉ với `GET`) và TL-01 (bulkhead 25, retry 3 lần cho thao tác idempotent kể cả có `Idempotency-Key`); cấu hình chuẩn nằm trong `vt-platform-starter` (ADR-24), xem [trang 21](21-kien-truc-mission-core-va-microservice.md) và trang 14.

**Tích hợp hệ ngoài.** Chỉ qua SVC-13. Chiều vào lưu thông điệp nguyên văn ở `integ.inbox_message`, ánh xạ mã ngoài qua `integ.mapping_xref` rồi phát sự kiện nội bộ (EVT-24 hoặc lệnh tới SVC-04); chiều ra tiêu thụ EVT-12, EVT-19, lưu `integ.outbound_message` và gửi có thử lại, chỉ coi là xong khi hệ nhận xác nhận. Không bảng nội bộ nào chứa mã của hệ ngoài.

**Giữa các cell.** Các cell không chia sẻ CSDL; chỉ có ba luồng được phép: danh bạ tenant qua `registry.tenant.changed.v1`; MirrorMaker 2 sao các topic `agg.*` đã ẩn danh lên kho phân tích quốc gia (không topic nghiệp vụ chi tiết nào được sao); gói điều động cán bộ liên cell `integ.transfer_package` được mã hoá, ký, bọc khoá cho cell nhận và ghi sổ cái cả hai phía.

## Hợp đồng sự kiện trong mã (NEN-007)

### Hợp đồng sự kiện ở đâu

Hợp đồng sự kiện nằm ở `contracts/events/` và là nguồn sự thật (BACKEND.md mục 2):

- `contracts/events/catalog.yaml`: 26 sự kiện EVT-01 đến EVT-26 và 8 topic kỹ thuật, mỗi dòng có mã, tên nghiệp vụ, topic, khoá, bảng outbox nguồn, phân loại, bên phát, bên tiêu thụ, tệp lược đồ.
- `contracts/events/avro/<miền>/<TênNghiệpVụ>.avsc`: lược đồ Avro, namespace `vn.vtokr.<miền>.v1`.
- `backend/libs/vt-events`: lớp Java sinh từ lược đồ và lớp hằng `EventTopics` sinh từ danh mục; profile `-Pschema-compat` kiểm tương thích với Apicurio Registry 3.x thật.

Topic nghiệp vụ theo dạng `<miền>.<thực-thể>.<sự-kiện>.v1`, khoá `tenantId:aggregateId`. Topic kỹ thuật (`sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`, `registry.tenant.changed.v1`, `org.bootstrap.v1`, `period.drain.marker`) không được ứng dụng nghiệp vụ tiêu thụ; trường của đa số topic kỹ thuật là (suy luận) vì TL chưa mô tả.

### Quy ước đã chốt

- Phong bì CloudEvents (`id`, `source`, `type`, `time`, `subject`, `tenantid`, `actorid`, `traceparent`, `policyversion`, `classification`, `aggregateversion`) nằm ở tiêu đề Kafka chế độ nhị phân, không lặp trong thân Avro. Riêng `aggregateVersion` có trong thân mọi sự kiện đi qua outbox theo mẫu EVT-05 của TL-03.
- Tương thích `BACKWARD_TRANSITIVE` (TL-03), chặt hơn `BACKWARD` mà TL-01 ghi; chỉ thêm trường có mặc định, thay đổi phá vỡ tạo topic `.v2`.
- Không sự kiện nào mang dữ liệu C2, chỉ mang mã tham chiếu; kiểm thử chặn tên trùng cột C2 của TL-03.
- Mã nghiệp vụ, trạng thái là `string`, không dùng `enum` Avro; `jsonb` là `string` chứa JSON.
- Artifact Apicurio: nhóm `default`, artifactId `<topic>-value` (TopicIdStrategy, suy luận).

### Bẫy kỹ thuật

- Avro 1.12.1 trở đi chỉ nạp lớp sinh trong danh sách tin cậy (vá CVE-2024-47561). Thiếu bước này, `SpecificDatumReader` ném `SecurityException: Forbidden vn.vtokr...`. Gọi `EventContracts.trustContractPackages()` khi khởi động service.
- Goal `test-update` của apicurio-registry-maven-plugin 3.3 bị đánh dấu lỗi thời (khuyên dùng `register` với `dryRun`) và không gửi kèm tham chiếu, nên `org.bootstrap.v1-value` (tham chiếu EVT-14..EVT-16) chỉ được đăng ký, không test-update.
- CHECK `ck_outbox_event__topic` của TL-03 chỉ nhận topic bốn đoạn, trong khi `period.drain.marker` và `sys.audit.v1` được TL mô tả là đi qua outbox: điểm lệch đang chờ quyết định (xem trang 14 khi được ghi nhận).

## Phát sự kiện trong mã (NEN-011)

Thư viện `vt-platform-starter` cung cấp `OutboxWriter` (`vn.viettel.okrkpi.shared.outbox`) để ghi sự kiện miền vào bảng `<schema>.outbox_event` trong **cùng giao dịch CSDL** với thao tác nghiệp vụ (`Propagation.MANDATORY`).

### Cấu hình dịch vụ phát sự kiện

```yaml
okrkpi:
  outbox:
    schema: task
    registry:
      url: http://apicurio-registry:8080/apis/registry/v3
```

### Sử dụng `OutboxWriter`

```java
@Transactional
public void updateProgress(UUID taskId, UUID periodId, UUID reporterId, BigDecimal pct, long nextVersion) {
  taskRepository.updateProgress(taskId, pct, nextVersion);

  TienDoDaCapNhat payload = TienDoDaCapNhat.newBuilder()
      .setTaskId(taskId)
      .setPeriodId(periodId)
      .setProgressId(Ids.newUuidV7())
      .setReporterId(reporterId)
      .setProgressPct(pct)
      .setReportedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
      .setAggregateVersion(nextVersion)
      .build();

  outboxWriter.append(
      EventEnvelope.of(
          EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
          TenantContext.require(),
          "Task",
          taskId,
          nextVersion,
          payload,
          Classification.C1));
}
```

- `OutboxWriter.append(EventEnvelope)` kiểm tra đang có giao dịch mở (`TransactionSynchronizationManager.isActualTransactionActive()`), chặn sự kiện phân loại `C2`/`C3` (`SensitiveEventException`), tuần tự hoá payload sang Avro nhị phân kèm tra cứu `globalId` từ Apicurio Registry v3 (`ApicurioPayloadSerializer`, tắt `AUTO_REGISTER_ARTIFACT` và đệm kết quả theo `(topic, schema)`), và ghi cột `headers` (`jsonb`) chứa `traceparent`, `tenantid`, `actorid`, `classification`, `policyversion`, `aggregateversion`, `ce_type`, `occurredat`, `apicurio.value.globalId`.
- Khi giao dịch nghiệp vụ rollback, dòng `outbox_event` cũng rollback theo; khi schema chưa đăng ký hoặc Registry lỗi, `OutboxSerializationException` được ném ra làm rollback toàn bộ giao dịch.
- Cấu hình Debezium mẫu (`EventRouter` + SMT `OutboxHeadersTransform`) được đóng gói tại `classpath:vn/viettel/okrkpi/shared/outbox/debezium-outbox-connector.json`.

## Viết consumer (NEN-012)

Thư viện `vt-platform-starter` cung cấp `@InboxListener` và `InboxMessageProcessor` (`vn.viettel.okrkpi.shared.inbox`) để tiêu thụ sự kiện Kafka một cách lũy đẳng (idempotent) qua bảng `<schema>.inbox_processed` và `<schema>.inbox_aggregate_version`.

### Cấu hình dịch vụ tiêu thụ sự kiện

```yaml
okrkpi:
  inbox:
    schema: appraisal
    registry:
      url: http://apicurio-registry:8080/apis/registry/v3
    retry:
      in-place-attempts: 3
      initial-backoff: 50ms
      multiplier: 2.0
      tier-delays: [1m, 5m, 30m]
    purge:
      enabled: true
      cron: "0 15 3 * * *"
      retention: "14 days"
      batch-size: 5000
      max-batches-per-run: 20
```

### Khai báo `@InboxListener`

```java
@Component
public class AppraisalTaskProgressConsumer {

  @InboxListener(
      topic = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
      consumer = "svc-appraisal.tien-do",
      aggregateType = "Task")
  public void onTaskProgressed(InboxEnvelope<TienDoDaCapNhat> envelope) {
    TienDoDaCapNhat event = envelope.payload();
    // Chạy trong cùng giao dịch với INSERT inbox_processed, đã đặt sẵn TenantContext + SET LOCAL app.tenant_id + span OTel con
  }
}
```

### Quy trình xử lý trong `InboxMessageProcessor`

1. Khôi phục `TenantContext` và tạo span OpenTelemetry `CONSUMER` con từ Kafka header `traceparent`.
2. Giải tuần tự thân Avro qua `ApicurioPayloadDeserializer` (bật `apicurio.registry.headers.enabled=true`, đọc `apicurio.value.globalId`).
3. Trong một giao dịch CSDL (thử lại tại chỗ tối đa `in-place-attempts` lần cho lỗi tạm thời):
   - Gọi `TenantSql.applyTenantSession(jdbc, tenantId)` để đặt `SET LOCAL app.tenant_id`.
   - `INSERT INTO <schema>.inbox_processed (event_id, consumer, tenant_id, source_topic, kafka_partition, kafka_offset) VALUES (...) ON CONFLICT (consumer, event_id) DO NOTHING RETURNING event_id` — nếu không có dòng trả về, bỏ qua sự kiện trùng (`DUPLICATE_SKIPPED`) và tăng `inbox_messages_total{outcome="duplicate"}` + `inbox_duplicates_total`.
   - Upsert `<schema>.inbox_aggregate_version (consumer, aggregate_type, aggregate_id, tenant_id, last_version, last_event_id)` với điều kiện `WHERE inbox_aggregate_version.last_version < EXCLUDED.last_version` — nếu sự kiện đến có `aggregateVersion <= last_version`, bỏ qua sự kiện cũ đến muộn (`STALE_SKIPPED`, giữ dòng `inbox_processed`) và tăng `inbox_stale_skipped_total`.
   - Gọi phương thức `@InboxListener` để cập nhật bảng nghiệp vụ.
   - Commit giao dịch rồi mới gọi `ack.acknowledge()`.
4. Phân loại lỗi:
   - **Lỗi tạm thời** (`TransientInboxException`, `TransientDataAccessException`, `SQLTransientException`): sau khi hết số lần thử tại chỗ sẽ rollback giao dịch (xoá dòng `inbox_processed` vừa chèn) và phát sang `<topic>.retry.<consumer>` qua 3 tầng độ trễ (`x-inbox-retry-tier = 1..3`); hết tầng 3 chuyển sang `<topic>.dlq.<consumer>`.
   - **Lỗi vĩnh viễn** (`PermanentInboxException`, thiếu header, lỗi giải mã Avro): rollback và chuyển thẳng sang `<topic>.dlq.<consumer>` kèm header `x-inbox-dlq-reason`, `x-inbox-dlq-error-code`, `x-inbox-dlq-failed-at`, `x-inbox-dlq-consumer`, `x-inbox-dlq-original-topic`.
5. Tác vụ dọn dẹp `InboxPurgeJob` gọi `<schema>.fn_purge_inbox_processed(?::interval, ?)` theo lô hàng ngày để giữ bảng `inbox_processed` gọn nhẹ theo chính sách giữ 14 ngày của TL-03.

## Liên quan

- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
- [Thiết kế CSDL và đa khách thuê](17-thiet-ke-csdl-va-da-khach-thue.md)
- [Hạ tầng production và triển khai](19-ha-tang-production-va-trien-khai.md)
- [Bảo mật phía máy chủ](20-bao-mat-phia-may-chu.md)
- [Kiến trúc mission-core và microservice](21-kien-truc-mission-core-va-microservice.md)
- `docs/quy-dinh/BACKEND.md`
