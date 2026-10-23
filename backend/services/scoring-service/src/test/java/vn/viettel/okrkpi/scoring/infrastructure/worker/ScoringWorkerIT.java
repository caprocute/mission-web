package vn.viettel.okrkpi.scoring.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.support.TransactionTemplate;
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
import vn.viettel.okrkpi.scoring.application.IncrementalScoring;
import vn.viettel.okrkpi.scoring.application.InputAssembler;
import vn.viettel.okrkpi.scoring.application.ProfileRecheck;
import vn.viettel.okrkpi.scoring.application.ScoreJobRunner;
import vn.viettel.okrkpi.scoring.application.ScoreJobWorker;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.policy.RatingClassifier;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.application.port.ScoringInputs;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.infrastructure.events.IncrementalScoringConsumers;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.CopyScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreChunkQueue;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcStoredScores;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;
import vn.viettel.okrkpi.scoring.infrastructure.policy.JdbcOpenPeriods;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;
import vn.vtokr.product.v1.MinhChungDaXacNhan;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/**
 * Tính dần theo sự kiện (DV-07-04) và tính lại hàng loạt theo lô (DV-07-06) trên PostgreSQL 17
 * thật, kết nối {@code svc_scoring}: EVT-06, EVT-08, EVT-09, EVT-22 qua inbox nhóm {@code
 * scoring-incremental} xếp tác vụ; worker chia lô theo trang đầu vào của lõi (giả lập), ghi kết quả
 * và EVT-10 đúng một lần; hồ sơ không đổi không phát lại; mở lại hậu kiểm chỉ tính phạm vi; trang
 * đầu vào lỗi thử lại ba lần rồi vào hàng đợi lỗi; tác vụ đối chiếu {@code KIEM_TRA_TAI_LAP} cho
 * cùng băm kết quả mà không phát sự kiện. Kafka thay bằng gọi thẳng bộ xử lý inbox.
 */
class ScoringWorkerIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20271;
  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");
  private static final int PAGE = 2;

  private static PostgreSQLContainer<?> pg;
  private static FlowableDecisionTables tables;
  private static PolicyContent pack;
  private static InboxMessageProcessor processor;
  private static final Map<String, InboxEndpoint> ENDPOINTS = new HashMap<>();
  private static ScoreJobWorker worker;
  private static ScoreRecorder recorder;
  private static ProfileRecheck rechecks;
  private static TransactionTemplate tx;
  private static long offset;

  /** Đầu vào của lõi theo kỳ, trả theo trang {@value #PAGE} hồ sơ; kỳ trong {@code FAILING} lỗi. */
  private static final Map<UUID, List<CoreScoringInput>> INPUTS = new ConcurrentHashMap<>();

  private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();
  private static final AtomicInteger READS = new AtomicInteger();

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void start() throws Exception {
    EventContracts.trustContractPackages();
    pg = TestPostgres.container("td_scoring_worker");
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
      for (String t :
          List.of(
              "score_job",
              "score_input_v",
              "score_result",
              "score_trace",
              "score_job_chunk",
              "score_job_error")) {
        st.execute(
            "SELECT scoring.fn_create_period_partitions('scoring." + t + "', " + BUCKET + ")");
      }
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), "svc_scoring", PASSWORD);
    var tm = new TenantDataSourceTransactionManager(ds, TenantProperties.defaults());
    tx = new TransactionTemplate(tm);
    JdbcClient jdbc = JdbcClient.create(ds);
    JdbcTemplate svc = new JdbcTemplate(ds);

    tables = new FlowableDecisionTables();
    pack = SamplePolicyPack.content();
    PolicyPackSource source =
        new PolicyPackSource() {
          @Override
          public java.util.Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
            return "DANG".equals(key.flow())
                ? java.util.Optional.of(pack.pinned())
                : java.util.Optional.empty();
          }

          @Override
          public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
            return pack;
          }
        };
    PolicyPackCache cache =
        new PolicyPackCache(
            source,
            new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables),
            4);
    OutboxPayloadSerializer serializer =
        (topic, payload) -> new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    OutboxWriter outbox =
        new OutboxWriter(svc, "scoring", serializer, new ObjectMapper(), Clock.systemUtc());
    JdbcScoreJobStore jobs = new JdbcScoreJobStore(jdbc);
    JdbcStoredScores stored = new JdbcStoredScores(jdbc);
    recorder =
        new ScoreRecorder(jobs, new CopyScoreResultStore(svc), new OutboxScoreEvents(outbox));
    ScoringInputs inputs =
        (tenant, period, cursor, limit) -> {
          READS.incrementAndGet();
          if (FAILING.contains(period)) {
            throw new IllegalStateException("Lõi trả 503");
          }
          List<CoreScoringInput> all = INPUTS.getOrDefault(period, List.of());
          int from = cursor == null ? 0 : Integer.parseInt(cursor);
          int to = Math.min(from + PAGE, all.size());
          return new ScoringInputs.Page(
              List.copyOf(all.subList(from, to)), to < all.size() ? Integer.toString(to) : null);
        };
    worker =
        new ScoreJobWorker(
            new JdbcScoreChunkQueue(jdbc),
            jobs,
            inputs,
            new ScoreJobRunner(
                cache,
                new InputAssembler(),
                new ProfileScorer(new ScoreCalculator(), new RatingClassifier()),
                stored,
                java.time.Clock.systemUTC()),
            recorder,
            tx,
            Duration.ofMinutes(10),
            Duration.ZERO);
    rechecks = new ProfileRecheck(stored, jobs, new ProfileRecheck.Guard());
    IncrementalScoringConsumers consumers =
        new IncrementalScoringConsumers(
            new IncrementalScoring(stored, new JdbcOpenPeriods(jdbc), jobs, cache));
    for (String name :
        List.of(
            "onTaskStatusChanged", "onEvidenceAccepted", "onPeriodLocked", "onPeriodReopened")) {
      var method = consumers.getClass().getMethod(name, InboxEnvelope.class);
      InboxListener l = method.getAnnotation(InboxListener.class);
      ENDPOINTS.put(l.topic(), InboxEndpoint.fromMethod(consumers, method, l));
    }
    KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    processor =
        new InboxMessageProcessor(
            svc,
            tm,
            "scoring",
            (topic, headers, data) -> decode(topic, data),
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
  void evt06TinhDanChiHoSoBiAnhHuongVaPhatEvt10MotLan() throws Exception {
    UUID period = UUID.randomUUID();
    List<CoreScoringInput> profiles = profiles(3, "0.75");
    INPUTS.put(period, profiles);
    UUID first = openJob(period, ScoreJobKind.TAM_TINH, JobScope.of("x"));
    drain();
    assertThat(job(first)).isEqualTo("XONG:3:3:0");
    assertThat(chunks(first)).isEqualTo("1:XONG,2:XONG");
    assertThat(events(period)).isEqualTo(3);

    // Nhiệm vụ của hồ sơ thứ nhất được nghiệm thu với mức chất lượng mới.
    CoreScoringInput a = profiles.get(0);
    UUID task = a.workItems().get(0).taskId();
    INPUTS.put(period, List.of(withQuality(a, "1"), profiles.get(1), profiles.get(2)));
    UUID event = UUID.randomUUID();
    long t0 = System.nanoTime();
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI),
                evt06(event, task, "HOAN_THANH"),
                () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI),
                evt06(event, task, "HOAN_THANH"),
                () -> {}))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    // Sự kiện thứ hai của cùng nhiệm vụ gộp vào tác vụ đang chờ; chuyển về nháp không xếp gì.
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI),
                evt06(UUID.randomUUID(), task, "CHO_NGHIEM_THU"),
                () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI),
                evt06(UUID.randomUUID(), task, "NHAP"),
                () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_job WHERE period_id = '"
                    + period
                    + "' AND status = 'CHO'"))
        .isEqualTo(1);
    drain();
    long millis = (System.nanoTime() - t0) / 1_000_000;
    assertThat(events(period)).isEqualTo(4);
    assertThat(
            adminString(
                "SELECT string_agg(aggregate_version::text, ',' ORDER BY aggregate_version)"
                    + " FROM scoring.outbox_event WHERE aggregate_id = '"
                    + a.profileId()
                    + "'"))
        .isEqualTo("1,2");
    assertThat(
            adminString(
                "SELECT j.scope->>'ly_do' || ':' || j.status || ':' || j.total_items"
                    + " FROM scoring.score_job j WHERE j.period_id = '"
                    + period
                    + "' AND j.id <> '"
                    + first
                    + "'"))
        .isEqualTo(IncrementalScoring.REASON_EVENT + ":XONG:1");

    // EVT-08 của hồ sơ thứ hai khi đầu vào không đổi: tính nhưng không ghi, không phát.
    UUID taskB = profiles.get(1).workItems().get(0).taskId();
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN), evt08(taskB), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    drain();
    assertThat(events(period)).isEqualTo(4);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_job WHERE period_id = '"
                    + period
                    + "' AND status <> 'XONG'"))
        .isZero();
    System.out.printf(
        Locale.ROOT, "DO_DAC DV-07-04: EVT-06 den EVT-10 (inbox, 1 lo, 3 ho so) %d ms%n", millis);
  }

  @Test
  void evt09ChotKyNhieuLoRoiEvt22ChiTinhLaiPhamVi() throws Exception {
    UUID period = UUID.randomUUID();
    List<CoreScoringInput> profiles = profiles(5, "0.75");
    INPUTS.put(period, profiles);
    UUID lock = UUID.randomUUID();
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_09_KY_DA_KHOA), evt09(lock, period), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    drain();
    UUID chot =
        UUID.fromString(
            adminString(
                "SELECT id::text FROM scoring.score_job WHERE trigger_event_id = '" + lock + "'"));
    assertThat(job(chot)).isEqualTo("XONG:5:5:0");
    assertThat(chunks(chot)).isEqualTo("1:XONG,2:XONG,3:XONG");
    assertThat(events(period)).isEqualTo(5);

    CoreScoringInput x = profiles.get(3);
    INPUTS.put(
        period,
        List.of(
            withQuality(profiles.get(0), "1"),
            profiles.get(1),
            profiles.get(2),
            withQuality(x, "1"),
            profiles.get(4)));
    UUID reopen = UUID.randomUUID();
    assertThat(
            processor.process(
                endpoint(EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM),
                evt22(reopen, period, x.profileId()),
                () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    drain();
    UUID hauKiem =
        UUID.fromString(
            adminString(
                "SELECT id::text FROM scoring.score_job WHERE trigger_event_id = '"
                    + reopen
                    + "'"));
    assertThat(job(hauKiem)).isEqualTo("XONG:1:1:0");
    assertThat(
            adminString(
                "SELECT string_agg(DISTINCT profile_id::text, ',') FROM scoring.score_result"
                    + " WHERE score_job_id = '"
                    + hauKiem
                    + "'"))
        .isEqualTo(x.profileId().toString());
    assertThat(events(period)).isEqualTo(6);
  }

  @Test
  void trangDauVaoLoiThuLaiBaLanRoiVaoHangDoiLoi() throws Exception {
    UUID period = UUID.randomUUID();
    FAILING.add(period);
    int before = READS.get();
    UUID job = openJob(period, ScoreJobKind.TAM_TINH, JobScope.of("x"));
    drain();
    assertThat(READS.get() - before).isEqualTo(3);
    assertThat(adminString("SELECT status FROM scoring.score_job WHERE id = '" + job + "'"))
        .isEqualTo("LOI");
    assertThat(chunks(job)).isEqualTo("1:LOI");
    assertThat(
            adminString(
                "SELECT reason_code || ':' || attempts || ':' || (profile_id IS NULL)"
                    + " FROM scoring.score_job_error WHERE score_job_id = '"
                    + job
                    + "'"))
        .isEqualTo("DOC_DAU_VAO_LOI:3:true");
    FAILING.remove(period);
  }

  @Test
  void doiChieuTaiLapChoCungBamKhongPhatSuKien() throws Exception {
    UUID period = UUID.randomUUID();
    List<CoreScoringInput> profiles = profiles(1, "0.75");
    INPUTS.put(period, profiles);
    UUID original = openJob(period, ScoreJobKind.CHOT_KY, JobScope.of("x"));
    drain();
    UUID profile = profiles.get(0).profileId();
    String originalHash =
        adminString(
            "SELECT encode(result_hash, 'hex') FROM scoring.score_result WHERE score_job_id = '"
                + original
                + "'");
    long eventsBefore = events(period);

    UUID check =
        TenantContext.call(
            TenantId.of(TENANT), () -> tx.execute(s -> rechecks.request(profile, ACTOR)));
    drain();
    assertThat(job(check)).as(errors(check)).isEqualTo("XONG:1:1:0");
    assertThat(
            adminString(
                "SELECT scope->>'recheckOf' FROM scoring.score_job WHERE id = '" + check + "'"))
        .isEqualTo(original.toString());
    assertThat(
            adminString(
                "SELECT encode(result_hash, 'hex') FROM scoring.score_result WHERE score_job_id = '"
                    + check
                    + "'"))
        .isEqualTo(originalHash);
    assertThat(events(period)).isEqualTo(eventsBefore);
    // Ảnh chụp hiện hành vẫn là kết quả gốc.
    UUID again =
        TenantContext.call(
            TenantId.of(TENANT), () -> tx.execute(s -> rechecks.request(profile, ACTOR)));
    assertThat(
            adminString(
                "SELECT scope->>'recheckOf' FROM scoring.score_job WHERE id = '" + again + "'"))
        .isEqualTo(original.toString());
    drain();
  }

  // --- Dựng dữ liệu ---

  private static List<CoreScoringInput> profiles(int n, String quality) {
    List<CoreScoringInput> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(
          new CoreScoringInput(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "DANG",
              UUID.randomUUID(),
              null,
              false,
              false,
              pack.pinned().versionId(),
              List.of(new CoreScoringInput.Criterion("TCC-01", true)),
              List.of(item("THEN_CHOT", quality), item("THUONG_XUYEN", "1")),
              0));
    }
    return out;
  }

  private static CoreScoringInput.WorkItem item(String group, String quality) {
    return new CoreScoringInput.WorkItem(
        UUID.randomUUID(),
        null,
        new BigDecimal("100"),
        3,
        group,
        "TR1",
        "HOAN_THANH",
        new BigDecimal(quality),
        0,
        0,
        false,
        false,
        false);
  }

  private static CoreScoringInput withQuality(CoreScoringInput in, String quality) {
    List<CoreScoringInput.WorkItem> items = new ArrayList<>(in.workItems());
    CoreScoringInput.WorkItem w = items.get(0);
    items.set(
        0,
        new CoreScoringInput.WorkItem(
            w.taskId(),
            w.parentTaskId(),
            w.standardScore(),
            w.difficulty(),
            w.taskGroup(),
            w.axisCode(),
            w.statusAtLock(),
            new BigDecimal(quality),
            w.subjectiveLateWorkdays(),
            w.objectiveLateWorkdays(),
            w.exceeded(),
            w.outOfScope(),
            w.deferredByDecision()));
    return new CoreScoringInput(
        in.profileId(),
        in.personId(),
        in.flow(),
        in.orgUnitId(),
        in.subjectGroupId(),
        in.head(),
        in.leadership(),
        in.policyPackVersionId(),
        in.generalCriteria(),
        items,
        in.incidents());
  }

  private static UUID openJob(UUID period, ScoreJobKind kind, JobScope scope) throws Exception {
    ScoreJob job =
        new ScoreJob(
            TENANT,
            Ids.newUuidV7(),
            period,
            BUCKET,
            kind,
            scope.toDoc(),
            pack.pinned().versionId(),
            null,
            0,
            ACTOR);
    return TenantContext.call(TenantId.of(TENANT), () -> tx.execute(s -> recorder.open(job)));
  }

  /** Chạy worker đến khi không còn tác vụ chờ và lô đến hạn. */
  private static void drain() {
    for (int i = 0; i < 100; i++) {
      int started = worker.startWaiting(10);
      boolean ran = worker.runOne(10);
      if (started == 0 && !ran) {
        return;
      }
    }
    throw new AssertionError("Worker không dừng sau 100 vòng");
  }

  // --- Sự kiện ---

  private static InboxEndpoint endpoint(String topic) {
    return ENDPOINTS.get(topic);
  }

  private static ConsumerRecord<String, byte[]> evt06(UUID eventId, UUID task, String to) {
    return record(
        EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI,
        eventId,
        task,
        "TrangThaiNhiemVuDoi",
        TrangThaiNhiemVuDoi.newBuilder()
            .setTaskId(task)
            .setFromStatus("DANG_THUC_HIEN")
            .setToStatus(to)
            .setIsOverdue(false)
            .setReasonCode(null)
            .setChangedBy(ACTOR)
            .setAggregateVersion(2)
            .build());
  }

  private static ConsumerRecord<String, byte[]> evt08(UUID task) {
    return record(
        EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN,
        UUID.randomUUID(),
        task,
        "MinhChungDaXacNhan",
        MinhChungDaXacNhan.newBuilder()
            .setEvidenceId(UUID.randomUUID())
            .setVersionId(UUID.randomUUID())
            .setTaskId(task)
            .setQualityLevel(new BigDecimal("0.75"))
            .setAcceptedBy(ACTOR)
            .setAcceptedAt(Instant.now())
            .setAggregateVersion(1)
            .build());
  }

  private static ConsumerRecord<String, byte[]> evt09(UUID eventId, UUID period) {
    return record(
        EventTopics.EVT_09_KY_DA_KHOA,
        eventId,
        period,
        "KyDaKhoa",
        KyDaKhoa.newBuilder()
            .setPeriodId(period)
            .setPeriodBucket(BUCKET)
            .setLockedAt(Instant.now())
            .setSagaId(UUID.randomUUID())
            .setPolicyPackVersionIds(List.of(pack.pinned().versionId()))
            .setAggregateVersion(1)
            .build());
  }

  private static ConsumerRecord<String, byte[]> evt22(UUID eventId, UUID period, UUID profile) {
    return record(
        EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
        eventId,
        period,
        "KyDaMoLaiHauKiem",
        KyDaMoLaiHauKiem.newBuilder()
            .setPeriodId(period)
            .setReopenRequestId(UUID.randomUUID())
            .setScope("HO_SO")
            .setScopeIds(List.of(profile))
            .setApproverIds(List.of(ACTOR, UUID.randomUUID()))
            .setExecutedAt(Instant.now())
            .setAggregateVersion(2)
            .build());
  }

  private static ConsumerRecord<String, byte[]> record(
      String topic, UUID eventId, UUID aggregate, String type, SpecificRecordBase payload) {
    var r = new ConsumerRecord<>(topic, 0, offset++, TENANT + ":" + aggregate, encode(payload));
    header(r, OutboxHeaders.EVENT_ID, eventId.toString());
    header(r, OutboxHeaders.TENANT_ID, TENANT.toString());
    header(r, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(r, OutboxHeaders.CE_TYPE, type);
    header(r, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(r, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return r;
  }

  private static void header(ConsumerRecord<String, byte[]> r, String key, String value) {
    r.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static byte[] encode(SpecificRecordBase payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecordBase>((Class<SpecificRecordBase>) payload.getClass())
          .write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static org.apache.avro.specific.SpecificRecord decode(String topic, byte[] data) {
    Class<? extends SpecificRecordBase> type =
        switch (topic) {
          case EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI -> TrangThaiNhiemVuDoi.class;
          case EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN -> MinhChungDaXacNhan.class;
          case EventTopics.EVT_09_KY_DA_KHOA -> KyDaKhoa.class;
          default -> KyDaMoLaiHauKiem.class;
        };
    try {
      return new SpecificDatumReader<>(type)
          .read(null, DecoderFactory.get().binaryDecoder(data, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- Đọc kết quả bằng quyền quản trị ---

  private static String job(UUID id) throws SQLException {
    return adminString(
        "SELECT status || ':' || total_items || ':' || done_items || ':' || failed_items"
            + " FROM scoring.score_job WHERE id = '"
            + id
            + "'");
  }

  private static String errors(UUID job) throws SQLException {
    return adminString(
        "SELECT string_agg(reason_code || ': ' || detail, '; ') FROM scoring.score_job_error"
            + " WHERE score_job_id = '"
            + job
            + "'");
  }

  private static String chunks(UUID job) throws SQLException {
    return adminString(
        "SELECT string_agg(seq || ':' || status, ',' ORDER BY seq) FROM scoring.score_job_chunk"
            + " WHERE score_job_id = '"
            + job
            + "'");
  }

  private static long events(UUID period) throws SQLException {
    return adminLong(
        "SELECT count(*) FROM scoring.outbox_event o WHERE o.topic = 'scoring.score.computed.v1'"
            + " AND o.aggregate_id IN (SELECT profile_id FROM scoring.score_result WHERE period_id = '"
            + period
            + "')");
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
