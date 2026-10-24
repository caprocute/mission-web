package vn.viettel.okrkpi.shared.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import vn.viettel.okrkpi.shared.testdb.TenantDatabase;

/**
 * Kiểm thử tích hợp NEN-014 với OPA thật (nạp chính sách {@code backend/opa/okrkpi/authz.rego}, dữ
 * liệu sinh {@code okrkpi/generated/data.json} và {@code partial.rego}) cùng PostgreSQL 17 thật
 * ({@link TenantDatabase}, vai trò {@code svc_task}, RLS FORCE, bảng {@code
 * org_replica.unit_closure_v}).
 */
class OpaAuthzIT {

  private static final Logger log = LoggerFactory.getLogger(OpaAuthzIT.class);

  private static final UUID TENANT_A = UUID.fromString("01927a00-0000-7000-8000-00000000000a");
  private static final UUID TENANT_B = UUID.fromString("01927a00-0000-7000-8000-00000000000b");

  private static final UUID UNIT_ROOT = UUID.fromString("01927a00-0000-7000-8000-000000000100");
  private static final UUID UNIT_CHILD_1 = UUID.fromString("01927a00-0000-7000-8000-000000000101");
  private static final UUID UNIT_CHILD_2 = UUID.fromString("01927a00-0000-7000-8000-000000000102");
  private static final UUID UNIT_OTHER = UUID.fromString("01927a00-0000-7000-8000-000000000999");

  private static final UUID USER_1 = UUID.fromString("01927a00-0000-7000-8000-0000000000a1");
  private static final UUID USER_2 = UUID.fromString("01927a00-0000-7000-8000-0000000000a2");
  private static final UUID USER_3 = UUID.fromString("01927a00-0000-7000-8000-0000000000a3");
  private static final UUID LEADER_ROOT = UUID.fromString("01927a00-0000-7000-8000-0000000000b1");

  private static final UUID TASK_ROOT_U1 = UUID.fromString("01927a00-0000-7000-8000-000000001001");
  private static final UUID TASK_CHILD1_U2 =
      UUID.fromString("01927a00-0000-7000-8000-000000001002");
  private static final UUID TASK_CHILD2_U3 =
      UUID.fromString("01927a00-0000-7000-8000-000000001003");
  private static final UUID TASK_OTHER = UUID.fromString("01927a00-0000-7000-8000-000000001004");

  private static Process opaProcess;
  private static Path tempRegoDir;
  private static String opaBaseUrl;
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @BeforeAll
  static void setUpAll() throws Exception {
    TenantDatabase.start();
    seedDatabase();
    startOpaServer();
  }

