package vn.viettel.okrkpi.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.replica.application.OrgReplicaService;
import vn.viettel.okrkpi.replica.infrastructure.listener.OrgReplicaListeners;
import vn.viettel.okrkpi.replica.infrastructure.persistence.JdbcOrgDirectory;
import vn.viettel.okrkpi.replica.infrastructure.persistence.JdbcOrgReplicaStore;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Kiểm thử tích hợp consumer bản sao tổ chức (CORE-00-03) trên PostgreSQL thật với đầy đủ vai trò
 * và migration: chống áp phiên bản cũ, bao đóng hai cây khi đổi cấp trên, vòng lặp vào thư chết và
 * đo thời gian áp 10.000 sự kiện.
 *
 * <p>Payload mã hoá Avro nhị phân bằng chính lớp sinh từ hợp đồng; bộ giải mã thay Apicurio bằng
 * tra lớp theo topic để kiểm thử không cần registry. Kafka thay bằng {@link KafkaOperations} giả,
 * ghi lại thông điệp gửi sang topic thư chết.
 */
class OrgReplicaConsumerIT {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
  private static final Map<String, Class<? extends SpecificRecord>> TYPES =
      Map.of(
          EventTopics.EVT_14_DON_VI_DA_THAY_DOI, DonViDaThayDoi.class,
          EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI, PhanCongDaThayDoi.class);

