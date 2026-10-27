package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đầu vào tính điểm với CSDL thật (CORE-04-06): duyệt hết 10.000 hồ sơ qua con trỏ trang 200 không
 * trùng, không sót dù có chèn đồng thời; tiêu chí chung ưu tiên bước thẩm định; nhiệm vụ kết thúc
 * đánh dấu người được giao đúng một lần, bỏ qua hồ sơ đã khoá; tenant khác không thấy.
 */
class ScoringInputIT {

  private static final int BUCKET = 20274;
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private SingleConnectionDataSource ds;
  private JdbcScoringInputRepository repo;
  private ScoringInputService service;
  private TransactionTemplate tx;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement st = c.createStatement()) {
      for (String t : List.of("appraisal_profile", "general_criteria_result")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + BUCKET + ")");
      }
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    ds = connect(tenant);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    repo = new JdbcScoringInputRepository(JdbcClient.create(ds));
    service = new ScoringInputService(repo, Clock.systemUtc());
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void duyetHetMuoiNghinHoSoKhongTrungKhongSotKhiCoChenDongThoi() throws SQLException {
    insertProfiles(10_000, period);
    Set<UUID> before = new HashSet<>(ids());
    List<UUID> seen = new ArrayList<>();
    UUID after = null;
    int pages = 0;
    while (true) {
      List<ScoringInput> page = service.page(TenantId.of(tenant), period, after, 200);
      page.forEach(p -> seen.add(p.profileId()));
      pages++;
      if (pages == 10) {
        insertProfiles(300, period);
      }
      if (page.size() < 200) {
        break;
      }
      after = page.get(page.size() - 1).profileId();
    }
    assertThat(new HashSet<>(seen)).hasSameSizeAs(seen).containsAll(before);
    assertThat(seen.stream().map(UUID::toString).toList()).isSorted();
  }

  @Test
  void tieuChiChungUuTienThamDinhVaTenantKhacKhongThay() throws SQLException {
    insertProfiles(1, period);
    UUID profile = ids().get(0);
    criteria(profile, "TU_DANH_GIA", "TCC-01", false);
    criteria(profile, "THAM_DINH", "TCC-01", true);
    criteria(profile, "TU_DANH_GIA", "TCC-02", true);
    ScoringInput in = service.page(TenantId.of(tenant), period, null, 10).get(0);
    assertThat(in.flow()).isEqualTo("DANG");
    assertThat(in.leadership()).isTrue();
    assertThat(in.policyPackVersionId()).isNotNull();
    assertThat(in.generalCriteria())
        .containsExactly(
            new SelfAssessmentForm.Criterion("TCC-01", true),
            new SelfAssessmentForm.Criterion("TCC-02", true));
    assertThat(service.page(TenantId.of(tenant), UUID.randomUUID(), null, 10)).isEmpty();

    SingleConnectionDataSource other = connect(UUID.randomUUID());
    try {
      assertThat(
              new JdbcScoringInputRepository(JdbcClient.create(other))
                  .page(TenantId.of(tenant), period, null, 10))
          .isEmpty();
    } finally {
      other.destroy();
    }
  }

  @Test
  void nhiemVuKetThucDanhDauMotLanBoQuaHoSoDaKhoa() throws SQLException {
    insertProfiles(2, period);
    List<UUID> profiles = ids();
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(
          "UPDATE appraisal.appraisal_profile SET status = 'DA_KHOA', locked_at = now() WHERE id = '"
              + profiles.get(1)
              + "'");
    }
    List<UUID> people = persons();
    UUID event = ScoringInputService.eventKey(UUID.randomUUID(), "HOAN_THANH", Instant.now());
    TenantId t = TenantId.of(tenant);
    assertThat((Integer) tx.execute(x -> service.forAssignees(t, period, event, people)))
        .isEqualTo(1);
    assertThat((Integer) tx.execute(x -> service.forAssignees(t, period, event, people))).isZero();
    assertThat(
            JdbcClient.create(ds)
                .sql(
                    "SELECT count(*) FROM appraisal.appraisal_profile WHERE inputs_changed_at"
                        + " IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static SingleConnectionDataSource connect(UUID tenantId) throws SQLException {
    Connection c =
        TestPostgres.connectAsTenant(
            pg.getJdbcUrl(), "svc_appraisal", MissionCoreTestDatabase.TEST_DB_PASSWORD, tenantId);
    return new SingleConnectionDataSource(c, true);
  }

  private void insertProfiles(int n, UUID periodId) throws SQLException {
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(
          "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
              + " is_leadership, policy_pack_version_id) SELECT gen_random_uuid(),"
              + " gen_random_uuid(), gen_random_uuid(), '"
              + periodId
              + "', "
              + BUCKET
              + ", gen_random_uuid(), 'DANG', gen_random_uuid(), gen_random_uuid(),"
              + " gen_random_uuid(), true, gen_random_uuid() FROM generate_series(1, "
              + n
              + ")");
    }
  }

  private void criteria(UUID profile, String stage, String code, boolean met) throws SQLException {
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(
          "INSERT INTO appraisal.general_criteria_result (id, created_by, updated_by, period_id,"
              + " period_bucket, profile_id, stage, criteria_code, compliance_ratio, level, met)"
              + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '"
              + period
              + "', "
              + BUCKET
              + ", '"
              + profile
              + "', '"
              + stage
              + "', '"
              + code
              + "', 1, 'DAM_BAO', "
              + met
              + ")");
    }
  }

  private List<UUID> ids() {
    return JdbcClient.create(ds)
        .sql("SELECT id FROM appraisal.appraisal_profile WHERE period_id = ? ORDER BY id")
        .param(period)
        .query(UUID.class)
        .list();
  }

  private List<UUID> persons() {
    return JdbcClient.create(ds)
        .sql("SELECT person_id FROM appraisal.appraisal_profile WHERE period_id = ?")
        .param(period)
        .query(UUID.class)
        .list();
  }
}
