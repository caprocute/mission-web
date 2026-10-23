package vn.viettel.okrkpi.task.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerEntryLookup;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerPayloadHash;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSourceReconciler;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * R2 (DL-12) trên PostgreSQL thật, schema {@code task} với vai trò {@code svc_task}; sổ cái thay
 * bằng bảng băm theo mã sự kiện. Khớp thì {@code KHOP}; lệch băm, thiếu bản ghi sổ cái, dòng nguồn
 * bị sửa ngoài luồng đều {@code LOI} với loại lệch trong {@code detail}; sổ cái không phản hồi thì
 * {@code LOI} không tính là lệch; chạy lại cùng ngày không ghi trùng.
 */
class TaskLedgerReconcileIT {

  private static final int BUCKET = 20_373;
  private static final LocalDate DAY = LocalDate.of(2026, 10, 22);
  private static final Instant FROM = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
  private static final Instant TO = FROM.plus(Duration.ofDays(1));
  private static final Instant NOW = TO.plus(Duration.ofHours(2));
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private static JdbcTemplate jdbc;
  private static TenantDataSourceTransactionManager tm;
  private static SimpleMeterRegistry meters;
  private static final Map<UUID, String> LEDGER = new ConcurrentHashMap<>();
  private static volatile boolean ledgerDown;
  private static LedgerSourceReconciler reconciler;

  private TenantId tenant;

