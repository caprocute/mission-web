package vn.viettel.okrkpi.scoring.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaOperations;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPublishedHandler;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.policy.v1.PolicyPackDaPhatHanh;

/**
 * Đổi công thức không cần phát hành phần mềm (DV-07-02) trên PostgreSQL 17 thật, kết nối {@code
 * svc_scoring}: dịch vụ đang chạy tính kỳ theo phiên bản 2.1.0; MOD-05 phát hành 2.2.0 đổi công
 * thức KPI nhiệm vụ và phát EVT-13; consumer qua inbox {@code scoring.inbox_processed} nạp và biên
 * dịch trước 2.2.0, mở một tác vụ tính lại cho kỳ đang mở ghim 2.2.0 (kỳ đã chốt không bị đụng), và
 * lần tính sau dùng công thức mới mà không đổi mã, không khởi động lại. EVT-13 trùng không nạp lại.
 * Kafka thay bằng gọi thẳng bộ xử lý inbox; thân Avro mã hoá bằng lớp sinh từ hợp đồng.
 */
class PolicyPackPublishedIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20271;
  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  private static final UUID OPEN = UUID.fromString("0192f1a2-0000-7000-8000-0000000000b1");
  private static final UUID CLOSED = UUID.fromString("0192f1a2-0000-7000-8000-0000000000b2");
  private static final UUID PACK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a01");
  private static final String NEW_FORMULA = "(kpi_A + kpi_B + 2.0 * kpi_C) / 4.0";

  private static PostgreSQLContainer<?> pg;
  private static InboxMessageProcessor processor;
  private static InboxEndpoint endpoint;
  private static PolicyPackCache cache;
  private static FlowableDecisionTables tables;
  private static final Map<PolicyBindingKey, PinnedPolicy> BINDINGS = new HashMap<>();
  private static final Map<UUID, PolicyContent> CONTENTS = new HashMap<>();
  private static long offset;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void start() throws Exception {
    EventContracts.trustContractPackages();
    pg = TestPostgres.container("td_scoring_policy");
    pg.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles("scoring")) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = admin()) {
      DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
      bootstrap.applySchemas(admin, List.of("scoring"));
      bootstrap.setTenancyModel(admin, "pool");
    }
    new SchemaMigrator(MigrationOptions.defaults())
        .migrate(
            pg.getJdbcUrl(),
            "scoring",
            new MigrationCredentials.Credentials("mig_scoring", PASSWORD));
    try (Connection admin = admin();
        Statement st = admin.createStatement()) {
      for (String t : List.of("score_job", "score_input_v", "score_result", "score_trace")) {
        st.execute(
            "SELECT scoring.fn_create_period_partitions('scoring." + t + "', " + BUCKET + ")");
      }
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), "svc_scoring", PASSWORD);
    var tm = new TenantDataSourceTransactionManager(ds, TenantProperties.defaults());
    tables = new FlowableDecisionTables();
    PolicyPackSource source =
        new PolicyPackSource() {
          @Override
          public java.util.Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
            return java.util.Optional.ofNullable(BINDINGS.get(key));
          }

          @Override
          public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
            return CONTENTS.get(versionId);
          }
        };
    cache =
        new PolicyPackCache(
            source,
            new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables),
            8);
    PolicyPackPublishedConsumer consumer =
        new PolicyPackPublishedConsumer(
            new PolicyPublishedHandler(
                cache,
                new JdbcOpenPeriods(JdbcClient.create(ds)),
                new JdbcScoreJobStore(JdbcClient.create(ds))));
    var method =
        consumer
            .getClass()
            .getMethod("onPublished", vn.viettel.okrkpi.shared.inbox.InboxEnvelope.class);
    endpoint =
        InboxEndpoint.fromMethod(consumer, method, method.getAnnotation(InboxListener.class));
    KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    processor =
        new InboxMessageProcessor(
            new JdbcTemplate(ds),
            tm,
            "scoring",
            (topic, headers, data) -> decode(data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new SimpleMeterRegistry()),
            new InboxProperties.Retry(1, null, null, null),
            Clock.systemUtc());
  }

  @AfterAll
  static void stop() {
    tables.destroy();
    pg.stop();
  }

  @Test
  void evt13DoiCongThucNapKhongKhoiDongLaiVaMoTacVuTinhLai() throws Exception {
    PolicyContent v1 = SamplePolicyPack.content(PACK, UUID.randomUUID(), "2.1.0", Map.of());
    PolicyContent v2 =
        SamplePolicyPack.content(
            PACK, UUID.randomUUID(), "2.2.0", Map.of("kpi_nhiem_vu", NEW_FORMULA));
    CONTENTS.put(v1.pinned().versionId(), v1);
    CONTENTS.put(v2.pinned().versionId(), v2);
    PolicyBindingKey open = PolicyBindingKey.of(TENANT, OPEN, "DANG");
    PolicyBindingKey closed = PolicyBindingKey.of(TENANT, CLOSED, "DANG");
    BINDINGS.put(open, v1.pinned());
    BINDINGS.put(closed, v1.pinned());
    seedResult(OPEN, false);
    seedResult(CLOSED, true);
    Map<String, Object> kpi = Map.of("kpi_A", 1.0, "kpi_B", 0.5, "kpi_C", 0.8);

    long t0 = System.nanoTime();
    CompiledPolicy before = cache.forBinding(open);
    long compileNanos = System.nanoTime() - t0;
    assertThat(before.evaluate("kpi_nhiem_vu", kpi)).isEqualTo((1.0 + 0.5 + 0.8) / 3.0);
    assertThat(cache.forBinding(closed)).isSameAs(before);
    assertThat(cache.compilations()).isEqualTo(1);

    // MOD-05 phát hành 2.2.0 cho kỳ đang mở; kỳ đã chốt giữ 2.1.0.
    BINDINGS.put(open, v2.pinned());
    UUID event = UUID.randomUUID();
    assertThat(processor.process(endpoint, evt13(event, v2), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(cache.compilations()).isEqualTo(2);
    assertThat(processor.process(endpoint, evt13(event, v2), () -> {}))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(cache.compilations()).isEqualTo(2);

    assertThat(
            adminString(
                "SELECT period_id || ':' || job_kind || ':' || status || ':' || policy_pack_version_id"
                    + " FROM scoring.score_job WHERE trigger_event_id = '"
                    + event
                    + "'"))
        .isEqualTo(OPEN + ":TAM_TINH:CHO:" + v2.pinned().versionId());
    assertThat(
            adminLong("SELECT count(*) FROM scoring.score_job WHERE trigger_event_id IS NOT NULL"))
        .isEqualTo(1);

    CompiledPolicy after = cache.forBinding(open);
    assertThat(after.contentHash()).isEqualTo(v2.pinned().contentHash());
    assertThat(after.evaluate("kpi_nhiem_vu", kpi)).isEqualTo((1.0 + 0.5 + 1.6) / 4.0);
    assertThat(cache.forBinding(closed)).isSameAs(before);
    assertThat(cache.compilations()).isEqualTo(2);

    // Số đo cho báo cáo: biên dịch một gói, đánh giá một hồ sơ 25 nhiệm vụ khi trúng đệm.
    int profiles = 2_000;
    long t1 = System.nanoTime();
    for (int i = 0; i < profiles; i++) {
      oneProfile(cache.forBinding(open));
    }
    double perProfileMicros = (System.nanoTime() - t1) / 1_000.0 / profiles;
    System.out.printf(
        Locale.ROOT,
        "DO_DAC DV-07-02: bien dich goi %.1f ms; danh gia 1 ho so (25 nhiem vu, 1 bang DMN) %.1f us%n",
        compileNanos / 1_000_000.0,
        perProfileMicros);
  }

  @Test
  void phienBanSaiKieuVaoThuChet() {
    PolicyContent bad =
        SamplePolicyPack.content(PACK, UUID.randomUUID(), "9.9.9", Map.of("kpi_A", "'x' + 1"));
    CONTENTS.put(bad.pinned().versionId(), bad);
    assertThat(processor.process(endpoint, evt13(UUID.randomUUID(), bad), () -> {}))
        .isEqualTo(InboxOutcome.DLQ);
    assertThat(cache.cached(bad.pinned().contentHash())).isEmpty();
  }

  private static void oneProfile(CompiledPolicy p) {
    Map<String, Object> tham = p.params();
    List<Double> kpis = new ArrayList<>(25);
    for (int i = 0; i < 25; i++) {
      Map<String, Object> nv = Map.of("ke_hoach", 4L, "so_luong_duyet", (long) (i % 5));
      double a = (Double) p.evaluate("kpi_A", Map.of("nv", nv));
      kpis.add(
          (Double) p.evaluate("kpi_nhiem_vu", Map.of("kpi_A", a, "kpi_B", 0.75, "kpi_C", 1.0)));
    }
    assertThat(tham).isNotNull();
    Map<String, Object> ho = new HashMap<>();
    ho.put("diem", kpis.stream().mapToDouble(Double::doubleValue).sum() * 4);
    ho.put("muc_tieu_chi_chung", "DAM_BAO");
    ho.put("loai_tru", false);
    ho.put("ty_le_O", 1.0);
    ho.put("dat_ty_le_vuot", false);
    ho.put("giai_phap_dot_pha_da_kiem_chung", false);
    ho.put("truot_then_chot_chu_quan", false);
    assertThat(p.decide("xep_loai", ho)).containsKey("xep_loai");
  }

  private static ConsumerRecord<String, byte[]> evt13(UUID eventId, PolicyContent v) {
    PolicyPackDaPhatHanh payload =
        PolicyPackDaPhatHanh.newBuilder()
            .setPackId(PACK)
            .setVersionId(v.pinned().versionId())
            .setContentHash(ByteBuffer.wrap(HexFormat.of().parseHex(v.pinned().contentHash())))
            .setEffectiveFrom(null)
            .setApprovedBy(UUID.randomUUID())
            .setTestRunId(UUID.randomUUID())
            .setAggregateVersion(1)
            .build();
    var record =
        new ConsumerRecord<>(
            EventTopics.EVT_13_POLICY_PACK_DA_PHAT_HANH,
            0,
            offset++,
            TENANT + ":" + v.pinned().versionId(),
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, TENANT.toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(record, OutboxHeaders.CE_TYPE, "PolicyPackDaPhatHanh");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return record;
  }

  private static void header(ConsumerRecord<String, byte[]> r, String key, String value) {
    r.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] encode(PolicyPackDaPhatHanh payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>(PolicyPackDaPhatHanh.class).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static PolicyPackDaPhatHanh decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(PolicyPackDaPhatHanh.class)
          .read(null, DecoderFactory.get().binaryDecoder(data, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Một kết quả tính của kỳ; {@code chot} thêm tác vụ CHOT_KY đã xong (kỳ đã chốt). */
  private static void seedResult(UUID period, boolean chot) throws SQLException {
    UUID job = UUID.randomUUID();
    String hash = "decode(repeat('ab', 32), 'hex')";
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO scoring.score_job (id, tenant_id, created_by, updated_by, period_id,"
              + " period_bucket, job_kind, scope, policy_pack_version_id, status) VALUES ('"
              + job
              + "', '"
              + TENANT
              + "', '"
              + TENANT
              + "', '"
              + TENANT
              + "', '"
              + period
              + "', "
              + BUCKET
              + ", '"
              + (chot ? "CHOT_KY" : "TAM_TINH")
              + "', '{}', '"
              + PACK
              + "', 'XONG')");
      st.execute(
          "INSERT INTO scoring.score_result (id, tenant_id, created_by, updated_by, period_id,"
              + " period_bucket, score_job_id, person_id, flow, profile_id, input_hash,"
              + " policy_pack_version_id, policy_content_hash, engine_version, result_doc,"
              + " result_hash, status, computed_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + TENANT
              + "', '"
              + TENANT
              + "', '"
              + TENANT
              + "', '"
              + period
              + "', "
              + BUCKET
              + ", '"
              + job
              + "', '"
              + UUID.randomUUID()
              + "', 'DANG', '"
              + UUID.randomUUID()
              + "', "
              + hash
              + ", '"
              + PACK
              + "', "
              + hash
              + ", 'thu', '{}', "
              + hash
              + ", 'OK', now())");
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static String adminString(String sql) throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static long adminLong(String sql) throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
