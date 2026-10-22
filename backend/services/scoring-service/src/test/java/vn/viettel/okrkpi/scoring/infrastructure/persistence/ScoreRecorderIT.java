package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Đường ghi lô trên PostgreSQL 17 thật (DV-07-01), kết nối bằng {@code svc_scoring} như ứng dụng:
 * COPY vào bảng tạm rồi INSERT ... SELECT, tập đầu vào trùng băm dùng lại, EVT-10 trong cùng giao
 * dịch, lô lỗi không để lại gì, RLS hai tenant, kết quả không sửa không xoá được, nén lz4.
 */
class ScoreRecorderIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20271;
  private static final UUID TENANT_A = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  private static final UUID TENANT_B = UUID.fromString("0192f1a2-0000-7000-8000-00000000b001");
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");
  private static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");
  private static final UUID PPV = UUID.fromString("0192f1a2-0000-7000-8000-0000000000cc");

  private static PostgreSQLContainer<?> pg;
  private static ScoreRecorder recorder;
  private static TransactionTemplate tx;
  private static JdbcTemplate svc;

  @BeforeAll
  static void start() throws SQLException {
    pg = TestPostgres.container("td_scoring");
    pg.start();
    Map<String, String> env = new java.util.HashMap<>();
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
    svc = new JdbcTemplate(ds);
    tx =
        new TransactionTemplate(
            new TenantDataSourceTransactionManager(ds, TenantProperties.defaults()));
    OutboxPayloadSerializer serializer =
        (topic, payload) -> new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    OutboxWriter outbox =
        new OutboxWriter(svc, "scoring", serializer, new ObjectMapper(), Clock.systemUtc());
    recorder =
        new ScoreRecorder(
            new JdbcScoreJobStore(JdbcClient.create(ds)),
            new CopyScoreResultStore(svc),
            new OutboxScoreEvents(outbox));
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @Test
  void ghiLoQuaCopyPhatEvt10VaDungLaiDauVaoTrungBam() throws SQLException {
    UUID job = openJob(TENANT_A, 4, null);
    UUID profile = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    ComputedScore first = score(person, profile, "{\"nhiệm_vụ\":[1,2]}", "96.750", 3);
    ComputedScore same = score(person, profile, "{\"nhiệm_vụ\":[1,2]}", "96.750", 2);
    ComputedScore failed =
        new ComputedScore(
            Ids.newUuidV7(),
            UUID.randomUUID(),
            "NHA_NUOC",
            UUID.randomUUID(),
            CanonicalDoc.parse("{\"loi\":\"\\\"thiếu\\\" dữ liệu,\\ndòng 2\"}"),
            CanonicalDoc.parse("{}"),
            null,
            null,
            ScoreStatus.LOI,
            List.of());

    int inputs =
        inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, job, List.of(first, failed))));
    int again = inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, job, List.of(same))));
    ComputedScore repacked = score(person, profile, "{\"nhiệm_vụ\":[1,2]}", "96.750", 3);
    byte[] otherPack = new byte[32];
    otherPack[0] = 1;
    int third =
        inTenant(
            TENANT_A, () -> recorder.record(batch(TENANT_A, job, List.of(repacked), otherPack)));

    assertThat(inputs).isEqualTo(2);
    assertThat(again).as("cùng băm đầu vào và cùng gói thì không ghi lại").isZero();
    assertThat(third).as("gói khác thì dùng lại tập đầu vào trùng băm").isZero();
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_result WHERE id = '" + same.resultId() + "'"))
        .isZero();
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_input_v WHERE tenant_id = '" + TENANT_A + "'"))
        .isEqualTo(2);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_result WHERE score_job_id = '" + job + "'"))
        .isEqualTo(3);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_trace WHERE score_result_id = '"
                    + first.resultId()
                    + "'"))
        .isEqualTo(3);
    assertThat(
            adminString(
                "SELECT legal_ref FROM scoring.score_trace WHERE score_result_id = '"
                    + first.resultId()
                    + "' AND step_no = 2"))
        .isNull();
    assertThat(
            adminString(
                "SELECT explanation FROM scoring.score_trace WHERE score_result_id = '"
                    + first.resultId()
                    + "' AND step_no = 1"))
        .isEqualTo("Bước \"1\", có dấu phẩy, xuống\ndòng");
    // Băm trong CSDL trùng băm tính ở ứng dụng; tài liệu lưu đúng dạng chuẩn hoá.
    assertThat(
            adminString(
                "SELECT encode(input_hash, 'hex') FROM scoring.score_result WHERE id = '"
                    + first.resultId()
                    + "'"))
        .isEqualTo(first.input().hashHex());
    assertThat(
            adminString(
                "SELECT result_doc::text FROM scoring.score_result WHERE id = '"
                    + failed.resultId()
                    + "'"))
        .isEqualTo("{}");
    // EVT-10 chỉ cho kết quả OK; thứ tự trong hồ sơ tăng 1, 2.
    assertThat(
            adminString(
                "SELECT string_agg(aggregate_version::text, ',' ORDER BY aggregate_version)"
                    + " FROM scoring.outbox_event WHERE aggregate_id = '"
                    + profile
                    + "'"
                    + " AND topic = 'scoring.score.computed.v1'"))
        .isEqualTo("1,2");
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.outbox_event WHERE aggregate_id = '"
                    + failed.profileId()
                    + "'"))
        .isZero();
    assertThat(
            adminString(
                "SELECT status || ':' || done_items || ':' || failed_items"
                    + " || ':' || (finished_at IS NOT NULL) FROM scoring.score_job WHERE id = '"
                    + job
                    + "'"))
        .isEqualTo("XONG:3:1:true");
  }

  @Test
  void loLoiKhongDeLaiKetQuaVaSuKien() throws SQLException {
    UUID job = openJob(TENANT_A, 1, null);
    UUID profile = UUID.randomUUID();
    ComputedScore ok = score(UUID.randomUUID(), profile, "{\"x\":1}", "50", 1);
    UUID missingJob = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, missingJob, List.of(ok)))))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_result WHERE profile_id = '" + profile + "'"))
        .isZero();
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.outbox_event WHERE aggregate_id = '" + profile + "'"))
        .isZero();
    assertThat(adminString("SELECT status FROM scoring.score_job WHERE id = '" + job + "'"))
        .isEqualTo("CHO");
  }

  @Test
  void ghiLoNghinKetQuaTrongMotGiaoDich() throws SQLException {
    UUID job = openJob(TENANT_A, ScoreBatch.MAX_SIZE, null);
    List<ComputedScore> items = new ArrayList<>();
    for (int i = 0; i < ScoreBatch.MAX_SIZE; i++) {
      items.add(score(UUID.randomUUID(), UUID.randomUUID(), "{\"i\":" + i + "}", "70.125", 5));
    }
    long started = System.nanoTime();
    int inputs = inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, job, items)));
    long millis = (System.nanoTime() - started) / 1_000_000;
    assertThat(inputs).isEqualTo(ScoreBatch.MAX_SIZE);
    assertThat(
            adminLong(
                "SELECT count(*) FROM scoring.score_trace t JOIN scoring.score_result r"
                    + " ON r.id = t.score_result_id WHERE r.score_job_id = '"
                    + job
                    + "'"))
        .isEqualTo(5L * ScoreBatch.MAX_SIZE);
    // Chỉ ghi nhận, không đo hiệu năng ở giai đoạn này (YC-PT-19).
    System.getLogger(getClass().getName())
        .log(System.Logger.Level.INFO, "Lô 1.000 kết quả, 5.000 bước: " + millis + " ms");
  }

  @Test
  void rlsHaiTenantVaChiGhiThem() throws SQLException {
    UUID jobA = openJob(TENANT_A, 1, UUID.randomUUID());
    UUID trigger = UUID.randomUUID();
    UUID jobB = openJob(TENANT_B, 1, trigger);
    assertThat(openJob(TENANT_B, 1, trigger)).as("cùng sự kiện kích hoạt").isEqualTo(jobB);
    ComputedScore b = score(UUID.randomUUID(), UUID.randomUUID(), "{\"b\":1}", "80", 1);
    inTenant(TENANT_B, () -> recorder.record(batch(TENANT_B, jobB, List.of(b))));

    Long seenByA =
        inTenant(
            TENANT_A,
            () ->
                svc.queryForObject(
                    "SELECT count(*) FROM scoring.score_result WHERE id = ?",
                    Long.class,
                    b.resultId()));
    assertThat(seenByA).isZero();
    // Tenant A không ghi được dòng mang tenant B, cũng không gắn kết quả vào tác vụ của B.
    assertThatThrownBy(
            () ->
                inTenant(
                    TENANT_A,
                    () ->
                        recorder.record(
                            batch(
                                TENANT_B,
                                jobB,
                                List.of(
                                    score(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "{\"c\":1}",
                                        "1",
                                        1))))))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                inTenant(
                    TENANT_A,
                    () ->
                        recorder.record(
                            batch(
                                TENANT_A,
                                jobB,
                                List.of(
                                    score(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "{\"d\":1}",
                                        "1",
                                        1))))))
        .isInstanceOf(RuntimeException.class);
    assertThat(jobA).isNotNull();
    for (String sql :
        List.of(
            "UPDATE scoring.score_result SET status = 'LOI'",
            "DELETE FROM scoring.score_result",
            "UPDATE scoring.score_trace SET step_no = step_no",
            "DELETE FROM scoring.score_trace",
            "UPDATE scoring.score_input_v SET flow = flow",
            "DELETE FROM scoring.score_input_v",
            "DELETE FROM scoring.score_job")) {
      assertThatThrownBy(() -> inTenant(TENANT_B, () -> svc.update(sql)))
          .as(sql)
          .hasStackTraceContaining("permission denied");
    }
  }

  @Test
  void dauVaoNenLz4TrenPhanVung() throws SQLException {
    assertThat(
            adminString(
                "SELECT string_agg(DISTINCT a.attcompression::text, ',') FROM pg_attribute a"
                    + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'scoring' AND c.relname LIKE 'score_input_v%'"
                    + " AND a.attname = 'input_doc'"))
        .isEqualTo("l");
  }

  private static UUID openJob(UUID tenant, int total, UUID trigger) {
    ScoreJob job =
        new ScoreJob(
            tenant,
            Ids.newUuidV7(),
            PERIOD,
            BUCKET,
            ScoreJobKind.CHOT_KY,
            CanonicalDoc.of(Map.of("don_vi", List.of("DV-01"))),
            PPV,
            trigger,
            total,
            ACTOR);
    return inTenant(tenant, () -> recorder.open(job));
  }

  private static ComputedScore score(
      UUID person, UUID profile, String input, String total, int steps) {
    List<TraceStep> trace = new ArrayList<>();
    for (int i = 1; i <= steps; i++) {
      trace.add(
          new TraceStep(
              i,
              "BR-09-0" + i,
              i == 2 ? null : "QĐ 366-QĐ/TW, Điều " + i,
              CanonicalDoc.of(Map.of("buoc", i)),
              CanonicalDoc.of(Map.of("diem", total)),
              i == 1 ? "Bước \"1\", có dấu phẩy, xuống\ndòng" : null));
    }
    return new ComputedScore(
        Ids.newUuidV7(),
        person,
        "DANG",
        profile,
        CanonicalDoc.parse(input),
        CanonicalDoc.of(Map.of("tong_diem", total, "muc", "HTTNV")),
        new BigDecimal(total),
        "HTTNV",
        ScoreStatus.OK,
        trace);
  }

  private static ScoreBatch batch(UUID tenant, UUID job, List<ComputedScore> items) {
    return batch(tenant, job, items, new byte[32]);
  }

  private static ScoreBatch batch(
      UUID tenant, UUID job, List<ComputedScore> items, byte[] policyContentHash) {
    return new ScoreBatch(
        tenant,
        job,
        ScoreJobKind.CHOT_KY,
        PERIOD,
        BUCKET,
        PPV,
        policyContentHash,
        "scoring-service:0.1.0",
        ACTOR,
        Instant.now(),
        items);
  }

  private static <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
    try {
      return TenantContext.call(TenantId.of(tenant), () -> tx.execute(s -> work.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static long adminLong(String sql) throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String adminString(String sql) throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