  @BeforeAll
  static void setUp() {
    EventContracts.trustContractPackages();
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(3);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=task");
    ds.setUsername("svc_task");
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    jdbc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null));
    meters = new SimpleMeterRegistry();
    LedgerEntryLookup ledger =
        (t, agg, from, to) -> {
          if (ledgerDown) {
            throw new DependencyUnavailableException(
                "ledger-service", DependencyUnavailableException.Reason.IO_ERROR, null, null);
          }
          return LEDGER.entrySet().stream()
              .map(e -> new LedgerEntryLookup.LedgerRef(e.getKey(), "x", e.getValue(), from))
              .toList();
        };
    reconciler =
        new LedgerSourceReconciler(
            List.of(new TaskLedgerSource(jdbc, tm)),
            ledger,
            new ReconcileMetrics(meters, Clock.systemUTC()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            1.0,
            100,
            Duration.ofHours(1));
    for (String table : List.of("task", "task_progress")) {
      jdbc.queryForList(
          "SELECT task.fn_create_period_partitions(?::regclass, ?)", "task." + table, BUCKET);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = TenantId.of(UUID.randomUUID());
    LEDGER.clear();
    ledgerDown = false;
  }

  @Test
  void soCaiVaNguonKhopThiKhop() {
    UUID task = task("Rà soát hồ sơ", 2);
    record(task, assigned(task, "Rà soát hồ sơ", 2), FROM.plusSeconds(60), true);
    UUID progress = progress(task, new BigDecimal("0.4550"));
    record(task, progressed(task, progress, "45.500", 3), FROM.plusSeconds(120), true);

    var out = reconciler.run(DAY, FROM, TO, List.of(tenant));
    assertThat(out)
        .singleElement()
        .satisfies(o -> assertThat(o.status()).isEqualTo(RunStatus.KHOP));
    ReconcileRun run = run();
    assertThat(run.status()).isEqualTo(RunStatus.KHOP);
    assertThat(run.bucketsTotal()).isEqualTo(1);
    assertThat(run.bucketsMismatch()).isZero();

    // Chạy lại cùng ngày (bản sao thứ hai của job) không ghi trùng.
    assertThat(reconciler.run(DAY, FROM, TO, List.of(tenant))).isEmpty();
  }

  @Test
  void lechBamHoacThieuSoCaiThiLoi() {
    UUID task = task("Tổng hợp báo cáo", 2);
    UUID e1 = record(task, assigned(task, "Tổng hợp báo cáo", 2), FROM.plusSeconds(60), false);
    LEDGER.put(e1, "00".repeat(32));
    UUID progress = progress(task, new BigDecimal("0.1000"));
    record(task, progressed(task, progress, "10.000", 3), FROM.plusSeconds(90), false);

    reconciler.run(DAY, FROM, TO, List.of(tenant));
    ReconcileRun run = run();
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.bucketsMismatch()).isEqualTo(1);
    assertThat(run.detail())
        .contains("\"kind\": \"LECH_BAM_PAYLOAD\"")
        .contains("\"kind\": \"THIEU_SO_CAI\"")
        .contains(e1.toString());
    assertThat(
            meters
                .find(ReconcileMetrics.MISMATCHES)
                .tag("reconcile", LedgerSourceReconciler.JOB)
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void dongNguonBiSuaNgoaiLuongThiLechDuLieuNguon() {
    UUID task = task("Kiểm tra hồ sơ", 2);
    record(task, assigned(task, "Kiểm tra hồ sơ", 2), FROM.plusSeconds(60), true);
    UUID progress = progress(task, new BigDecimal("0.3000"));
    record(task, progressed(task, progress, "30.000", 3), FROM.plusSeconds(90), true);
    // Sửa thẳng trong CSDL bằng tài khoản quản trị (vai trò svc_task không có quyền sửa dòng tiến
    // độ): tiến độ đã báo và tiêu đề nhiệm vụ đang ở phiên bản đã ghi sổ.
    var pg = MissionCoreTestDatabase.container();
    JdbcTemplate admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    admin.update("UPDATE task.task_progress SET progress_pct = 0.9000 WHERE id = ?", progress);
    admin.update("UPDATE task.task SET title = 'Tiêu đề khác' WHERE id = ?", task);

    reconciler.run(DAY, FROM, TO, List.of(tenant));
    ReconcileRun run = run();
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.detail())
        .contains("\"kind\": \"LECH_DU_LIEU_NGUON\"")
        .contains("task.task.progressed.v1.progressPct")
        .contains("task.task.assigned.v1.title")
        .doesNotContain("LECH_BAM_PAYLOAD");
  }

  @Test
  void phienBanMoiHonThiChiSoTruongKhongDoi() {
    UUID task = task("Lập kế hoạch", 5);
    // Sự kiện giao ở phiên bản 2, dòng hiện hành đã sang phiên bản 5 (gia hạn, đổi tiêu đề hợp lệ).
    record(task, assigned(task, "Tiêu đề lúc giao", 2), FROM.plusSeconds(60), true);

    reconciler.run(DAY, FROM, TO, List.of(tenant));
    assertThat(run().status()).isEqualTo(RunStatus.KHOP);
  }

  @Test
  void soCaiKhongPhanHoiThiLoiKhongTinhLech() {
    UUID task = task("Theo dõi", 2);
    record(task, assigned(task, "Theo dõi", 2), FROM.plusSeconds(60), true);
    ledgerDown = true;

    reconciler.run(DAY, FROM, TO, List.of(tenant));
    ReconcileRun run = run();
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.bucketsMismatch()).isZero();
    assertThat(run.detail()).contains("\"reason\": \"SO_CAI_KHONG_PHAN_HOI\"");
  }

  // ---------------------------------------------------------------- hỗ trợ

  private UUID task(String title, int version) {
    UUID id = UUID.randomUUID();
    inTenant(
        () ->
            jdbc.update(
                "INSERT INTO task.task (id, created_by, updated_by, row_version, period_id,"
                    + " period_bucket, code, title, task_group, source, assigner_id, due_date, unit,"
                    + " dup_fingerprint, state_machine_version, status, is_key_task)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'THEN_CHOT', 'DOT_XUAT', ?, ?, 'việc', ?,"
                    + " 'mac-dinh@2.1.0', 'DA_GIAO', true)",
                id,
                ACTOR,
                ACTOR,
                version,
                PERIOD,
                BUCKET,
                "NV-" + id.toString().substring(0, 8),
                title,
                ACTOR,
                Date.valueOf(LocalDate.of(2026, 12, 31)),
                id.toString().getBytes(StandardCharsets.UTF_8)));
    return id;
  }

  private UUID progress(UUID task, BigDecimal ratio) {
    UUID id = UUID.randomUUID();
    inTenant(
        () ->
            jdbc.update(
                "INSERT INTO task.task_progress (id, created_by, updated_by, period_id,"
                    + " period_bucket, task_id, reported_at, progress_pct, done_quantity, channel,"
                    + " idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'WEB', ?)",
                id,
                ACTOR,
                ACTOR,
                PERIOD,
                BUCKET,
                task,
                Timestamp.from(REPORTED),
                ratio,
                id.toString()));
    return id;
  }

  private static final Instant REPORTED = FROM.plusSeconds(100).plusNanos(123_456_000);

  private static NhiemVuDaGiao assigned(UUID task, String title, int version) {
    return NhiemVuDaGiao.newBuilder()
        .setTaskId(task)
        .setPlanItemId(null)
        .setTitle(title)
        .setLeadAssigneeId(ACTOR)
        .setAssigneeIds(List.of())
        .setDueDate(LocalDate.of(2026, 12, 31))
        .setIsKeyTask(true)
        .setIsSecret(false)
        .setAggregateVersion(version)
        .build();
  }

  private static TienDoDaCapNhat progressed(UUID task, UUID progress, String pct, int version) {
    return TienDoDaCapNhat.newBuilder()
        .setTaskId(task)
        .setPeriodId(PERIOD)
        .setProgressId(progress)
        .setReporterId(ACTOR)
        .setProgressPct(new BigDecimal(pct))
        .setActualValue(null)
        .setReportedAt(REPORTED.truncatedTo(ChronoUnit.MICROS))
        .setAggregateVersion(version)
        .build();
  }

  /** Ghi một dòng outbox với thân Avro thật; {@code inLedger} thì sổ cái có bản ghi cùng băm. */
  private UUID record(UUID task, SpecificRecord payload, Instant at, boolean inLedger) {
    UUID id = UUID.randomUUID();
    byte[] avro = avro(payload);
    String topic =
        payload instanceof NhiemVuDaGiao ? TaskLedgerSource.ASSIGNED : TaskLedgerSource.PROGRESSED;
    inTenant(
        () ->
            jdbc.update(
                "INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
                    + " aggregate_version, event_type, topic, event_key, payload, schema_version,"
                    + " created_at) VALUES (?, ?, 'task', ?, ?, 'x', ?, ?, ?, 1, ?)",
                id,
                tenant.value(),
                task,
                ((Number) payload.get(payload.getSchema().getField("aggregateVersion").pos()))
                    .longValue(),
                topic,
                tenant.value() + ":" + task,
                avro,
                Timestamp.from(at)));
    if (inLedger) {
      LEDGER.put(id, Jcs.hex(LedgerPayloadHash.of(payload.getSchema(), avro)));
    }
    return id;
  }

  private static byte[] avro(SpecificRecord r) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecord>(r.getSchema()).write(r, enc);
      enc.flush();
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private ReconcileRun run() {
    return inTenant(
        () ->
            new JdbcReconcileRunStore(jdbc, "task")
                .find(LedgerSourceReconciler.runId("task", tenant.value(), DAY))
                .orElseThrow());
  }

  private <T> T inTenant(Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(tm)
                  .execute(
                      st -> {
                        try {
                          return work.call();
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