  @AfterAll
  static void tearDownAll() throws Exception {
    if (opaProcess != null) {
      opaProcess.destroyForcibly();
      opaProcess.waitFor();
    }
    if (tempRegoDir != null) {
      Files.walk(tempRegoDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    }
  }

  @Test
  void evaluateDecisionRealOpaAllowAndDenyReasons() {
    HttpOpaClient client = newClient(true);
    long now = Instant.now().getEpochSecond();

    // 1. Cho phép: leader tại UNIT_ROOT xem mục tiêu ở UNIT_CHILD_2 (phạm vi Cd - cây đơn vị)
    OpaDecision allowSubtree =
        client.decide(
            new OpaInput(
                OpaInput.Subject.user(
                    LEADER_ROOT.toString(),
                    TENANT_A.toString(),
                    UNIT_ROOT.toString(),
                    List.of("leader"),
                    "aal1",
                    now,
                    List.of("NHA_NUOC")),
                "SCR-0203.xem",
                null,
                AuthzResource.builder()
                    .type("Objective")
                    .id(TASK_CHILD2_U3.toString())
                    .tenantId(TENANT_A.toString())
                    .ownerUnitId(UNIT_CHILD_2.toString())
                    .classification("C1")
                    .build()
                    .toOpaResource(TENANT_A.toString(), "C1"),
                new OpaInput.Context(now)));
    assertThat(allowSubtree.isPermitted()).as("allowSubtree=%s", allowSubtree).isTrue();

    // 2. Từ chối ngoài cây đơn vị (out_of_scope)
    OpaDecision denySubtree =
        client.decide(
            new OpaInput(
                OpaInput.Subject.user(
                    LEADER_ROOT.toString(),
                    TENANT_A.toString(),
                    UNIT_ROOT.toString(),
                    List.of("leader"),
                    "aal1",
                    now,
                    List.of("NHA_NUOC")),
                "SCR-0203.xem",
                null,
                AuthzResource.builder()
                    .type("Objective")
                    .id(TASK_OTHER.toString())
                    .tenantId(TENANT_A.toString())
                    .ownerUnitId(UNIT_OTHER.toString())
                    .classification("C1")
                    .build()
                    .toOpaResource(TENANT_A.toString(), "C1"),
                new OpaInput.Context(now)));
    assertThat(denySubtree.isPermitted()).isFalse();
    assertThat(denySubtree.deny()).contains("out_of_scope");

    // 3. Từ chối khác tenant (out_of_scope)
    OpaDecision denyTenant =
        client.decide(
            new OpaInput(
                OpaInput.Subject.user(
                    LEADER_ROOT.toString(),
                    TENANT_A.toString(),
                    UNIT_ROOT.toString(),
                    List.of("leader"),
                    "aal1",
                    now,
                    List.of("NHA_NUOC")),
                "SCR-0203.xem",
                null,
                AuthzResource.builder()
                    .type("Objective")
                    .id(TASK_ROOT_U1.toString())
                    .tenantId(TENANT_B.toString())
                    .ownerUnitId(UNIT_ROOT.toString())
                    .classification("C1")
                    .build()
                    .toOpaResource(TENANT_A.toString(), "C1"),
                new OpaInput.Context(now)));
    assertThat(denyTenant.isPermitted()).isFalse();
    assertThat(denyTenant.deny()).contains("out_of_scope");

    // 4. Từ chối nguyên tắc bốn mắt (four_eyes_required) trên SCR-1301.duyet-2
    OpaDecision denyFourEyes =
        client.decide(
            new OpaInput(
                OpaInput.Subject.user(
                    LEADER_ROOT.toString(),
                    TENANT_A.toString(),
                    UNIT_ROOT.toString(),
                    List.of("second_approver"),
                    "aal2",
                    now,
                    List.of("NHA_NUOC")),
                "SCR-1301.duyet-2",
                null,
                AuthzResource.builder()
                    .type("PeriodReopen")
                    .id(TASK_ROOT_U1.toString())
                    .tenantId(TENANT_A.toString())
                    .ownerUnitId(UNIT_ROOT.toString())
                    .designatedIds(List.of(LEADER_ROOT.toString()))
                    .participants(Map.of("approver", List.of(LEADER_ROOT.toString())))
                    .classification("C1")
                    .build()
                    .toOpaResource(TENANT_A.toString(), "C1"),
                new OpaInput.Context(now)));
    assertThat(denyFourEyes.isPermitted()).isFalse();
    assertThat(denyFourEyes.deny()).contains("four_eyes_required");

    // 5. Từ chối thiếu AAL2 (step_up_required) trên SCR-0403.duyet-ky
    OpaDecision denyStepUp =
        client.decide(
            new OpaInput(
                OpaInput.Subject.user(
                    LEADER_ROOT.toString(),
                    TENANT_A.toString(),
                    UNIT_ROOT.toString(),
                    List.of("leader"),
                    "aal1",
                    now,
                    List.of("NHA_NUOC")),
                "SCR-0403.duyet-ky",
                null,
                AuthzResource.builder()
                    .type("Plan")
                    .id(TASK_ROOT_U1.toString())
                    .tenantId(TENANT_A.toString())
                    .ownerUnitId(UNIT_ROOT.toString())
                    .classification("C1")
                    .build()
                    .toOpaResource(TENANT_A.toString(), "C1"),
                new OpaInput.Context(now)));
    assertThat(denyStepUp.isPermitted()).isFalse();
    assertThat(denyStepUp.deny()).contains("step_up_required");
  }

  /**
   * QA-05 (23/10/2026): quản trị nghiệp vụ (AC-03) mở trang chủ đọc bố cục, cờ tính năng (dòng API
   * {@code ui}), ngưỡng bất thường (dòng {@code config}) và kỳ đang mở (dòng {@code periods}) theo
   * ma trận sinh ra; không được ghi kỳ.
   */
  @Test
  void quanTriNghiepVuDocTrangChuNguongVaKyKhongGhiKy() {
    HttpOpaClient client = newClient(false);
    long now = Instant.now().getEpochSecond();
    OpaInput.Subject author =
        OpaInput.Subject.user(
            LEADER_ROOT.toString(),
            TENANT_A.toString(),
            UNIT_ROOT.toString(),
            List.of("policy_author"),
            "aal2",
            now,
            List.of("NHA_NUOC"));
    for (String module : List.of("ui", "config", "periods")) {
      OpaDecision read =
          client.decide(
              new OpaInput(
                  author,
                  null,
                  new OpaInput.Request(module, "GET"),
                  null,
                  new OpaInput.Context(now)));
      assertThat(read.isPermitted()).as("GET %s: %s", module, read).isTrue();
    }
    OpaDecision write =
        client.decide(
            new OpaInput(
                author,
                null,
                new OpaInput.Request("periods", "POST"),
                null,
                new OpaInput.Context(now)));
    assertThat(write.isPermitted()).isFalse();
    assertThat(write.deny()).contains("role_not_allowed");
  }

  @Test
  void partialCompileToSqlFiltersRowsCorrectlyInPostgresWithRls() throws Exception {
    HttpOpaClient client = newClient(true);
    OpaSqlFilter sqlFilter = new OpaSqlFilter(client, newProperties(true));
    ResourceColumnMapping mapping = ResourceColumnMapping.defaultForAlias("t", "NHA_NUOC");
    long now = Instant.now().getEpochSecond();

    // Kịch bản 1: member (USER_1 tại UNIT_CHILD_1) với SCR-0503.nhan-viec (phạm vi B)
    // chỉ thấy nhiệm vụ mình làm (lead_id) hoặc mình tạo (created_by)
    OpaInput.Subject memberSubject =
        OpaInput.Subject.user(
            USER_1.toString(),
            TENANT_A.toString(),
            UNIT_CHILD_1.toString(),
            List.of("member"),
            "aal1",
            now,
            List.of("NHA_NUOC"));
    SqlPredicate memberPred =
        sqlFilter.buildPredicate(memberSubject, "SCR-0503.nhan-viec", mapping);
    assertThat(memberPred.sql()).contains("t.lead_id").contains("t.created_by");

    List<UUID> memberRows = queryTasksAsSvcTask(memberPred);
    assertThat(memberRows).containsExactlyInAnyOrder(TASK_ROOT_U1, TASK_CHILD2_U3);

    // Kịch bản 2: leader (LEADER_ROOT tại UNIT_ROOT) với SCR-0203.xem (phạm vi Cd)
    // thấy toàn bộ cây con UNIT_ROOT (UNIT_ROOT, UNIT_CHILD_1, UNIT_CHILD_2) nhưng KHÔNG thấy
    // nhiệm vụ của UNIT_OTHER
    OpaInput.Subject leaderSubject =
        OpaInput.Subject.user(
            LEADER_ROOT.toString(),
            TENANT_A.toString(),
            UNIT_ROOT.toString(),
            List.of("leader"),
            "aal1",
            now,
            List.of("NHA_NUOC"));
    SqlPredicate leaderPred = sqlFilter.buildPredicate(leaderSubject, "SCR-0203.xem", mapping);
    assertThat(leaderPred.sql()).contains("org_replica.unit_closure_v");

    List<UUID> leaderRows = queryTasksAsSvcTask(leaderPred);
    assertThat(leaderRows)
        .containsExactlyInAnyOrder(TASK_ROOT_U1, TASK_CHILD1_U2, TASK_CHILD2_U3)
        .doesNotContain(TASK_OTHER);

    // Kịch bản 3: Vai trò không có quyền trên thao tác -> 1 = 0 -> 0 dòng
    SqlPredicate deniedPred = sqlFilter.buildPredicate(memberSubject, "SCR-0105.duyet", mapping);
    assertThat(deniedPred.sql()).isEqualTo("1 = 0");
    assertThat(queryTasksAsSvcTask(deniedPred)).isEmpty();
  }

  @Test
  void measureLatencyCachedAndUncached() {
    long now = Instant.now().getEpochSecond();
    OpaInput sampleInput =
        new OpaInput(
            OpaInput.Subject.user(
                USER_1.toString(),
                TENANT_A.toString(),
                UNIT_CHILD_1.toString(),
                List.of("member"),
                "aal1",
                now,
                List.of("NHA_NUOC")),
            "SCR-0503.nhan-viec",
            null,
            AuthzResource.builder()
                .type("Task")
                .id(TASK_ROOT_U1.toString())
                .tenantId(TENANT_A.toString())
                .ownerUnitId(UNIT_ROOT.toString())
                .assigneeIds(List.of(USER_1.toString()))
                .classification("C1")
                .build()
                .toOpaResource(TENANT_A.toString(), "C1"),
            new OpaInput.Context(now));

    HttpOpaClient uncachedClient = newClient(false);
    for (int i = 0; i < 10; i++) {
      uncachedClient.decide(sampleInput);
    }
    List<Double> uncachedMs = new ArrayList<>(200);
    for (int i = 0; i < 200; i++) {
      long t0 = System.nanoTime();
      OpaDecision d = uncachedClient.decide(sampleInput);
      long elapsedNs = System.nanoTime() - t0;
      assertThat(d.isPermitted()).as("sampleDecision=%s", d).isTrue();
      uncachedMs.add(elapsedNs / 1_000_000.0);
    }
    Collections.sort(uncachedMs);
    double uncachedP50 = percentile(uncachedMs, 50);
    double uncachedP99 = percentile(uncachedMs, 99);

    HttpOpaClient cachedClient = newClient(true);
    cachedClient.decide(sampleInput); // prime cache
    List<Double> cachedMs = new ArrayList<>(1000);
    for (int i = 0; i < 1000; i++) {
      long t0 = System.nanoTime();
      OpaDecision d = cachedClient.decide(sampleInput);
      long elapsedNs = System.nanoTime() - t0;
      assertThat(d.isPermitted()).as("sampleDecision=%s", d).isTrue();
      cachedMs.add(elapsedNs / 1_000_000.0);
    }
    Collections.sort(cachedMs);
    double cachedP50 = percentile(cachedMs, 50);
    double cachedP99 = percentile(cachedMs, 99);

    log.info(
        "OPA latency benchmark: uncached p50={} ms, p99={} ms; cached p50={} ms, p99={} ms",
        String.format("%.3f", uncachedP50),
        String.format("%.3f", uncachedP99),
        String.format("%.4f", cachedP50),
        String.format("%.4f", cachedP99));

    assertThat(cachedP99).isLessThan(5.0);
    assertThat(uncachedP99).isLessThan(25.0);
  }

  private static double percentile(List<Double> sorted, int p) {
    int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
  }

  private static OpaProperties newProperties(boolean cacheEnabled) {
    OpaProperties props = new OpaProperties();
    props.setEnabled(true);
    props.setBaseUrl(URI.create(opaBaseUrl));
    props.setDecisionPath("/v1/data/okrkpi/authz/decision");
    props.setCompilePath("/v1/compile");
    props.setConnectTimeout(Duration.ofSeconds(2));
    props.setReadTimeout(Duration.ofSeconds(2));
    props.setDecisionCacheTtl(cacheEnabled ? Duration.ofSeconds(30) : Duration.ZERO);
    props.setSqlFilterCacheTtl(cacheEnabled ? Duration.ofSeconds(60) : Duration.ZERO);
    props.setMaxCacheEntries(1_000);
    return props;
  }

  private static HttpOpaClient newClient(boolean cacheEnabled) {
    return new HttpOpaClient(newProperties(cacheEnabled), MAPPER, new SimpleMeterRegistry());
  }

  private static List<UUID> queryTasksAsSvcTask(SqlPredicate predicate) throws Exception {
    try (Connection conn = TenantDatabase.connect("svc_task")) {
      conn.setAutoCommit(false);
      try (PreparedStatement ps =
          conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, TENANT_A.toString());
        ps.execute();
      }
      SingleConnectionDataSource ds = new SingleConnectionDataSource(conn, true);
      JdbcTemplate jdbc = new JdbcTemplate(ds);
      String sql =
          "SELECT t.id FROM task.authz_task_probe t WHERE " + predicate.sql() + " ORDER BY t.id";
      List<UUID> ids =
          jdbc.query(
              sql, (rs, rowNum) -> rs.getObject(1, UUID.class), predicate.params().toArray());
      conn.commit();
      return ids;
    }
  }

