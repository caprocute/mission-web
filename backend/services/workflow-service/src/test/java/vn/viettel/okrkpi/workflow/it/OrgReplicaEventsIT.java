package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalCommand;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;

/**
 * Bản sao đơn vị và nhân sự của workflow-service từ EVT-14 {@code org.unit.changed.v1}, EVT-16
 * {@code org.person.changed.v1} và {@code org.bootstrap.v1} (DV-06-10) trên PostgreSQL thật: bộ xử
 * lý inbox thật của thư viện nền chạy bằng {@code svc_flowable} (RLS bật), Kafka thay bằng bản ghi
 * dựng tay. Khẳng định áp sự kiện, trùng bị bỏ qua, bản cũ không ghi đè, tenant khác không lẫn,
 * vòng trên cây tổ chức vào thư chết mà không ghi, và phân giải cấp duyệt chạy trên dữ liệu nạp qua
 * sự kiện.
 */
class OrgReplicaEventsIT extends WorkflowIntegrationTest {

  private static final LocalDate FROM = LocalDate.of(2020, 1, 1);

  @Autowired private InboxListenerRegistrar inboxRegistrar;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private OrgDirectory directory;
  @Autowired private StartApprovalService starter;
  @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private final UUID tenant = UUID.randomUUID();
  private final UUID otherTenant = UUID.randomUUID();

