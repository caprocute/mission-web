package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.workflow.infrastructure.reconcile.OrgReplicaReconcileJob;
import vn.viettel.okrkpi.workflow.infrastructure.reconcile.OrgReplicaReconciler;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * R1 (DL-12) cho bản sao tổ chức của workflow-service trên PostgreSQL thật với migration V14 và tài
 * khoản {@code svc_flowable} (RLS bật): bản sao nạp qua bộ xử lý inbox thật, {@code KET_THUC} của
 * {@code org.bootstrap.v1} vào consumer đối soát riêng; khớp thì {@code KHOP}, cố ý sửa một dòng
 * thì {@code LOI} đúng một bucket kèm lệnh phát lại, phát lại xong thì {@code DA_SUA}; thiếu dòng ở
 * bản sao cũng là lệch. Băm phía nguồn tính độc lập theo công thức {@code Buckets} của org-service.
 */
class OrgReplicaReconcileIT extends WorkflowIntegrationTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

  @Autowired private InboxListenerRegistrar inboxRegistrar;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrgReplicaReconciler reconciler;
  @Autowired private MeterRegistry meters;
  @Autowired private ObjectMapper json;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private final UUID tenant = UUID.randomUUID();
  private final List<Row> source = new ArrayList<>();

  @SuppressWarnings("unchecked")
  private final KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);

  private InboxMessageProcessor processor;
  private OrgReplicaReconcileJob job;
  private long offset;

  record Row(String kind, UUID id, LocalDate validFrom, long sv) {}

  @BeforeEach
  void seed() {
    processor = inboxProcessor();
    // Bỏ thời gian lắng: đồng hồ của job đi trước một ngày.
    job =
        new OrgReplicaReconcileJob(
            reconciler,
            jdbc,
            transactionManager,
            Duration.ZERO,
            Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
    for (int i = 0; i < 2; i++) {
      UUID unit = UUID.randomUUID();
      apply(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, unit, 2, donVi(unit, 2));
      source.add(new Row("ORG_UNIT", unit, FROM, 2));
      for (int j = 0; j < 4; j++) {
        UUID person = UUID.randomUUID();
        apply(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI, person, 1, nhanSu(person, 1));
        source.add(new Row("PERSON", person, null, 1));
        UUID assignment = UUID.randomUUID();
        apply(
            EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
            assignment,
            3,
            phanCong(assignment, person, unit, 3));
        source.add(new Row("ASSIGNMENT", assignment, FROM, 3));
      }
    }
  }

  @Test
  void banSaoDungThiKhop() {
    UUID batch = endMarker(0, all(), null);
    assertThat(job.pendingTenants()).contains(tenant);
    assertThat(job.runFor(List.of(tenant))).isEqualTo(1);
    Map<String, Object> run = run(batch);
    assertThat(run)
        .containsEntry("status", "KHOP")
        .containsEntry("job", OrgReplicaReconciler.JOB)
        .containsEntry("entity_type", OrgReplicaReconciler.ALL_KINDS)
        .containsEntry("buckets_total", 256)
        .containsEntry("buckets_mismatch", 0);
    JsonNode detail = detail(run);
    assertThat(detail.path("actualCounts").path("PERSON").asLong()).isEqualTo(8);
    assertThat(detail.path("actualCounts").path("ASSIGNMENT").asLong()).isEqualTo(8);
    assertThat(detail.path("actualCounts").path("ORG_UNIT").asLong()).isEqualTo(2);
    // Đã so thì không còn chờ.
    assertThat(job.pendingTenants()).doesNotContain(tenant);
    // Nhận lại cùng KET_THUC (inbox bỏ trùng; mã lần chạy tất định theo batchId) không ghi thêm.
    assertThat(runCount()).isEqualTo(1);
  }

  @Test
  void suaMotDongThiLechDungMotBucketRoiPhatLaiThiDaSua() {
    Row p = source.stream().filter(r -> r.kind().equals("PERSON")).findFirst().orElseThrow();
    int bucket = bucket(p.id());
    // Sửa trái phép trực tiếp trong CSDL: bản sao không còn khớp nguồn.
    owner.update(
        "UPDATE org_replica.person_v SET source_version = 99 WHERE tenant_id = ? AND id = ?",
        tenant,
        p.id());
    double before = mismatches();

    UUID nightly = endMarker(0, all(), null);
    assertThat(job.runFor(List.of(tenant))).isEqualTo(1);
    Map<String, Object> lech = run(nightly);
    assertThat(lech).containsEntry("status", "LOI").containsEntry("buckets_mismatch", 1);
    JsonNode detail = detail(lech);
    assertThat(detail.path("mismatched")).hasSize(1);
    assertThat(detail.path("mismatched").get(0).asInt()).isEqualTo(bucket);
    assertThat(detail.path("replay").asText())
        .isEqualTo("org-republish --tenant=" + tenant + " --bucket=" + bucket);
    assertThat(mismatches() - before).isEqualTo(1);

    // Nguồn phát lại riêng bucket lệch qua org.bootstrap.v1 với sourceVersion lớn hơn bản đã bị
    // sửa; consumer bản sao áp, consumer đối soát nhận KET_THUC và lần so mới khớp.
    source.replaceAll(r -> r.id().equals(p.id()) ? new Row("PERSON", p.id(), null, 100) : r);
    List<Row> inBucket = source.stream().filter(r -> bucket(r.id()) == bucket).toList();
    for (Row r : inBucket) {
      if (r.kind().equals("PERSON")) {
        assertThat(bootstrapEntity(r.id(), nhanSu(r.id(), r.sv())))
            .isEqualTo(InboxOutcome.PROCESSED);
      }
    }
    UUID replay = endMarker(inBucket.size(), List.of(bucket), null);
    job.runFor(List.of(tenant));
    Map<String, Object> sua = run(replay);
    assertThat(sua)
        .containsEntry("status", "DA_SUA")
        .containsEntry("rows_repaired", inBucket.size())
        .containsEntry("buckets_total", 1)
        .containsEntry("buckets_mismatch", 0);
  }

  @Test
  void thieuDongOBanSaoCungLaLech() {
    UUID missing = UUID.randomUUID();
    source.add(new Row("ORG_UNIT", missing, FROM, 1));
    UUID batch = endMarker(0, all(), List.of("ORG_UNIT"));
    job.runFor(List.of(tenant));
    Map<String, Object> run = run(batch);
    assertThat(run).containsEntry("status", "LOI").containsEntry("entity_type", "ORG_UNIT");
    assertThat(detail(run).path("replay").asText())
        .endsWith("--entity=unit --bucket=" + bucket(missing));
  }

  @Test
  void ketThucThieuBamVaoThuChetThongDiepThucTheKhongTaoLanChay() {
    UUID batch = UUID.randomUUID();
    OrgBootstrap bad =
        OrgBootstrap.newBuilder()
            .setBatchId(batch)
            .setSeq(1)
            .setIsLast(true)
            .setEntity(null)
            .setMarker("KET_THUC")
            .setEntityCounts(Map.of("PERSON", 1L))
            .setBucketHashes(Map.of())
            .build();
    assertThat(processor.process(reconcileEndpoint(), bootRecord(batch, bad), null))
        .isEqualTo(InboxOutcome.DLQ);
    // Thông điệp thực thể và BAT_DAU: consumer đối soát bỏ qua.
    UUID person = source.get(1).id();
    OrgBootstrap entity =
        OrgBootstrap.newBuilder()
            .setBatchId(batch)
            .setSeq(2)
            .setIsLast(false)
            .setEntity(nhanSu(person, 1))
            .build();
    assertThat(processor.process(reconcileEndpoint(), bootRecord(person, entity), null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(runCount()).isZero();
    assertThat(job.runFor(List.of(tenant))).isZero();
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Gửi {@code KET_THUC} vào consumer đối soát; trả mã lần chạy. */
  private UUID endMarker(long entities, List<Integer> buckets, List<String> kinds) {
    UUID batch = UUID.randomUUID();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String k : kinds == null ? List.of("ORG_UNIT", "PERSON", "ASSIGNMENT") : kinds) {
      counts.put(k, source.stream().filter(r -> r.kind().equals(k)).count());
    }
    Map<String, String> hashes = new LinkedHashMap<>(sourceHashes(buckets, kinds));
    OrgBootstrap end =
        OrgBootstrap.newBuilder()
            .setBatchId(batch)
            .setSeq(entities + 1)
            .setIsLast(true)
            .setEntity(null)
            .setMarker("KET_THUC")
            .setEntityCounts(counts)
            .setBucketHashes(hashes)
            .build();
    ConsumerRecord<String, byte[]> r = bootRecord(batch, end);
    assertThat(processor.process(reconcileEndpoint(), r, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(reconcileEndpoint(), r, null))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    return OrgReplicaReconciler.runId(batch);
  }

  /** Băm nguồn theo đúng {@code Buckets.Accumulator} của org-service (wiki trang 18). */
  private Map<String, String> sourceHashes(List<Integer> buckets, List<String> kinds) {
    long[] sums = new long[256];
    for (Row r : source) {
      if (kinds == null || kinds.contains(r.kind())) {
        byte[] h =
            sha(
                r.kind()
                    + "|"
                    + r.id()
                    + "|"
                    + (r.validFrom() == null ? "-" : r.validFrom())
                    + "|"
                    + r.sv());
        long v = 0;
        for (int i = 0; i < 8; i++) {
          v = (v << 8) | (h[i] & 0xffL);
        }
        sums[bucket(r.id())] += v;
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    buckets.forEach(b -> out.put(Integer.toString(b), HexFormat.of().toHexDigits(sums[b])));
    return out;
  }

  private static int bucket(UUID id) {
    return sha(id.toString())[0] & 0xff;
  }

  private static byte[] sha(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Integer> all() {
    return IntStream.range(0, 256).boxed().toList();
  }

  private Map<String, Object> run(UUID id) {
    return owner.queryForMap(
        "SELECT job, entity_type, status, buckets_total, buckets_mismatch, rows_repaired,"
            + " detail::text AS detail FROM org_replica.reconcile_run WHERE id = ? AND tenant_id = ?",
        id,
        tenant);
  }

  private long runCount() {
    return owner.queryForObject(
        "SELECT count(*) FROM org_replica.reconcile_run WHERE tenant_id = ?", Long.class, tenant);
  }

  private JsonNode detail(Map<String, Object> run) {
    try {
      return json.readTree((String) run.get("detail"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private double mismatches() {
    Counter c =
        meters
            .find(ReconcileMetrics.MISMATCHES)
            .tag("reconcile", OrgReplicaReconciler.JOB)
            .counter();
    return c == null ? 0 : c.count();
  }

  @SuppressWarnings("unchecked")
  private InboxMessageProcessor inboxProcessor() {
    EventContracts.trustContractPackages();
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        transactionManager,
        "flowable",
        (topic, headers, data) ->
            switch (topic) {
              case EventTopics.ORG_BOOTSTRAP_V1 -> decode(OrgBootstrap.class, data);
              case EventTopics.EVT_14_DON_VI_DA_THAY_DOI -> decode(DonViDaThayDoi.class, data);
              case EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI -> decode(NhanSuDaThayDoi.class, data);
              default -> decode(PhanCongDaThayDoi.class, data);
            },
        new InboxRetryDlqPublisher(kafka),
        new InboxMetrics(new SimpleMeterRegistry()),
        new InboxProperties.Retry(1, null, null, null),
        vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
  }

  private InboxEndpoint endpoint(String topic) {
    return inboxRegistrar.endpoints().stream()
        .filter(e -> e.topic().equals(topic) && !e.consumer().endsWith(".reconcile"))
        .findFirst()
        .orElseThrow();
  }

  private InboxEndpoint reconcileEndpoint() {
    return inboxRegistrar.endpoints().stream()
        .filter(e -> e.consumer().equals("workflow.org-replica.reconcile"))
        .findFirst()
        .orElseThrow();
  }

  private void apply(String topic, UUID id, long version, SpecificRecord payload) {
    assertThat(processor.process(endpoint(topic), record(topic, id, version, payload), null))
        .isEqualTo(InboxOutcome.PROCESSED);
  }

  private InboxOutcome bootstrapEntity(UUID id, SpecificRecord entity) {
    OrgBootstrap m =
        OrgBootstrap.newBuilder()
            .setBatchId(UUID.randomUUID())
            .setSeq(1)
            .setIsLast(false)
            .setEntity(entity)
            .build();
    return processor.process(endpoint(EventTopics.ORG_BOOTSTRAP_V1), bootRecord(id, m), null);
  }

  private ConsumerRecord<String, byte[]> bootRecord(UUID key, OrgBootstrap payload) {
    return record(EventTopics.ORG_BOOTSTRAP_V1, key, 1, payload);
  }

  private ConsumerRecord<String, byte[]> record(
      String topic, UUID aggregateId, long version, SpecificRecord payload) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(topic, 0, offset++, tenant + ":" + aggregateId, encode(payload));
    r.headers().add(OutboxHeaders.EVENT_ID, bytes(UUID.randomUUID().toString()));
    r.headers().add(OutboxHeaders.TENANT_ID, bytes(tenant.toString()));
    r.headers().add(OutboxHeaders.AGGREGATE_VERSION, bytes(Long.toString(version)));
    r.headers().add(OutboxHeaders.CE_TYPE, bytes(payload.getSchema().getName()));
    r.headers().add(OutboxHeaders.CLASSIFICATION, bytes(Classification.C1.name()));
    r.headers().add(OutboxHeaders.OCCURRED_AT, bytes(Instant.now().toString()));
    return r;
  }

  static DonViDaThayDoi donVi(UUID id, long version) {
    return DonViDaThayDoi.newBuilder()
        .setUnitId(id)
        .setVersionId(UUID.randomUUID())
        .setSourceVersion(version)
        .setCode("DV-" + id.toString().substring(0, 8))
        .setName("Đơn vị " + id.toString().substring(0, 4))
        .setUnitType("CO_QUAN")
        .setLevel("TINH")
        .setFlows(List.of("NHA_NUOC"))
        .setValidFrom(FROM)
        .setValidTo(null)
        .setAggregateVersion((int) version)
        .build();
  }

  static NhanSuDaThayDoi nhanSu(UUID id, long version) {
    return NhanSuDaThayDoi.newBuilder()
        .setPersonId(id)
        .setDisplayName("Cán bộ kiểm thử")
        .setIsPartyMember(true)
        .setStatus("DANG_CONG_TAC")
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .build();
  }

  static PhanCongDaThayDoi phanCong(UUID id, UUID person, UUID unit, long version) {
    return PhanCongDaThayDoi.newBuilder()
        .setAssignmentId(id)
        .setPersonId(person)
        .setUnitId(unit)
        .setPositionId(null)
        .setJobTitleId(UUID.randomUUID())
        .setKind("BO_NHIEM")
        .setIsPrimary(true)
        .setValidFrom(FROM)
        .setValidTo(null)
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .setIsHead(false)
        .build();
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
}