  private static void seedDatabase() throws Exception {
    // Tạo bảng org_replica.unit_closure_v bằng mig_org_replica
    try (Connection c = TenantDatabase.connect("mig_org_replica");
        Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS org_replica.unit_closure_v CASCADE");
      st.execute(
          """
          CREATE TABLE org_replica.unit_closure_v (
              tenant_id     uuid NOT NULL,
              tree          varchar(16) NOT NULL,
              ancestor_id   uuid NOT NULL,
              descendant_id uuid NOT NULL,
              depth         int  NOT NULL,
              PRIMARY KEY (tenant_id, tree, ancestor_id, descendant_id)
          )
          """);
      st.execute("ALTER TABLE org_replica.unit_closure_v ENABLE ROW LEVEL SECURITY");
      st.execute("ALTER TABLE org_replica.unit_closure_v FORCE ROW LEVEL SECURITY");
      st.execute(
          """
          CREATE POLICY rls_unit_closure_v__tenant ON org_replica.unit_closure_v
              USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
              WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
          """);
    }

    // Chèn dữ liệu cây đơn vị bằng svc_org_replica
    try (Connection c = TenantDatabase.connect("svc_org_replica")) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, TENANT_A.toString());
        ps.execute();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO org_replica.unit_closure_v (tenant_id, tree, ancestor_id, descendant_id, depth)"
                  + " VALUES (?, ?, ?, ?, ?)")) {
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_ROOT, UNIT_ROOT, 0);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_ROOT, UNIT_CHILD_1, 1);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_ROOT, UNIT_CHILD_2, 2);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_CHILD_1, UNIT_CHILD_1, 0);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_CHILD_1, UNIT_CHILD_2, 1);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_CHILD_2, UNIT_CHILD_2, 0);
        insertClosure(ps, TENANT_A, "NHA_NUOC", UNIT_OTHER, UNIT_OTHER, 0);
      }
      c.commit();
    }

    // Tạo bảng task.authz_task_probe bằng mig_task
    try (Connection c = TenantDatabase.connect("mig_task");
        Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS task.authz_task_probe CASCADE");
      st.execute(
          """
          CREATE TABLE task.authz_task_probe (
              id            uuid PRIMARY KEY,
              tenant_id     uuid NOT NULL,
              owner_unit_id uuid NOT NULL,
              lead_id       uuid NOT NULL,
              created_by    uuid NOT NULL,
              status        varchar(32) NOT NULL
          )
          """);
      st.execute("ALTER TABLE task.authz_task_probe ENABLE ROW LEVEL SECURITY");
      st.execute("ALTER TABLE task.authz_task_probe FORCE ROW LEVEL SECURITY");
      st.execute(
          """
          CREATE POLICY rls_authz_task_probe__tenant ON task.authz_task_probe
              USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
              WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
          """);
    }

    // Chèn 4 dòng nhiệm vụ bằng svc_task
    try (Connection c = TenantDatabase.connect("svc_task")) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, TENANT_A.toString());
        ps.execute();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO task.authz_task_probe (id, tenant_id, owner_unit_id, lead_id, created_by, status)"
                  + " VALUES (?, ?, ?, ?, ?, ?)")) {
        insertTask(ps, TASK_ROOT_U1, TENANT_A, UNIT_ROOT, USER_1, USER_2, "DANG_THUC_HIEN");
        insertTask(ps, TASK_CHILD1_U2, TENANT_A, UNIT_CHILD_1, USER_2, USER_2, "DANG_THUC_HIEN");
        insertTask(ps, TASK_CHILD2_U3, TENANT_A, UNIT_CHILD_2, USER_3, USER_1, "DANG_THUC_HIEN");
        insertTask(ps, TASK_OTHER, TENANT_A, UNIT_OTHER, USER_2, USER_2, "DANG_THUC_HIEN");
      }
      c.commit();
    }
  }

  private static void insertClosure(
      PreparedStatement ps, UUID tenant, String tree, UUID ancestor, UUID descendant, int depth)
      throws Exception {
    ps.setObject(1, tenant);
    ps.setString(2, tree);
    ps.setObject(3, ancestor);
    ps.setObject(4, descendant);
    ps.setInt(5, depth);
    ps.executeUpdate();
  }

  private static void insertTask(
      PreparedStatement ps,
      UUID id,
      UUID tenant,
      UUID unit,
      UUID lead,
      UUID createdBy,
      String status)
      throws Exception {
    ps.setObject(1, id);
    ps.setObject(2, tenant);
    ps.setObject(3, unit);
    ps.setObject(4, lead);
    ps.setObject(5, createdBy);
    ps.setString(6, status);
    ps.executeUpdate();
  }

  private static void startOpaServer() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    Path opaBundleDir = resolveOpaBundleDir();
    tempRegoDir = Files.createTempDirectory("opa-nen014-");
    Path okrkpiGenDir = tempRegoDir.resolve("okrkpi/generated");
    Files.createDirectories(okrkpiGenDir);
    Files.copy(opaBundleDir.resolve("okrkpi/authz.rego"), tempRegoDir.resolve("okrkpi/authz.rego"));
    Files.copy(
        opaBundleDir.resolve("okrkpi/generated/data.json"), okrkpiGenDir.resolve("data.json"));
    try (var in = OpaAuthzIT.class.getResourceAsStream("partial.rego")) {
      assertThat(in).as("partial.rego trong classpath").isNotNull();
      Files.copy(in, tempRegoDir.resolve("partial.rego"));
    }
    Path orgDir = tempRegoDir.resolve("org");
    Files.createDirectories(orgDir);
    Map<String, Object> orgData =
        Map.of(
            TENANT_A.toString(),
            Map.of(
                "subtree",
                Map.of(
                    UNIT_ROOT.toString(),
                    List.of(UNIT_ROOT.toString(), UNIT_CHILD_1.toString(), UNIT_CHILD_2.toString()),
                    UNIT_CHILD_1.toString(),
                    List.of(UNIT_CHILD_1.toString(), UNIT_CHILD_2.toString()),
                    UNIT_CHILD_2.toString(),
                    List.of(UNIT_CHILD_2.toString()),
                    UNIT_OTHER.toString(),
                    List.of(UNIT_OTHER.toString())),
                "managed_units",
                Map.of(LEADER_ROOT.toString(), List.of(UNIT_ROOT.toString()))));
    Files.writeString(
        orgDir.resolve("data.json"), MAPPER.writeValueAsString(orgData), StandardCharsets.UTF_8);

    String opaBin = resolveOpaBinary();
    ProcessBuilder pb =
        new ProcessBuilder(
            opaBin,
            "run",
            "--server",
            "--addr",
            "127.0.0.1:" + port,
            "--skip-version-check",
            "-b",
            tempRegoDir.toString());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    opaProcess = pb.start();
    opaBaseUrl = "http://127.0.0.1:" + port;

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> resp =
            http.send(
                HttpRequest.newBuilder(URI.create(opaBaseUrl + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
          return;
        }
      } catch (Exception ignored) {
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("OPA server không khởi động kịp tại " + opaBaseUrl);
  }

  private static Path resolveOpaBundleDir() {
    Path cwd = Path.of("").toAbsolutePath();
    for (Path p = cwd; p != null; p = p.getParent()) {
      Path candidate = p.resolve("backend/opa");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Không tìm thấy thư mục backend/opa từ " + cwd);
  }

  private static String resolveOpaBinary() {
    for (String candidate : List.of("/opt/homebrew/bin/opa", "/usr/local/bin/opa", "opa")) {
      if (candidate.startsWith("/") && Files.isExecutable(Path.of(candidate))) {
        return candidate;
      }
    }
    return "opa";
  }
}