  private static JdbcTemplate jdbc;
  private static JdbcOrgDirectory directory;
  private static TenantDataSourceTransactionManager tm;
  private static InboxMessageProcessor processor;
  private static InboxMetrics metrics;
  private static InboxEndpoint unitEndpoint;
  private static InboxEndpoint assignmentEndpoint;
  private static final List<ProducerRecord<String, byte[]>> SENT = new CopyOnWriteArrayList<>();
  private static long offset;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void setUp() throws Exception {
    EventContracts.trustContractPackages();
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(4);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=org_replica");
    ds.setUsername("svc_org_replica");
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    jdbc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null));
    directory = new JdbcOrgDirectory(jdbc, tm);

    MeterRegistry meters = new SimpleMeterRegistry();
    metrics = new InboxMetrics(meters);
    var service =
        new OrgReplicaService(new JdbcOrgReplicaStore(jdbc), new NoPeriods(), event -> {});
    var listeners =
        new OrgReplicaListeners(
            service,
            new StaticListableBeanFactory(Map.of("meters", meters))
                .getBeanProvider(MeterRegistry.class));
    unitEndpoint = endpoint(listeners, "onUnit");
    assignmentEndpoint = endpoint(listeners, "onAssignment");

    KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(ProducerRecord.class)))
        .thenAnswer(
            inv -> {
              SENT.add(inv.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    processor =
        new InboxMessageProcessor(
            jdbc,
            tm,
            "org_replica",
            (topic, headers, data) -> decode(TYPES.get(topic), data),
            new InboxRetryDlqPublisher(kafka),
            metrics,
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
  }

  @Test
  void phienBanCuDenSauKhongGhiDePhienBanMoi() {
    UUID unit = UUID.randomUUID();
    assertThat(sendUnit(unit, null, 5, "Tên mới")).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(sendUnit(unit, null, 4, "Tên cũ")).isEqualTo(InboxOutcome.STALE_SKIPPED);

    assertThat(unitName(unit)).isEqualTo("Tên mới");
    assertThat(sourceVersion(unit)).isEqualTo(5L);
  }

  @Test
  void doiCapTrenCapNhatBaoDongCaCayCon() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID d = UUID.randomUUID();
    sendUnit(a, null, 1, "A");
    sendUnit(b, a, 1, "B");
    sendUnit(c, b, 1, "C");
    sendUnit(d, c, 1, "D");
    assertThat(directory.ancestorsOf(TENANT, OrgTree.DANG, d)).containsExactly(c, b, a);

    // Chuyển C (kèm D) từ dưới B lên thẳng dưới A.
    assertThat(sendUnit(c, a, 2, "C")).isEqualTo(InboxOutcome.PROCESSED);

    assertThat(directory.ancestorsOf(TENANT, OrgTree.DANG, d)).containsExactly(c, a);
    assertThat(directory.ancestorsOf(TENANT, OrgTree.CHUYEN_MON, d)).containsExactly(c, a);
    assertThat(directory.parentOf(TENANT, OrgTree.DANG, c)).contains(a);
    assertThat(directory.descendantsOf(TENANT, OrgTree.DANG, b, 0)).isEmpty();
    assertThat(directory.descendantsOf(TENANT, OrgTree.DANG, a, 0))
        .containsExactlyInAnyOrder(b, c, d);
    assertThat(directory.isDescendant(TENANT, OrgTree.DANG, b, d)).isFalse();
  }

  @Test
  void capTrenTaoVongLapDiThangVaoThuChet() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    sendUnit(a, null, 1, "A");
    sendUnit(b, a, 1, "B");
    SENT.clear();

    assertThat(sendUnit(a, b, 2, "A")).isEqualTo(InboxOutcome.DLQ);

    assertThat(SENT).hasSize(1);
    assertThat(SENT.getFirst().topic())
        .isEqualTo(
            InboxRetryDlqPublisher.dlqTopic(
                EventTopics.EVT_14_DON_VI_DA_THAY_DOI, "org-replica.unit"));
    assertThat(directory.parentOf(TENANT, OrgTree.DANG, a)).isEmpty();
    assertThat(sourceVersion(a)).isEqualTo(1L);
  }

  @Test
  void muoiNghinSuKienCoP99DuoiNamGiay() {
    int units = 2_000;
    int assignments = 8_000;
    List<UUID> ids = new ArrayList<>();
    List<Long> latencies = new ArrayList<>(units + assignments);
    long started = System.nanoTime();
    for (int i = 0; i < units; i++) {
      UUID id = UUID.randomUUID();
      UUID parent = ids.isEmpty() ? null : ids.get((i * 7919) % ids.size());
      latencies.add(timed(() -> sendUnit(id, parent, 1, "Đơn vị " + id)));
      ids.add(id);
    }
    for (int i = 0; i < assignments; i++) {
      UUID unit = ids.get(i % ids.size());
      latencies.add(timed(() -> sendAssignment(UUID.randomUUID(), UUID.randomUUID(), unit)));
    }
    long totalMs = (System.nanoTime() - started) / 1_000_000;

    Collections.sort(latencies);
    long p50 = latencies.get(latencies.size() / 2) / 1_000;
    long p99 = latencies.get((int) Math.ceil(latencies.size() * 0.99) - 1) / 1_000;
    Long lagMs =
        queryInTenant("select max(lag_ms) from org_replica.replica_checkpoint", Long.class);
    // Số đo thật in ra nhật ký kiểm thử để đưa vào báo cáo task.
    System.out.printf(
        "CORE-00-03 đo tải: %d sự kiện trong %d ms; mỗi sự kiện p50=%d µs p99=%d µs;"
            + " lag_ms checkpoint lớn nhất=%d%n",
        latencies.size(), totalMs, p50, p99, lagMs);

    assertThat(
            metrics.count(
                "org-replica.assignment",
                EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
                InboxOutcome.PROCESSED))
        .isGreaterThanOrEqualTo(assignments);
    assertThat(p99).isLessThan(5_000_000L);
  }

  // ==================== Dựng sự kiện ====================

  private static InboxOutcome sendUnit(UUID id, UUID parent, long version, String name) {
    DonViDaThayDoi payload =
        DonViDaThayDoi.newBuilder()
            .setUnitId(id)
            .setVersionId(UUID.randomUUID())
            .setSourceVersion(version)
            .setCode("DV-" + id.toString().substring(0, 8))
            .setName(name)
            .setShortName(null)
            .setUnitType("CHI_BO")
            .setSubjectGroupId(null)
            .setIsCollectiveSubject(false)
            .setFlows(List.of())
            .setLevel("CO_SO")
            .setValidFrom(FROM)
            .setValidTo(null)
            .setChangeOrderId(null)
            .setAggregateVersion((int) version)
            .setPartyParentId(parent)
            .setAdminParentId(parent)
            .build();
    return processor.process(
        unitEndpoint,
        record(EventTopics.EVT_14_DON_VI_DA_THAY_DOI, id, version, "DonViDaThayDoi", payload),
        () -> {});
  }

  private static InboxOutcome sendAssignment(UUID id, UUID person, UUID unit) {
    PhanCongDaThayDoi payload =
        PhanCongDaThayDoi.newBuilder()
            .setAssignmentId(id)
            .setPersonId(person)
            .setUnitId(unit)
            .setPositionId(null)
            .setJobTitleId(UUID.randomUUID())
            .setKind("CHINH_THUC")
            .setIsPrimary(true)
            .setIsHead(false)
            .setValidFrom(FROM)
            .setValidTo(null)
            .setSourceVersion(1L)
            .setAggregateVersion(1)
            .build();
    return processor.process(
        assignmentEndpoint,
        record(EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI, id, 1, "PhanCongDaThayDoi", payload),
        () -> {});
  }

  private static ConsumerRecord<String, byte[]> record(
      String topic, UUID aggregateId, long version, String type, SpecificRecord payload) {
    var record =
        new ConsumerRecord<>(
            topic, 0, offset++, TENANT.value() + ":" + aggregateId, encode(payload));
    header(record, OutboxHeaders.EVENT_ID, UUID.randomUUID().toString());
    header(record, OutboxHeaders.TENANT_ID, TENANT.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, Long.toString(version));
    header(record, OutboxHeaders.CE_TYPE, type);
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return record;
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static byte[] encode(SpecificRecord payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter(payload.getClass()).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static SpecificRecord decode(Class<? extends SpecificRecord> type, byte[] data) {
    try {
      return new SpecificDatumReader<>(type)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static InboxEndpoint endpoint(Object bean, String name) throws Exception {
    for (Method m : bean.getClass().getMethods()) {
      if (m.getName().equals(name)) {
        return InboxEndpoint.fromMethod(bean, m, m.getAnnotation(InboxListener.class));
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static long timed(Runnable action) {
    long t0 = System.nanoTime();
    action.run();
    return System.nanoTime() - t0;
  }

  private static String unitName(UUID id) {
    return queryInTenant(
        "select name from org_replica.org_unit_v where id = '" + id + "'", String.class);
  }

  private static Long sourceVersion(UUID id) {
    return queryInTenant(
        "select source_version from org_replica.org_unit_v where id = '" + id + "'", Long.class);
  }

  /** Đọc trong ngữ cảnh tenant (RLS ép buộc với {@code svc_org_replica}). */
  private static <T> T queryInTenant(String sql, Class<T> type) {
    try {
      return TenantContext.call(
          TENANT, () -> new TransactionTemplate(tm).execute(s -> jdbc.queryForObject(sql, type)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Không có kỳ đánh giá nào trong kiểm thử: phân công không bao giờ rơi vào kỳ đã khoá. */
  private static final class NoPeriods implements PeriodApi {
    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
      return Optional.empty();
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) {}
  }
}
