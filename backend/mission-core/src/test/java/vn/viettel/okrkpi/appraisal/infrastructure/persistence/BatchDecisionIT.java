package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Command;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Item;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Prepared;
import vn.viettel.okrkpi.appraisal.application.decision.DecisionSigningService;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.LockCandidate;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Quyết định xếp loại hàng loạt với CSDL thật (CORE-04-07): lô 1.000 hồ sơ ghi một {@code
 * batch_decision} và 1.000 dòng mức {@code QUYET_DINH} gắn lô, hồ sơ giữ {@code CHO_QUYET_DINH};
 * gửi lại cùng số quyết định, cùng danh sách tiếp tục lô cũ; trùng số với danh sách khác bị 409; hồ
 * sơ người đứng đầu chọn sớm bị 422; hồ sơ ngoài phạm vi bị 403; huỷ lô chưa ký thì lô {@code HUY}
 * và hồ sơ trở về danh sách chờ quyết định. CORE-04-08: EVT-18 của lô chuyển 1.000 hồ sơ sang
 * {@code DA_KY_SO} và phát EVT-12 đúng một lần; hết hạn khiếu nại thì khoá, trừ hồ sơ đang khiếu
 * nại. Không đo thời gian phản hồi (YC-PT-19).
 */
class BatchDecisionIT {