  @SuppressWarnings("unchecked")
  private final KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);

  private long offset;

  // --- Tiện ích ----------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  InboxMessageProcessor inboxProcessor() {
    EventContracts.trustContractPackages();
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        transactionManager,
        "flowable",
        (topic, headers, data) ->
            switch (topic) {
              case EventTopics.ORG_BOOTSTRAP_V1 -> decode(OrgBootstrap.class, data);
              case EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI -> decode(NhanSuDaThayDoi.class, data);
              default -> decode(DonViDaThayDoi.class, data);
            },
        new InboxRetryDlqPublisher(kafka),
        new InboxMetrics(new SimpleMeterRegistry()),
        new InboxProperties.Retry(1, null, null, null),
        vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
  }

  /** Endpoint áp bản sao của topic (bỏ consumer đối soát R1 cùng nghe org.bootstrap.v1). */
  InboxEndpoint endpoint(String topic) {
    return inboxRegistrar.endpoints().stream()
        .filter(e -> e.topic().equals(topic) && !e.consumer().endsWith(".reconcile"))
        .findFirst()
        .orElseThrow();
  }

  static DonViDaThayDoi donVi(
      UUID id, String type, UUID partyParent, UUID adminParent, String name, long version) {
    return DonViDaThayDoi.newBuilder()
        .setUnitId(id)
        .setVersionId(UUID.randomUUID())
        .setSourceVersion(version)
        .setCode("DV-" + id.toString().substring(0, 8))
        .setName(name)
        .setUnitType(type)
        .setLevel("TINH")
        .setFlows(List.of("DANG", "NHA_NUOC"))
        .setValidFrom(FROM)
        .setValidTo(null)
        .setAggregateVersion((int) version)
        .setPartyParentId(partyParent)
        .setAdminParentId(adminParent)
        .build();
  }

  static NhanSuDaThayDoi nhanSu(UUID id, String name, String status, long version) {
    return NhanSuDaThayDoi.newBuilder()
        .setPersonId(id)
        .setDisplayName(name)
        .setIsPartyMember(true)
        .setStatus(status)
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .build();
  }

  static OrgBootstrap bootstrap(SpecificRecord entity, int seq) {
    return OrgBootstrap.newBuilder()
        .setBatchId(UUID.randomUUID())
        .setSeq(seq)
        .setIsLast(false)
        .setEntity(entity)
        .build();
  }

  ConsumerRecord<String, byte[]> record(
      String topic, UUID tenantId, UUID aggregateId, long version, SpecificRecord payload) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(topic, 0, offset++, tenantId + ":" + aggregateId, encode(payload));
    r.headers().add(OutboxHeaders.EVENT_ID, bytes(UUID.randomUUID().toString()));
    r.headers().add(OutboxHeaders.TENANT_ID, bytes(tenantId.toString()));
    r.headers().add(OutboxHeaders.AGGREGATE_VERSION, bytes(Long.toString(version)));
    r.headers().add(OutboxHeaders.CE_TYPE, bytes(payload.getSchema().getName()));
    r.headers().add(OutboxHeaders.CLASSIFICATION, bytes(Classification.C1.name()));
    r.headers().add(OutboxHeaders.OCCURRED_AT, bytes(Instant.now().toString()));
    return r;
  }

  /** Áp EVT-14 cho tenant, trả kết quả của inbox. */
  InboxOutcome unitEvent(InboxMessageProcessor p, UUID tenantId, DonViDaThayDoi u) {
    return p.process(
        endpoint(EventTopics.EVT_14_DON_VI_DA_THAY_DOI),
        record(
            EventTopics.EVT_14_DON_VI_DA_THAY_DOI,
            tenantId,
            u.getUnitId(),
            u.getAggregateVersion(),
            u),
        null);
  }

  /** Nạp ban đầu một thực thể cho tenant. */
  InboxOutcome bootstrapEvent(
      InboxMessageProcessor p, UUID tenantId, UUID entityId, SpecificRecord entity) {
    return p.process(
        endpoint(EventTopics.ORG_BOOTSTRAP_V1),
        record(EventTopics.ORG_BOOTSTRAP_V1, tenantId, entityId, 1, bootstrap(entity, 1)),
        null);
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  static byte[] encode(SpecificRecord payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecord>((Class<SpecificRecord>) payload.getClass())
          .write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static <T extends SpecificRecord> T decode(Class<T> type, byte[] data) {
    try {
      return new SpecificDatumReader<>(type)
          .read(null, DecoderFactory.get().binaryDecoder(data, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Map<String, Object> unitRow(UUID tenantId, UUID unitId) {
    return owner.queryForMap(
        "SELECT name, unit_type, party_parent_id, admin_parent_id, valid_to::text AS valid_to,"
            + " source_version FROM org_replica.org_unit_v WHERE tenant_id = ? AND id = ?",
        tenantId,
        unitId);
  }

  Map<String, Object> personRow(UUID tenantId, UUID personId) {
    return owner.queryForMap(
        "SELECT full_name, is_party_member, employment_status, cbccvc_code, source_version"
            + " FROM org_replica.person_v WHERE tenant_id = ? AND id = ?",
        tenantId,
        personId);
  }

  /** Đọc đơn vị qua cổng của luồng duyệt, bằng {@code svc_flowable} trong giao dịch (RLS). */
  Optional<OrgUnit> readAsService(UUID tenantId, UUID unitId) {
    return new TransactionTemplate(transactionManager)
        .execute(s -> directory.unit(tenantId, unitId, LocalDate.now()));
  }

  // ------------------------------------------------------------------ kiểm thử

  @Test
  void apDonViNhanSuTrungBoQuaBanCuKhongGhiDeTenantKhacKhongLan() {
    InboxMessageProcessor processor = inboxProcessor();
    assertThat(endpoint(EventTopics.EVT_14_DON_VI_DA_THAY_DOI).consumer())
        .isEqualTo("workflow.org-replica.unit");
    assertThat(endpoint(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI).consumer())
        .isEqualTo("workflow.org-replica.person");

    // EVT-14: áp, rồi đúng thông điệp đó lần nữa bị bỏ qua.
    UUID unit = UUID.randomUUID();
    UUID parent = UUID.randomUUID();
    DonViDaThayDoi v5 = donVi(unit, "CHI_BO", parent, null, "Chi bộ Văn phòng", 5);
    ConsumerRecord<String, byte[]> first =
        record(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, tenant, unit, 5, v5);
    InboxEndpoint evt14 = endpoint(EventTopics.EVT_14_DON_VI_DA_THAY_DOI);
    assertThat(processor.process(evt14, first, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(evt14, first, null)).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(unitRow(tenant, unit))
        .containsEntry("name", "Chi bộ Văn phòng")
        .containsEntry("unit_type", "CHI_BO")
        .containsEntry("party_parent_id", parent)
        .containsEntry("valid_to", "infinity")
        .containsEntry("source_version", 5L);

    // Bản cũ: qua topic thường inbox bỏ theo thứ tự phiên bản; qua nạp ban đầu thì upsert theo
    // source_version không ghi đè.
    assertThat(unitEvent(processor, tenant, donVi(unit, "CHI_BO", null, null, "Tên cũ", 3)))
        .isEqualTo(InboxOutcome.STALE_SKIPPED);
    assertThat(bootstrapEvent(processor, tenant, unit, donVi(unit, "CHI_BO", null, null, "Cũ", 4)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(unitRow(tenant, unit))
        .containsEntry("name", "Chi bộ Văn phòng")
        .containsEntry("source_version", 5L);

    // EVT-16: áp, trùng bỏ qua, bản cũ qua nạp ban đầu không ghi đè, bản mới ghi đè.
    UUID person = UUID.randomUUID();
    NhanSuDaThayDoi p2 = nhanSu(person, "Nguyễn Văn An", "DANG_CONG_TAC", 2);
    ConsumerRecord<String, byte[]> personRecord =
        record(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, tenant, person, 2, p2);
    InboxEndpoint evt16 = endpoint(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI);
    assertThat(processor.process(evt16, personRecord, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(evt16, personRecord, null))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(bootstrapEvent(processor, tenant, person, nhanSu(person, "Tên cũ", "THOI_VIEC", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(personRow(tenant, person))
        .containsEntry("full_name", "Nguyễn Văn An")
        .containsEntry("is_party_member", true)
        .containsEntry("employment_status", "DANG_CONG_TAC")
        .containsEntry("source_version", 2L);
    assertThat(personRow(tenant, person).get("cbccvc_code")).isNull();
    assertThat(
            processor.process(
                evt16,
                record(
                    EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
                    tenant,
                    person,
                    3,
                    nhanSu(person, "Nguyễn Văn An", "NGHI_HUU", 3)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(personRow(tenant, person)).containsEntry("employment_status", "NGHI_HUU");

    // Tenant khác: dòng riêng, mỗi tenant chỉ thấy đơn vị của mình qua RLS. Mã đơn vị là UUID
    // duy nhất toàn hệ thống; thứ tự phiên bản của inbox khoá theo (consumer, loại, mã) không gồm
    // tenant, nên kiểm thử không dùng lại mã của tenant kia.
    UUID foreign = UUID.randomUUID();
    assertThat(unitEvent(processor, otherTenant, donVi(foreign, "CO_QUAN", null, null, "Sở X", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(unitRow(otherTenant, foreign))
        .containsEntry("name", "Sở X")
        .containsEntry("source_version", 1L);
    assertThat(readAsService(tenant, unit)).get().extracting(OrgUnit::unitType).isEqualTo("CHI_BO");
    assertThat(readAsService(otherTenant, foreign))
        .get()
        .extracting(OrgUnit::unitType)
        .isEqualTo("CO_QUAN");
    assertThat(readAsService(tenant, foreign)).isEmpty();
    assertThat(readAsService(otherTenant, unit)).isEmpty();
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM org_replica.person_v WHERE tenant_id = ?",
                Long.class,
                otherTenant))
        .isZero();

    // Mốc độ trễ theo tenant và loại thực thể (replica_checkpoint) và số đo org_replica_lag_ms.
    assertThat(
            owner.queryForList(
                "SELECT entity_type FROM org_replica.replica_checkpoint WHERE tenant_id = ?"
                    + " ORDER BY entity_type",
                String.class,
                tenant))
        .containsExactly("ORG_UNIT", "PERSON");
    assertThat(
            owner.queryForList(
                "SELECT entity_type FROM org_replica.replica_checkpoint WHERE tenant_id = ?",
                String.class,
                otherTenant))
        .containsExactly("ORG_UNIT");
    assertThat(
            owner.queryForObject(
                "SELECT lag_ms FROM org_replica.replica_checkpoint WHERE tenant_id = ?"
                    + " AND entity_type = 'PERSON'",
                Integer.class,
                tenant))
        .isNotNegative();
    assertThat(
            meters
                .find("org_replica_lag_ms")
                .tag("tenant_id", tenant.toString())
                .tag("entity_type", "PERSON")
                .gauge())
        .isNotNull();
  }

  @Test
  void vongTrenCayToChucVaoThuChetKhongGhi() {
    InboxMessageProcessor processor = inboxProcessor();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(unitEvent(processor, tenant, donVi(a, "TO_CHUC_DANG", null, null, "Đảng uỷ", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(unitEvent(processor, tenant, donVi(b, "CHI_BO", a, null, "Chi bộ", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);

    // a trực thuộc b trong khi b trực thuộc a: vòng, chuyển thẳng vào thư chết.
    DonViDaThayDoi cyclic = donVi(a, "TO_CHUC_DANG", b, null, "Đảng uỷ đổi", 2);
    assertThat(unitEvent(processor, tenant, cyclic)).isEqualTo(InboxOutcome.DLQ);
    verify(kafka)
        .send(
            argThat(
                (ProducerRecord<String, byte[]> r) ->
                    r.topic()
                        .equals(
                            EventTopics.EVT_14_DON_VI_DA_THAY_DOI
                                + ".dlq.workflow.org-replica.unit")));
    // Giao dịch huỷ: dòng giữ phiên bản cũ, inbox không ghi dấu đã xử lý.
    assertThat(unitRow(tenant, a))
        .containsEntry("name", "Đảng uỷ")
        .containsEntry("source_version", 1L);
    assertThat(unitRow(tenant, a).get("party_parent_id")).isNull();

    // Tự trực thuộc chính mình trên cây chuyên môn cũng vào thư chết; dữ liệu sai cũng vậy.
    assertThat(unitEvent(processor, tenant, donVi(b, "CHI_BO", a, b, "Chi bộ", 2)))
        .isEqualTo(InboxOutcome.DLQ);
    DonViDaThayDoi blank = donVi(UUID.randomUUID(), "CHI_BO", null, null, " ", 1);
    assertThat(unitEvent(processor, tenant, blank)).isEqualTo(InboxOutcome.DLQ);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM org_replica.org_unit_v WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo(2L);
  }

  @Test
  void phanGiaiCapDuyetTrenCayNapQuaSuKien() {
    InboxMessageProcessor processor = inboxProcessor();
    UUID dangUy = UUID.randomUUID();
    UUID chiBo = UUID.randomUUID();
    UUID chiBoMoi = UUID.randomUUID();
    UUID coQuan = UUID.randomUUID();
    UUID banA = UUID.randomUUID();
    // Nạp ban đầu: cây Đảng và cây chuyên môn (đơn vị, kèm một nhân sự).
    assertThat(
            bootstrapEvent(
                processor,
                tenant,
                dangUy,
                donVi(dangUy, "TO_CHUC_DANG", null, null, "Đảng uỷ khối", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            bootstrapEvent(
                processor, tenant, chiBo, donVi(chiBo, "CHI_BO", dangUy, null, "Chi bộ 1", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            bootstrapEvent(
                processor, tenant, coQuan, donVi(coQuan, "CO_QUAN", null, null, "Sở Nội vụ", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            bootstrapEvent(
                processor, tenant, banA, donVi(banA, "PHONG_BAN", chiBo, coQuan, "Phòng A", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    UUID person = UUID.randomUUID();
    assertThat(
            bootstrapEvent(
                processor, tenant, person, nhanSu(person, "Lê Thị Bình", "DANG_CONG_TAC", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(personRow(tenant, person)).containsEntry("full_name", "Lê Thị Bình");

    // Duyệt OKR luồng Đảng: cấp 1 là cấp trên trực tiếp trên cây Đảng của Phòng A.
    starter.start(command(banA, UUID.randomUUID()));
    assertThat(candidateUnits()).containsExactly(chiBo);

    // EVT-14: Phòng A chuyển sang chi bộ mới; hồ sơ khởi tạo sau đó phân giải theo cây mới.
    assertThat(unitEvent(processor, tenant, donVi(chiBoMoi, "CHI_BO", dangUy, null, "Chi bộ 2", 1)))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            unitEvent(processor, tenant, donVi(banA, "PHONG_BAN", chiBoMoi, coQuan, "Phòng A", 2)))
        .isEqualTo(InboxOutcome.PROCESSED);
    starter.start(command(banA, UUID.randomUUID()));
    assertThat(candidateUnits()).containsExactlyInAnyOrder(chiBo, chiBoMoi);
  }

  StartApprovalCommand command(UUID unitId, UUID businessId) {
    return new StartApprovalCommand(
        tenant,
        ApprovalTemplate.DUYET_OKR,
        businessId,
        1,
        ApprovalFlow.DANG,
        unitId,
        false,
        UUID.randomUUID(),
        null,
        "Phê duyệt Quý IV/2026",
        null,
        3,
        UUID.randomUUID(),
        "CHU_QUAN");
  }

  List<UUID> candidateUnits() {
    return owner.queryForList(
        "SELECT candidate_unit_id FROM flowable.wf_task_projection WHERE tenant_id = ?",
        UUID.class,
        tenant);
  }
}
