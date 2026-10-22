package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.api.AnomalyAlertQueryApi;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyAlertIntake;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.infrastructure.listener.AnomalyDetectedListener;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.AnomalyAlertConfiguration;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.JdbcAnomalyAlertRepository;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.analytics.v1.DauHieuBatThuong;

/**
 * CORE-07-10 trên PostgreSQL thật: EVT-20 đi qua bộ xử lý inbox của schema oversight (thân Avro
 * thay bằng bộ giải mã kiểm thử). Nhận trùng mã sự kiện chỉ tạo một cảnh báo; minh chứng trùng bị
 * chặn qua {@link AnomalyAlertQueryApi} cho tới khi nhận {@code HET_DAU_HIEU}; chuỗi bằng chứng chỉ
 * giữ mã. Thân sai hợp đồng (vào thư chết) kiểm ở {@code AnomalyDetectedListenerTest}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.oversight.anomaly-digest.enabled=false"
    })
class AnomalyAlertInboxIT {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private JdbcAnomalyAlertRepository repository;
  @Autowired private AlertHandlers handlers;
  @Autowired private AnomalyAlertQueryApi queries;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private DauHieuBatThuong current;

  @Test
  void duplicateEventsMakeOneAlertAndEvidenceStaysBlockedUntilResolved() throws Exception {
    inTenant(
        jdbc -> {
          for (String t : List.of("anomaly_alert", "alert_action")) {
            jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
                .params("oversight." + t, 20264)
                .query()
                .listOfRows();
          }
          return null;
        });
    PeriodApi periods =
        new PeriodApi() {
          @Override
          public Optional<PeriodSnapshot> findCurrent(TenantId t) {
            return Optional.empty();
          }

          @Override
          public Optional<PeriodSnapshot> findById(TenantId t, PeriodId id) {
            return Optional.of(
                new PeriodSnapshot(
                    id,
                    t,
                    "2026-Q4",
                    "Quý IV/2026",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.MO));
          }

          @Override
          public void assertOpen(TenantId t, PeriodId p) {}
        };
    AnomalyDetectedListener listener =
        new AnomalyDetectedListener(
            new AnomalyAlertIntake(
                repository, periods, handlers, null, java.time.Clock.systemUTC()),
            new com.fasterxml.jackson.databind.ObjectMapper());
    InboxMessageProcessor processor =
        AnomalyAlertConfiguration.oversightInboxProcessor(
            dataSource, tm, inboxProperties, (topic, headers, data) -> current, dlq, metrics);
    InboxEndpoint endpoint = AnomalyAlertConfiguration.anomalyEndpoint(listener);

    UUID unit = UUID.randomUUID();
    UUID mc1 = UUID.randomUUID();
    UUID mc2 = UUID.randomUUID();
    String chain =
        "[\"ky:%s\",\"evidence:%s\",\"evidence:%s\",\"sha256:5e88a1f4\",\"Nguyễn Văn A\"]"
            .formatted(period, mc1, mc2);
    current = event("CAO", unit, chain);
    UUID eventId = UUID.randomUUID();
    assertThat(processor.process(endpoint, record(eventId), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(endpoint, record(eventId), () -> {}))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    // Sự kiện khác mã nhưng cùng khoá chống trùng: không tạo cảnh báo thứ hai.
    assertThat(processor.process(endpoint, record(UUID.randomUUID()), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(alerts()).containsExactly("MOI:CAO");
    String evidence =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT evidence_chain::text FROM oversight.anomaly_alert")
                    .query(String.class)
                    .single());
    assertThat(evidence).doesNotContain("Nguyễn").contains("evidence:" + mc1);
    assertThat(blocked(List.of(mc1, mc2, UUID.randomUUID()))).containsExactlyInAnyOrder(mc1, mc2);

    current = event("HET_DAU_HIEU", unit, chain);
    assertThat(processor.process(endpoint, record(UUID.randomUUID()), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(alerts()).containsExactly("DONG:CAO");
    assertThat(blocked(List.of(mc1, mc2))).isEmpty();
  }

  private DauHieuBatThuong event(String severity, UUID unit, String chain) {
    return DauHieuBatThuong.newBuilder()
        .setAlertKey("k-" + unit)
        .setSignalCode("MINH_CHUNG_TRUNG")
        .setSeverity(severity)
        .setSubjectType("DON_VI")
        .setSubjectId(unit)
        .setEvidenceChain(chain)
        .setModelVersion("2026.10.3")
        .build();
  }

  private List<String> alerts() throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT status || ':' || severity FROM oversight.anomaly_alert")
                .query(String.class)
                .list());
  }

  private java.util.Set<UUID> blocked(List<UUID> ids) throws Exception {
    return TenantContext.call(tenant, () -> queries.blockedEvidence(tenant, ids));
  }

  private ConsumerRecord<String, byte[]> record(UUID eventId) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(
            EventTopics.EVT_20_DAU_HIEU_BAT_THUONG, 0, 0L, tenant.value() + ":k", new byte[] {0});
    r.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(eventId.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(tenant.value().toString())));
    return r;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private <T> T inTenant(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }
}