  private static final int BUCKET = 20274;
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final UUID headUnit = UUID.randomUUID();
  private final UUID subUnit = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private SingleConnectionDataSource ds;
  private JdbcClient jdbc;
  private BatchDecisionService service;
  private DecisionSigningService signing;
  private AppraisalProfileService steps;
  private TransactionTemplate tx;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String t :
          List.of(
              "appraisal_profile",
              "score_snapshot",
              "batch_decision",
              "rating_proposal",
              "rating_explanation")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + BUCKET + ")");
      }
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    Connection c =
        DriverManager.getConnection(
            pg.getJdbcUrl(), "svc_appraisal", MissionCoreTestDatabase.TEST_DB_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    ds = new SingleConnectionDataSource(c, true);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    jdbc = JdbcClient.create(ds);
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(ds),
            "appraisal",
            (topic, payload) ->
                new SerializedPayload(payload.toString().getBytes(StandardCharsets.UTF_8), 1L, 1),
            new ObjectMapper(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    JdbcAppraisalProfileRepository profileRepo = new JdbcAppraisalProfileRepository(jdbc);
    steps =
        new AppraisalProfileService(
            profileRepo, new OutboxAppraisalStepEvents(outbox), null, Clock.systemUTC());
    service =
        new BatchDecisionService(
            profileRepo,
            new JdbcBatchDecisionRepository(jdbc),
            steps,
            new PlainSealer(),
            (t, p, who) -> Set.of(),
            null,
            Clock.systemUTC());
    signing =
        new DecisionSigningService(
            profileRepo,
            new JdbcBatchDecisionRepository(jdbc),
            steps,
            new OutboxDecisionNotices(outbox),
            null,
            null,
            Clock.systemUTC());
    try (Connection a = admin();
        Statement st = a.createStatement()) {
      st.execute(
          "INSERT INTO org_replica.unit_closure_v (tenant_id, tree, ancestor_id, descendant_id,"
              + " depth) VALUES ('"
              + tenant
              + "', 'DANG', '"
              + headUnit
              + "', '"
              + subUnit
              + "', 1)");
    }
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void loNghinHoSoGhiMotLanVaGuiLaiTiepTucLoCu() {
    TenantId t = TenantId.of(tenant);
    seed(subUnit, false, 1000);
    List<Item> items = items(subUnit, RatingLevel.HTTNV);
    assertThat(items).hasSize(1000);
    Command cmd = command("215-QĐ/ĐUK", items);
    Scope scope = new Scope("p.org_unit_id IN (?, ?)", List.of(headUnit, subUnit));
    Prepared prepared = tx.execute(x -> service.prepare(t, decider, cmd, scope));
    assertThat(
            jdbc.sql(
                    "SELECT status || ':' || profile_count || ':' || length(profiles_hash)"
                        + " FROM appraisal.batch_decision WHERE id = ?")
                .param(prepared.batchId())
                .query(String.class)
                .single())
        .isEqualTo("NHAP:1000:32");
    assertThat(
            count(
                "appraisal.rating_proposal WHERE level_kind = 'QUYET_DINH' AND is_current"
                    + " AND is_bulk_decision AND actor_role = 'decider' AND batch_decision_id = '"
                    + prepared.batchId()
                    + "'"))
        .isEqualTo(1000);
    assertThat(count("appraisal.appraisal_profile WHERE status = 'CHO_QUYET_DINH'"))
        .isEqualTo(1000);
    assertThat(count("appraisal.appraisal_profile WHERE row_version = 1")).isEqualTo(1000);

    UUID signRequest = UUID.randomUUID();
    tx.executeWithoutResult(x -> service.attachSignRequest(t, prepared, signRequest, decider));
    assertThat(count("appraisal.rating_proposal WHERE sign_request_id = '" + signRequest + "'"))
        .isEqualTo(1000);
    Prepared again = tx.execute(x -> service.prepare(t, decider, cmd, scope));
    assertThat(again.batchId()).isEqualTo(prepared.batchId());
    assertThat(again.signRequestId()).isEqualTo(signRequest);
    assertThat(count("appraisal.batch_decision")).isEqualTo(1);

    BatchDecisionService.SignSource source =
        service.signSource(t, prepared.batchId()).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signer()).isEqualTo(decider);
    assertThat(source.documents()).hasSize(1000);
    assertThat(service.view(t, prepared.batchId(), decider).orElseThrow().batch().status())
        .isEqualTo("CHO_KY");
    assertThat(service.page(t, decider, period, "DANG", Set.of("CHO_KY"), null, 10)).hasSize(1);

    // EVT-18 của cả lô: 1.000 hồ sơ chuyển DA_KY_SO, mỗi hồ sơ một EVT-12; sự kiện lặp bị bỏ qua.
    Set<String> hashes = new HashSet<>();
    source.documents().forEach(d -> hashes.add(HexFormat.of().formatHex(d.contentHash())));
    Instant signedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    assertThat(
            (Integer)
                tx.execute(
                    x -> signing.onSigned(t, prepared.batchId(), signRequest, hashes, signedAt)))
        .isEqualTo(1000);
    assertThat(
            (Integer)
                tx.execute(
                    x -> signing.onSigned(t, prepared.batchId(), signRequest, hashes, signedAt)))
        .isZero();
    assertThat(count("appraisal.appraisal_profile WHERE status = 'DA_KY_SO'")).isEqualTo(1000);
    assertThat(count("appraisal.outbox_event WHERE topic = 'appraisal.rating.decided.v1'"))
        .isEqualTo(1000);
    assertThat(service.view(t, prepared.batchId(), decider).orElseThrow().signedCount())
        .isEqualTo(1000);
    assertThat(service.view(t, prepared.batchId(), decider).orElseThrow().batch().status())
        .isEqualTo("DA_KY");
    assertThat(
            count(
                "appraisal.outbox_event WHERE topic = 'notify.message.requested.v1'"
                    + " AND aggregate_type = 'batch_decision'"))
        .isEqualTo(1);

    List<Item> other = new ArrayList<>(items.subList(0, 999));
    Command dup = command("215-QĐ/ĐUK", other);
    assertThatThrownBy(() -> service.prepare(t, decider, dup, scope))
        .hasMessage("APPRAISAL-409-DECISION-NO-DUPLICATE");
    List<Item> tooMany = new ArrayList<>(items);
    tooMany.add(new Item(UUID.randomUUID(), RatingLevel.HTNV, null, 0L));
    assertThatThrownBy(() -> service.prepare(t, decider, command("216-QĐ/ĐUK", tooMany), scope))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
  }

  @Test
  void nguoiDungDauPhamViVaHuyLo() {
    TenantId t = TenantId.of(tenant);
    seed(headUnit, true, 1);
    seed(subUnit, false, 1);
    List<Item> head = items(headUnit, RatingLevel.HTTNV);
    List<Item> sub = items(subUnit, RatingLevel.HTTNV);
    Scope scope = new Scope("p.org_unit_id IN (?, ?)", List.of(headUnit, subUnit));
    assertThatThrownBy(() -> service.prepare(t, decider, command("1-QĐ", head), scope))
        .isInstanceOf(BusinessException.class)
        .hasMessage("APPRAISAL-422-HEAD-TOO-EARLY")
        .satisfies(e -> assertThat(((BusinessException) e).msg()).isEqualTo("MSG-09-14"));
    assertThatThrownBy(
            () ->
                service.prepare(
                    t,
                    decider,
                    command("1-QĐ", head),
                    new Scope("p.org_unit_id = ?", List.of(UUID.randomUUID()))))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");

    List<Item> both = new ArrayList<>(head);
    both.addAll(sub);
    Prepared prepared = tx.execute(x -> service.prepare(t, decider, command("2-QĐ", both), scope));
    long version = service.view(t, prepared.batchId(), decider).orElseThrow().batch().rowVersion();
    BatchDecisionService.View cancelled =
        tx.execute(x -> service.cancel(t, prepared.batchId(), version, decider));
    assertThat(cancelled.batch().status()).isEqualTo("HUY");
    assertThat(cancelled.signedCount()).isZero();
    assertThat(count("appraisal.rating_proposal WHERE is_current")).isZero();
    assertThat(count("appraisal.appraisal_profile WHERE row_version = 2")).isEqualTo(2);
    // Hồ sơ cấp dưới đã trở lại chờ quyết định nên hồ sơ người đứng đầu vẫn bị chặn.
    List<Item> headAgain = items(headUnit, RatingLevel.HTTNV);
    assertThatThrownBy(() -> service.prepare(t, decider, command("3-QĐ", headAgain), scope))
        .hasMessage("APPRAISAL-422-HEAD-TOO-EARLY");
  }

  @Test
  void hetHanKhieuNaiThiKhoaTruHoSoDangKhieuNai() {
    TenantId t = TenantId.of(tenant);
    seed(subUnit, false, 2);
    List<Item> items = items(subUnit, RatingLevel.HTTNV);
    Scope scope = new Scope("p.org_unit_id = ?", List.of(subUnit));
    Prepared prepared = tx.execute(x -> service.prepare(t, decider, command("9-QĐ", items), scope));
    UUID signRequest = UUID.randomUUID();
    tx.executeWithoutResult(x -> service.attachSignRequest(t, prepared, signRequest, decider));
    Set<String> hashes = new HashSet<>();
    service
        .signSource(t, prepared.batchId())
        .orElseThrow()
        .documents()
        .forEach(d -> hashes.add(HexFormat.of().formatHex(d.contentHash())));
    tx.execute(x -> signing.onSigned(t, prepared.batchId(), signRequest, hashes, Instant.now()));
    UUID complained = items.get(0).profileId();
    UUID person =
        jdbc.sql("SELECT person_id FROM appraisal.appraisal_profile WHERE id = ?")
            .param(complained)
            .query(UUID.class)
            .single();
    tx.execute(
        x -> steps.transition(t, complained, null, person, (p, r) -> p.fileComplaint(person)));
    Instant later = Instant.now().plus(1, ChronoUnit.DAYS);
    List<LockCandidate> due =
        new JdbcBatchDecisionRepository(jdbc).signedProfiles(t, later, null, 10);
    assertThat(due).hasSize(1);
    LocalDate early = LocalDate.now().plusDays(3);
    assertThat((Boolean) tx.execute(x -> signing.lockIfDue(t, due.get(0), early, new HashMap<>())))
        .isFalse();
    LocalDate today = LocalDate.now().plusDays(30);
    assertThat((Boolean) tx.execute(x -> signing.lockIfDue(t, due.get(0), today, new HashMap<>())))
        .isTrue();
    assertThat((Boolean) tx.execute(x -> signing.lockIfDue(t, due.get(0), today, new HashMap<>())))
        .isFalse();
    assertThat(count("appraisal.appraisal_profile WHERE status = 'DA_KHOA'")).isEqualTo(1);
    assertThat(count("appraisal.appraisal_profile WHERE status = 'KHIEU_NAI'")).isEqualTo(1);
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** {@code n} hồ sơ chờ quyết định ở {@code unit}, mỗi hồ sơ một ảnh chụp điểm mức HTTNV. */
  private void seed(UUID unit, boolean head, int n) {
    String sha = "'\\x" + "ab".repeat(32) + "'";
    jdbc.sql(
            "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
                + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
                + " policy_pack_version_id, status, appraiser_id, is_head)"
                + " SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, ?,"
                + " gen_random_uuid(), 'DANG', ?, gen_random_uuid(), gen_random_uuid(),"
                + " gen_random_uuid(), 'CHO_QUYET_DINH', ?, ? FROM generate_series(1, ?)")
        .params(period, BUCKET, unit, appraiser, head, n)
        .update();
    jdbc.sql(
            "INSERT INTO appraisal.score_snapshot (id, created_by, updated_by, period_id,"
                + " period_bucket, profile_id, flow, snapshot_kind, score_result_id,"
                + " policy_pack_version_id, policy_content_hash, policy_version, input_hash,"
                + " total_score, auto_rating_code, rule_checks, group_scores_json, computed_at,"
                + " snapshot_hash) SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                + " p.period_id, p.period_bucket, p.id, 'DANG', 'CHOT_KY', gen_random_uuid(),"
                + " gen_random_uuid(), "
                + sha
                + ", 'KHOI_DANG v2.0', "
                + sha
                + ", 85, 'HTTNV', '[]', '{}', now(), "
                + sha
                + " FROM appraisal.appraisal_profile p WHERE p.org_unit_id = ?"
                + " AND p.current_snapshot_id IS NULL")
        .param(unit)
        .update();
    jdbc.sql(
            "UPDATE appraisal.appraisal_profile p SET current_snapshot_id = s.id"
                + " FROM appraisal.score_snapshot s WHERE s.profile_id = p.id"
                + " AND p.current_snapshot_id IS NULL")
        .update();
  }

  private List<Item> items(UUID unit, RatingLevel level) {
    return jdbc.sql(
            "SELECT id, row_version FROM appraisal.appraisal_profile WHERE org_unit_id = ?"
                + " ORDER BY id")
        .param(unit)
        .query((rs, i) -> new Item(rs.getObject(1, UUID.class), level, null, rs.getLong(2)))
        .list();
  }

  private Command command(String no, List<Item> items) {
    return new Command(period, "DANG", no, LocalDate.parse("2027-01-12"), false, items);
  }

  private long count(String from) {
    return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** Niêm phong giả: giữ nguyên byte. */
  static final class PlainSealer implements TextSealer {
    @Override
    public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
      return plain == null ? null : new SealedText(plain.getBytes(StandardCharsets.UTF_8), rowId);
    }

    @Override
    public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
      return sealed == null ? null : new String(sealed.cipher(), StandardCharsets.UTF_8);
    }
  }
}
