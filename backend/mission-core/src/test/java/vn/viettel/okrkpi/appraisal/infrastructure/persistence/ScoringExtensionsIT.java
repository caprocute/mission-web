package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService.NewIncident;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Extensions;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;
import vn.viettel.okrkpi.task.api.TaskSummary;
import vn.viettel.okrkpi.task.infrastructure.persistence.JdbcTaskScoringReads;

/**
 * Trường mở rộng BS-03 của đầu vào tính điểm trên PostgreSQL thật (CORE-04-13): việc lá và phạm vi
 * phụ trách đọc từ schema {@code task} bằng vai trò {@code svc_task}; phần của cấp dưới không bị
 * tính hai lần; trang 200 hồ sơ có việc lá đạt p95 ≤ 500 ms; gói không khai báo không gọi MOD-02;
 * sự việc lọc theo cây đơn vị, theo kỳ hoặc theo ngày kết luận, tenant khác không thấy.
 */
class ScoringExtensionsIT {

  private static final int BUCKET = 20262;
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final TenantId t = TenantId.of(tenant);
  private SingleConnectionDataSource taskDs;
  private SingleConnectionDataSource appraisalDs;
  private JdbcTaskScoringReads reads;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String table : List.of("task", "task_assignee", "task_change_request")) {
        st.execute("SELECT task.fn_create_period_partitions('task." + table + "', " + BUCKET + ")");
      }
      st.execute(
          "SELECT appraisal.fn_create_period_partitions('appraisal.appraisal_profile', "
              + BUCKET
              + ")");
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    taskDs = connect("svc_task", tenant);
    appraisalDs = connect("svc_appraisal", tenant);
    reads = new JdbcTaskScoringReads(JdbcClient.create(taskDs), new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    taskDs.destroy();
    appraisalDs.destroy();
  }

  @Test
  void viecLaVaPhamViPhuTrachKhongTinhHaiLan() throws SQLException {
    UUID lead = UUID.randomUUID();
    UUID worker = UUID.randomUUID();
    UUID a = task(null, "NHIEM_VU", "DANG_THUC_HIEN", null, "{}");
    UUID b = task(a, "KPI", "DANG_THUC_HIEN", null, "{}");
    UUID l1 = task(a, "CONG_VIEC", "HOAN_THANH", "5", "{\"he_so_nhom\":\"NHOM_1\"}");
    UUID l2 = task(b, "CONG_VIEC", "HOAN_THANH", "10", "{\"he_so_nhom\":\"NHOM_2\"}");
    UUID l3 = task(b, "CONG_VIEC", "TAM_DUNG", "7", "{}");
    UUID draft = task(b, "CONG_VIEC", "NHAP", "99", "{}");
    for (UUID id : List.of(a, b)) {
      assign(id, lead);
    }
    for (UUID id : List.of(l1, l2, l3, draft)) {
      assign(id, worker);
    }

    List<ResponsibleItem> scope = reads.responsibleItems(t, period, lead);
    assertThat(scope).extracting(ResponsibleItem::itemId).containsExactlyInAnyOrder(a, b);
    ResponsibleItem itemA = scope.stream().filter(i -> i.itemId().equals(a)).findFirst().get();
    ResponsibleItem itemB = scope.stream().filter(i -> i.itemId().equals(b)).findFirst().get();
    assertThat(itemA.itemType()).isEqualTo("NHIEM_VU");
    assertThat(itemA.leafCount()).isEqualTo(1);
    assertThat(itemA.sumStandardScore()).isEqualByComparingTo("5");
    assertThat(itemB.itemType()).isEqualTo("KPI");
    assertThat(itemB.leafCount()).isEqualTo(2);
    assertThat(itemB.sumStandardScore()).isEqualByComparingTo("17");
    assertThat(itemB.sumCompletedScore()).isEqualByComparingTo("10");
    assertThat(
            scope.stream()
                .map(ResponsibleItem::sumStandardScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .as("tổng của hai mục bằng tổng việc lá, không cộng hai lần")
        .isEqualByComparingTo("22");

    List<WorkItemSnapshot> items = reads.workItems(t, period, worker, null, 10);
    assertThat(items).extracting(WorkItemSnapshot::taskId).containsExactlyInAnyOrder(l1, l2, l3);
    WorkItemSnapshot w2 = items.stream().filter(i -> i.taskId().equals(l2)).findFirst().get();
    assertThat(w2.parentTaskId()).isEqualTo(b);
    assertThat(w2.coefficientLevels()).containsEntry("he_so_nhom", "NHOM_2");
    assertThat(w2.statusAtLock()).isEqualTo("HOAN_THANH");
    assertThat(w2.qualityLevel()).isNull();
    assertThat(
            items.stream()
                .filter(i -> i.taskId().equals(l3))
                .findFirst()
                .get()
                .deferredByDecision())
        .isTrue();
    assertThat(reads.workItems(t, period, lead, null, 10)).isEmpty();

    List<WorkItemSnapshot> first = reads.workItems(t, period, worker, null, 2);
    List<WorkItemSnapshot> rest = reads.workItems(t, period, worker, first.get(1).taskId(), 2);
    assertThat(first).hasSize(2);
    assertThat(rest).hasSize(1);
    assertThat(reads.workItems(t, period, List.of(worker, lead), 2))
        .containsOnlyKeys(worker)
        .satisfies(m -> assertThat(m.get(worker)).hasSize(2));

    SingleConnectionDataSource other = connect("svc_task", UUID.randomUUID());
    try {
      assertThat(
              new JdbcTaskScoringReads(JdbcClient.create(other), new ObjectMapper())
                  .workItems(t, period, worker, null, 10))
          .isEmpty();
    } finally {
      other.destroy();
    }
  }

  @Test
  void trangHaiTramHoSoCoViecLaDatP95() throws SQLException {
    int people = 200;
    int perPerson = 25;
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TEMP TABLE p AS SELECT gen_random_uuid() AS person FROM generate_series(1, "
              + people
              + ")");
      st.execute(
          "CREATE TEMP TABLE w AS SELECT gen_random_uuid() AS id, p.person, g FROM p,"
              + " generate_series(1, "
              + perPerson
              + ") g");
      st.execute(
          "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
              + " code, title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
              + " state_machine_version, status, standard_score, difficulty, node_kind)"
              + " SELECT w.id, '"
              + tenant
              + "', w.person, w.person, '"
              + period
              + "', "
              + BUCKET
              + ", 'P-' || substr(w.id::text, 1, 28), 'Việc đo', 'THUONG_XUYEN', 'KE_HOACH',"
              + " w.person, date '2027-03-31', 'Việc', '\\x01', 'v1', 'DA_GIAO', 4, 3,"
              + " 'CONG_VIEC' FROM w");
      st.execute(
          "INSERT INTO task.task_assignee (id, tenant_id, created_by, updated_by, period_id,"
              + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
              + " SELECT gen_random_uuid(), '"
              + tenant
              + "', w.person, w.person, '"
              + period
              + "', "
              + BUCKET
              + ", w.id, 'CA_NHAN', w.person, 'CHU_TRI', date '2027-01-01' FROM w");
      st.execute(
          "INSERT INTO appraisal.appraisal_profile (id, tenant_id, created_by, updated_by,"
              + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id,"
              + " job_title_id, policy_pack_version_id) SELECT gen_random_uuid(), '"
              + tenant
              + "', p.person, p.person, '"
              + period
              + "', "
              + BUCKET
              + ", p.person, 'DANG', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '"
              + WORK_PACK
              + "' FROM p");
      st.execute("ANALYZE task.task");
      st.execute("ANALYZE task.task_assignee");
    }
    ScoringInputService inputs =
        new ScoringInputService(
            new JdbcScoringInputRepository(JdbcClient.create(appraisalDs)), Clock.systemUtc());
    AtomicInteger taskCalls = new AtomicInteger();
    ScoringExtensionService service =
        new ScoringExtensionService(
            new JdbcScoringInputRepository(JdbcClient.create(appraisalDs)),
            policies(),
            taskApi(taskCalls),
            null,
            null);

    List<Long> millis = new ArrayList<>();
    Map<UUID, Extensions> last = Map.of();
    for (int run = 0; run < 25; run++) {
      long start = System.nanoTime();
      List<ScoringInput> page = inputs.page(t, period, null, 200);
      last = service.extend(t, period, page);
      millis.add((System.nanoTime() - start) / 1_000_000);
    }
    assertThat(last).hasSize(people);
    assertThat(last.values()).allSatisfy(e -> assertThat(e.workItems()).hasSize(perPerson));
    assertThat(taskCalls.get()).as("một truy vấn việc lá mỗi trang").isEqualTo(25);
    List<Long> sorted = millis.stream().sorted().toList();
    long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    System.out.println("CORE-04-13 p95 trang 200 hồ sơ có việc lá: " + p95 + " ms " + millis);
    assertThat(p95)
        .as("p95 một trang 200 hồ sơ có việc lá (ms), các lần: " + millis)
        .isLessThanOrEqualTo(500);

    // Cùng trang nhưng gói không khai báo: không gọi MOD-02.
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "UPDATE appraisal.appraisal_profile SET policy_pack_version_id = '"
              + PLAIN_PACK
              + "' WHERE tenant_id = '"
              + tenant
              + "'");
    }
    int before = taskCalls.get();
    assertThat(service.extend(t, period, inputs.page(t, period, null, 200))).isEmpty();
    assertThat(taskCalls.get()).isEqualTo(before);
  }

  @Test
  void suViecLocTheoCayDonViVaKy() throws SQLException {
    UUID root = UUID.randomUUID();
    UUID child = UUID.randomUUID();
    UUID outside = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO org_replica.unit_closure_v (tenant_id, tree, ancestor_id, descendant_id,"
              + " depth) VALUES ('"
              + tenant
              + "', 'CHUYEN_MON', '"
              + root
              + "', '"
              + child
              + "', 1)");
      st.execute(profileSql(person, child));
      st.execute(profileSql(stranger, outside));
    }
    JdbcIncidentRepository repo = new JdbcIncidentRepository(JdbcClient.create(appraisalDs));
    IncidentService service =
        new IncidentService(repo, new PrefixSealer(), null, null, Clock.systemUtc());
    UUID inPeriod =
        service.newIncident(t, person, cmd(person, period, "2027-02-01", "Khiển trách")).row().id();
    UUID byDate = service.newIncident(t, person, cmd(person, null, "2027-03-15", null)).row().id();
    service.newIncident(t, person, cmd(person, null, "2026-12-31", null));
    UUID foreign =
        service.newIncident(t, person, cmd(stranger, period, "2027-02-02", null)).row().id();

    assertThat(service.list(t, root, null, null, null, 10))
        .extracting(v -> v.row().id())
        .hasSize(3)
        .doesNotContain(foreign)
        .isSortedAccordingTo((x, y) -> y.toString().compareTo(x.toString()));
    assertThat(service.list(t, root, person, period, null, 10))
        .extracting(v -> v.row().id())
        .containsExactly(inPeriod);
    assertThat(service.find(t, root, inPeriod).summary()).isEqualTo("Khiển trách");
    assertThat(
            JdbcClient.create(appraisalDs)
                .sql("SELECT summary_enc FROM appraisal.incident WHERE id = ?")
                .param(inPeriod)
                .query(byte[].class)
                .single())
        .asString(StandardCharsets.UTF_8)
        .startsWith("enc:");
    assertThat(repo.withinUnit(t, root, outside)).isFalse();

    Map<UUID, List<IncidentRow>> facts =
        repo.forPersons(
            t,
            period,
            LocalDate.parse("2027-01-01"),
            LocalDate.parse("2027-03-31"),
            List.of(person, stranger));
    assertThat(facts.get(person)).extracting(IncidentRow::id).containsExactly(inPeriod, byDate);
    assertThat(facts.get(stranger)).extracting(IncidentRow::id).containsExactly(foreign);
    assertThat(repo.forPersons(t, period, null, null, List.of(person)).get(person))
        .extracting(IncidentRow::id)
        .containsExactly(inPeriod);

    SingleConnectionDataSource other = connect("svc_appraisal", UUID.randomUUID());
    try {
      assertThat(new JdbcIncidentRepository(JdbcClient.create(other)).find(t, inPeriod)).isEmpty();
    } finally {
      other.destroy();
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static final UUID WORK_PACK = UUID.randomUUID();
  private static final UUID PLAIN_PACK = UUID.randomUUID();

  private static PolicyQueryApi policies() {
    return new PolicyQueryApi() {
      @Override
      public Optional<PolicyFormulaView> findActivePolicy(
          TenantId tenantId, PeriodId periodId, String code) {
        return Optional.empty();
      }

      @Override
      public List<InputDeclaration> inputs(TenantId tenantId, UUID versionId) {
        return WORK_PACK.equals(versionId)
            ? List.of(
                new InputDeclaration("work_items", "list<WorkItem>", "task.aggregate.work_items"))
            : List.of();
      }
    };
  }

  /** {@link TaskApi} chỉ nối phần đọc lô việc lá vào kho đọc thật. */
  private TaskApi taskApi(AtomicInteger calls) {
    return new TaskApi() {
      @Override
      public Optional<TaskSummary> find(TenantId tenant, UUID taskId) {
        return Optional.empty();
      }

      @Override
      public List<TaskSummary> listForPerson(
          TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit) {
        return List.of();
      }

      @Override
      public List<TaskSummary> listForUnit(
          TenantId tenant, PeriodId period, UUID orgUnitId, UUID afterId, int limit) {
        return List.of();
      }

      @Override
      public TransitionOutcome requestTransition(TenantId tenant, TransitionRequest request) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Map<UUID, List<WorkItemSnapshot>> listWorkItems(
          TenantId tenant, PeriodId period, Collection<UUID> personIds, int limitPerPerson) {
        calls.incrementAndGet();
        return reads.workItems(tenant, period.value(), personIds, limitPerPerson);
      }
    };
  }

  private UUID task(UUID parent, String kind, String status, String score, String levels)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO task.task (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
              + " code, title, task_group, source, assigner_id, due_date, unit, dup_fingerprint,"
              + " state_machine_version, status, completed_at, standard_score, node_kind,"
              + " parent_task_id, coefficient_levels) VALUES ('"
              + id
              + "', '"
              + tenant
              + "', gen_random_uuid(), gen_random_uuid(), '"
              + period
              + "', "
              + BUCKET
              + ", '"
              + id.toString().substring(0, 30)
              + "', 'Việc thử', 'THEN_CHOT', 'KE_HOACH', gen_random_uuid(), date '2027-03-31',"
              + " 'Việc', '\\x01', 'v1', '"
              + status
              + "', "
              + ("HOAN_THANH".equals(status) ? "now()" : "NULL")
              + ", "
              + (score == null ? "NULL" : score)
              + ", '"
              + kind
              + "', "
              + (parent == null ? "NULL" : "'" + parent + "'")
              + ", '"
              + levels
              + "')");
    }
    return id;
  }

  private void assign(UUID taskId, UUID person) throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO task.task_assignee (id, tenant_id, created_by, updated_by, period_id,"
              + " period_bucket, task_id, assignee_type, person_id, role, valid_from) VALUES"
              + " (gen_random_uuid(), '"
              + tenant
              + "', gen_random_uuid(), gen_random_uuid(), '"
              + period
              + "', "
              + BUCKET
              + ", '"
              + taskId
              + "', 'CA_NHAN', '"
              + person
              + "', 'CHU_TRI', date '2027-01-01')");
    }
  }

  private String profileSql(UUID person, UUID unit) {
    return "INSERT INTO appraisal.appraisal_profile (id, tenant_id, created_by, updated_by,"
        + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
        + " policy_pack_version_id) VALUES (gen_random_uuid(), '"
        + tenant
        + "', gen_random_uuid(), gen_random_uuid(), '"
        + period
        + "', "
        + BUCKET
        + ", '"
        + person
        + "', 'DANG', '"
        + unit
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid())";
  }

  private static NewIncident cmd(UUID person, UUID period, String date, String summary) {
    return new NewIncident(
        person, period, "KY_LUAT", LocalDate.parse(date), "12-QĐ/UBKT", null, "LOAI_TRU", summary);
  }

  private static final class PrefixSealer implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null || plain.isEmpty()
          ? null
          : new SealedText(("enc:" + plain).getBytes(StandardCharsets.UTF_8), UUID.randomUUID());
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null
          ? null
          : new String(sealed.cipher(), StandardCharsets.UTF_8).substring("enc:".length());
    }
  }

  private static SingleConnectionDataSource connect(String role, UUID tenantId)
      throws SQLException {
    Connection c =
        TestPostgres.connectAsTenant(
            pg.getJdbcUrl(), role, MissionCoreTestDatabase.TEST_DB_PASSWORD, tenantId);
    return new SingleConnectionDataSource(c, true);
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
