package vn.viettel.okrkpi.platform.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * Bộ so R1 dùng chung: kiểm bằng {@link JdbcTemplate} giả giữ {@code reconcile_run} và các view bản
 * sao trong bộ nhớ (kiểm thử trên PostgreSQL thật ở IT của mission-core và workflow-service).
 */
class OrgReplicaReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-10-23T01:00:00Z");
  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private FakeJdbc jdbc;
  private SimpleMeterRegistry meters;
  private OrgReplicaReconciler reconciler;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setUp() {
    jdbc = new FakeJdbc();
    meters = new SimpleMeterRegistry();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    reconciler =
        new OrgReplicaReconciler(
            jdbc, "wf_replica", new ReconcileMetrics(meters, clock), json, clock);
  }

  @Test
  void schemaKhongHopLeBiTuChoi() {
    Clock clock = Clock.systemUTC();
    var metrics = new ReconcileMetrics(meters, clock);
    assertThatThrownBy(
            () -> new OrgReplicaReconciler(jdbc, "org_replica; drop", metrics, json, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrgReplicaReconciler(jdbc, null, metrics, json, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(reconciler.runs().table()).isEqualTo("wf_replica.reconcile_run");
  }

  @Test
  void runIdTatDinhTheoLoat() {
    UUID batch = UUID.randomUUID();
    assertThat(OrgReplicaReconciler.runId(batch)).isEqualTo(OrgReplicaReconciler.runId(batch));
    assertThat(OrgReplicaReconciler.runId(batch))
        .isNotEqualTo(OrgReplicaReconciler.runId(UUID.randomUUID()));
  }

  @Test
  void expectGhiLanChayDangChayVaKhongGhiTrung() throws Exception {
    UUID batch = UUID.randomUUID();
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("PERSON", 2L);
    counts.put("ORG_UNIT", 1L);
    counts.put("ASSIGNMENT", 0L);
    counts.put("KHAC", 9L);

    assertThat(reconciler.expect(TENANT, batch, 3, counts, Map.of(" 7", "00", "200", "ff"), NOW))
        .isTrue();
    assertThat(reconciler.expect(TENANT, batch, 3, counts, Map.of("7", "00"), NOW)).isFalse();

    ReconcileRun run = jdbc.runs.get(OrgReplicaReconciler.runId(batch));
    assertThat(run.status()).isEqualTo(RunStatus.DANG_CHAY);
    assertThat(run.job()).isEqualTo(OrgReplicaReconciler.JOB);
    assertThat(run.entityType()).isEqualTo(OrgReplicaReconciler.ALL_KINDS);
    assertThat(run.bucketsTotal()).isEqualTo(2);
    assertThat(jdbc.inserts).allMatch(sql -> sql.contains("wf_replica.reconcile_run"));
    JsonNode d = json.readTree(run.detail());
    assertThat(d.path("batchId").asText()).isEqualTo(batch.toString());
    assertThat(d.path("entities").asLong()).isEqualTo(3);
    assertThat(d.path("kinds").toString()).isEqualTo("[\"ASSIGNMENT\",\"ORG_UNIT\",\"PERSON\"]");
    assertThat(d.path("expected").path("7").asText()).isEqualTo("00");
    assertThat(d.path("expected").path("200").asText()).isEqualTo("ff");
  }

  @Test
  void expectMotLoaiThiEntityTypeLaLoaiDo() {
    UUID batch = UUID.randomUUID();
    reconciler.expect(TENANT, batch, -5, Map.of("PERSON", 1L), Map.of("1", "00"), NOW);
    ReconcileRun run = jdbc.runs.get(OrgReplicaReconciler.runId(batch));
    assertThat(run.entityType()).isEqualTo("PERSON");
    assertThat(run.detail()).contains("\"entities\":0");
  }

  @Test
  void expectKhongCoLoaiHopLeThiLayCaBaLoai() {
    UUID batch = UUID.randomUUID();
    reconciler.expect(TENANT, batch, 0, Map.of(), Map.of("1", "00"), NOW);
    assertThat(jdbc.runs.get(OrgReplicaReconciler.runId(batch)).entityType())
        .isEqualTo(OrgReplicaReconciler.ALL_KINDS);
  }

  @Test
  void bucketNgoaiKhoangBiTuChoi() {
    UUID batch = UUID.randomUUID();
    assertThatThrownBy(
            () -> reconciler.expect(TENANT, batch, 0, Map.of(), Map.of("256", "00"), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("256");
    assertThatThrownBy(() -> reconciler.expect(TENANT, batch, 0, Map.of(), Map.of("-1", "0"), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void checkKhopKhiBanSaoDungBam() throws Exception {
    Row unit = new Row(UUID.randomUUID(), LocalDate.parse("2026-01-01"), 3);
    Row person = new Row(UUID.randomUUID(), null, 5);
    Row assignment = new Row(UUID.randomUUID(), LocalDate.parse("2026-02-01"), 1);
    jdbc.views.put("wf_replica.org_unit_v", List.of(unit));
    jdbc.views.put("wf_replica.person_v", List.of(person));
    jdbc.views.put("wf_replica.assignment_v", List.of(assignment));

    Map<String, String> hashes =
        expected(Map.of("ORG_UNIT", List.of(unit), "PERSON", List.of(person)), assignment);
    UUID batch = UUID.randomUUID();
    reconciler.expect(TENANT, batch, 0, Map.of(), hashes, NOW.minusSeconds(3600));

    RunStatus status = reconciler.check(jdbc.runs.get(OrgReplicaReconciler.runId(batch)));

    assertThat(status).isEqualTo(RunStatus.KHOP);
    ReconcileRun done = jdbc.runs.get(OrgReplicaReconciler.runId(batch));
    assertThat(done.bucketsMismatch()).isZero();
    assertThat(done.finishedAt()).isEqualTo(NOW);
    JsonNode out = json.readTree(done.detail());
    assertThat(out.path("actualCounts").path("ORG_UNIT").asLong()).isEqualTo(1);
    assertThat(out.path("actualCounts").path("PERSON").asLong()).isEqualTo(1);
    assertThat(out.has("replay")).isFalse();
    assertThat(jdbc.scans).allMatch(sql -> sql.contains("wf_replica."));
    assertThat(jdbc.fetchSizes).containsOnly(5_000);
    assertThat(
            meters
                .get(ReconcileMetrics.RUNS)
                .tag("reconcile", OrgReplicaReconciler.JOB)
                .tag("status", "KHOP")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void checkLechThiLoiVaGhiLenhPhatLaiTheoLoai() throws Exception {
    Row person = new Row(UUID.randomUUID(), null, 5);
    jdbc.views.put("wf_replica.person_v", List.of(person));
    int bucket = OrgReplicaReconciler.bucketsOf(List.of(person.id())).first();
    UUID batch = UUID.randomUUID();
    reconciler.expect(
        TENANT,
        batch,
        0,
        Map.of("PERSON", 1L),
        Map.of(String.valueOf(bucket), "0000000000000000"),
        NOW.minusSeconds(3600));

    RunStatus status = reconciler.check(jdbc.runs.get(OrgReplicaReconciler.runId(batch)));

    assertThat(status).isEqualTo(RunStatus.LOI);
    ReconcileRun done = jdbc.runs.get(OrgReplicaReconciler.runId(batch));
    assertThat(done.bucketsMismatch()).isEqualTo(1);
    assertThat(json.readTree(done.detail()).path("replay").asText())
        .isEqualTo("org-republish --tenant=" + TENANT + " --entity=person --bucket=" + bucket);
    assertThat(jdbc.scans).hasSize(1);
  }

  @Test
  void checkLenhPhatLaiNhieuLoai() throws Exception {
    Row unit = new Row(UUID.randomUUID(), LocalDate.parse("2026-01-01"), 3);
    jdbc.views.put("wf_replica.org_unit_v", List.of(unit));
    int bucket = Buckets.of(unit.id());
    UUID batch = UUID.randomUUID();
    reconciler.expect(
        TENANT,
        batch,
        0,
        Map.of("ORG_UNIT", 1L, "ASSIGNMENT", 0L),
        Map.of(String.valueOf(bucket), "0000000000000001"),
        NOW.minusSeconds(3600));

    reconciler.check(jdbc.runs.get(OrgReplicaReconciler.runId(batch)));

    assertThat(
            json.readTree(jdbc.runs.get(OrgReplicaReconciler.runId(batch)).detail())
                .path("replay")
                .asText())
        .isEqualTo(
            "org-republish --tenant=" + TENANT + " --entity=assignment,unit --bucket=" + bucket);
  }

  @Test
  void phatLaiKhopSauLanLoiThiDaSua() {
    Row person = new Row(UUID.randomUUID(), null, 7);
    jdbc.views.put("wf_replica.person_v", List.of(person));
    Map<String, String> hashes = expected(Map.of("PERSON", List.of(person)), null);
    jdbc.previous =
        new ReconcileRun(
            UUID.randomUUID(),
            TENANT,
            OrgReplicaReconciler.JOB,
            "PERSON",
            NOW.minusSeconds(7200),
            NOW.minusSeconds(7100),
            1,
            1,
            0,
            RunStatus.LOI,
            "{}");
    UUID batch = UUID.randomUUID();
    reconciler.expect(TENANT, batch, 4, Map.of("PERSON", 1L), hashes, NOW.minusSeconds(3600));

    RunStatus status = reconciler.check(jdbc.runs.get(OrgReplicaReconciler.runId(batch)));

    assertThat(status).isEqualTo(RunStatus.DA_SUA);
    assertThat(jdbc.runs.get(OrgReplicaReconciler.runId(batch)).rowsRepaired()).isEqualTo(4);
    assertThat(jdbc.lastFinishedArgs)
        .containsExactly(
            OrgReplicaReconciler.JOB, "PERSON", Timestamp.from(NOW.minusSeconds(3600)));
  }

  @Test
  void khopSauLanKhopTruocThiVanKhop() {
    Row person = new Row(UUID.randomUUID(), null, 7);
    jdbc.views.put("wf_replica.person_v", List.of(person));
    jdbc.previous =
        new ReconcileRun(
            UUID.randomUUID(),
            TENANT,
            OrgReplicaReconciler.JOB,
            "PERSON",
            NOW.minusSeconds(7200),
            NOW.minusSeconds(7100),
            1,
            0,
            0,
            RunStatus.KHOP,
            "{}");
    UUID batch = UUID.randomUUID();
    reconciler.expect(
        TENANT,
        batch,
        4,
        Map.of("PERSON", 1L),
        expected(Map.of("PERSON", List.of(person)), null),
        NOW.minusSeconds(3600));

    assertThat(reconciler.check(jdbc.runs.get(OrgReplicaReconciler.runId(batch))))
        .isEqualTo(RunStatus.KHOP);
  }

  @Test
  void detailKhongPhaiJsonThiBaoLoi() {
    ReconcileRun bad =
        new ReconcileRun(
            UUID.randomUUID(),
            TENANT,
            OrgReplicaReconciler.JOB,
            "PERSON",
            NOW,
            null,
            1,
            null,
            null,
            RunStatus.DANG_CHAY,
            "{khong-phai-json");
    assertThatThrownBy(() -> reconciler.check(bad)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void loaiLaTrongDetailBiTuChoi() {
    ReconcileRun bad =
        new ReconcileRun(
            UUID.randomUUID(),
            TENANT,
            OrgReplicaReconciler.JOB,
            "KHAC",
            NOW,
            null,
            1,
            null,
            null,
            RunStatus.DANG_CHAY,
            "{\"kinds\":[\"KHAC\"],\"expected\":{\"1\":\"00\"}}");
    assertThatThrownBy(() -> reconciler.check(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("KHAC");
  }

  /** Băm mong đợi theo công thức của org-service cho các bucket chứa các dòng đã cho. */
  private static Map<String, String> expected(Map<String, List<Row>> byKind, Row assignment) {
    Map<String, List<Row>> all = new LinkedHashMap<>(byKind);
    if (assignment != null) {
      all.put("ASSIGNMENT", List.of(assignment));
    }
    HashAggregate agg = new HashAggregate(HashAggregate.Mode.SUM64);
    List<Integer> buckets = new ArrayList<>();
    all.forEach(
        (kind, rows) ->
            rows.forEach(
                r -> {
                  int b = Buckets.of(r.id());
                  buckets.add(b);
                  agg.addTo(b, RowHash.canonical(kind, r.id(), r.validFrom(), r.version()));
                }));
    Map<String, String> out = new LinkedHashMap<>();
    agg.hashes(buckets).forEach((b, h) -> out.put(String.valueOf(b), h));
    return out;
  }

  private record Row(UUID id, LocalDate validFrom, long version) {}

  /** {@link JdbcTemplate} giả: {@code reconcile_run} và các view bản sao nằm trong bộ nhớ. */
  private static final class FakeJdbc extends JdbcTemplate {
    final Map<UUID, ReconcileRun> runs = new LinkedHashMap<>();
    final Map<String, List<Row>> views = new LinkedHashMap<>();
    final List<String> inserts = new ArrayList<>();
    final List<String> scans = new ArrayList<>();
    final List<Integer> fetchSizes = new ArrayList<>();
    ReconcileRun previous;
    List<Object> lastFinishedArgs = List.of();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public int update(String sql, Object... args) {
      if (sql.startsWith("INSERT")) {
        inserts.add(sql);
        UUID id = (UUID) args[0];
        if (runs.containsKey(id)) {
          return 0;
        }
        runs.put(
            id,
            new ReconcileRun(
                id,
                (UUID) args[1],
                (String) args[2],
                (String) args[3],
                ((Timestamp) args[4]).toInstant(),
                null,
                (Integer) args[6],
                (Integer) args[7],
                (Integer) args[8],
                RunStatus.valueOf((String) args[9]),
                (String) args[10]));
        return 1;
      }
      UUID id = (UUID) args[5];
      ReconcileRun r = runs.get(id);
      runs.put(
          id,
          new ReconcileRun(
              r.id(),
              r.tenantId(),
              r.job(),
              r.entityType(),
              r.startedAt(),
              ((Timestamp) args[3]).toInstant(),
              r.bucketsTotal(),
              (Integer) args[1],
              (Integer) args[2],
              RunStatus.valueOf((String) args[0]),
              merge(r.detail(), (String) args[4])));
      return 1;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      lastFinishedArgs = List.of(args);
      if (previous == null) {
        return List.of();
      }
      try {
        return List.of(mapper.mapRow(resultSetOf(previous), 0));
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void query(PreparedStatementCreator psc, RowCallbackHandler rch) {
      try {
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        String[] sql = new String[1];
        when(con.prepareStatement(anyString()))
            .thenAnswer(
                inv -> {
                  sql[0] = inv.getArgument(0);
                  return ps;
                });
        doAnswer(
                inv -> {
                  fetchSizes.add(inv.getArgument(0));
                  return null;
                })
            .when(ps)
            .setFetchSize(anyInt());
        psc.createPreparedStatement(con);
        scans.add(sql[0]);
        String view = sql[0].substring(sql[0].indexOf("FROM ") + 5).trim();
        for (Row row : views.getOrDefault(view, List.of())) {
          ResultSet rs = mock(ResultSet.class);
          when(rs.getObject(1, UUID.class)).thenReturn(row.id());
          when(rs.getDate(2))
              .thenReturn(
                  row.validFrom() == null || view.endsWith("person_v")
                      ? null
                      : Date.valueOf(row.validFrom()));
          when(rs.getLong(3)).thenReturn(row.version());
          rch.processRow(rs);
        }
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    private static ResultSet resultSetOf(ReconcileRun r) throws SQLException {
      ResultSet rs = mock(ResultSet.class);
      when(rs.getObject(eq("id"), eq(UUID.class))).thenReturn(r.id());
      when(rs.getObject(eq("tenant_id"), eq(UUID.class))).thenReturn(r.tenantId());
      when(rs.getString("job")).thenReturn(r.job());
      when(rs.getString("entity_type")).thenReturn(r.entityType());
      when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(r.startedAt()));
      when(rs.getTimestamp("finished_at"))
          .thenReturn(r.finishedAt() == null ? null : Timestamp.from(r.finishedAt()));
      when(rs.getObject("buckets_total")).thenReturn(r.bucketsTotal());
      when(rs.getObject("buckets_mismatch")).thenReturn(r.bucketsMismatch());
      when(rs.getObject("rows_repaired")).thenReturn(r.rowsRepaired());
      when(rs.getString("status")).thenReturn(r.status().name());
      when(rs.getString("detail")).thenReturn(r.detail());
      return rs;
    }

    private String merge(String base, String extra) {
      try {
        ObjectNode node = (ObjectNode) json.readTree(base);
        node.setAll((ObjectNode) json.readTree(extra));
        return json.writeValueAsString(node);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
