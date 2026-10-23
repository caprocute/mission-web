package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.BoardView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryInput;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService.EntryView;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectEligibility;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Phân nhóm triển vọng với CSDL thật (CORE-04-12): bảng theo gợi ý, lưu nháp kiểm BR-10-09 và thứ
 * tự, trình cần đủ lý do, sau khi trình không sửa được, outbox không có nhóm; tập thể không thông
 * qua về nháp phiên bản mới; thông qua rồi ký xong thì {@code DA_KY}.
 */
class ProspectBoardIT {

  private static final int BUCKET = 20290;
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID annualPeriod = UUID.randomUUID();
  private final UUID quarterPeriod = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID weak = UUID.randomUUID();
  private final UUID flagged = UUID.randomUUID();
  private final UUID good = UUID.randomUUID();
  private final UUID leader = UUID.randomUUID();
  private SingleConnectionDataSource ds;
  private TransactionTemplate tx;
  private ProspectBoardService service;
  private final TenantId t = TenantId.of(tenant);

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String table :
          List.of("appraisal_profile", "annual_rating", "prospect_group", "prospect_board")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal."
                + table
                + "', "
                + BUCKET
                + ")");
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
      person(st, weak, "KHTNV", false, null);
      person(st, flagged, "HTNV", true, null);
      person(st, good, "HTTNV", false, "NHOM_2");
    }
    ds = new SingleConnectionDataSource(c, true);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(t, PeriodId.of(annualPeriod)))
        .thenReturn(Optional.of(period(annualPeriod, "2029-01-01", "2029-12-31")));
    when(periods.findById(t, PeriodId.of(quarterPeriod)))
        .thenReturn(Optional.of(period(quarterPeriod, "2029-10-01", "2029-12-31")));
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(ds),
            "appraisal",
            (topic, payload) ->
                new SerializedPayload(payload.toString().getBytes(StandardCharsets.UTF_8), 1L, 1),
            new ObjectMapper(),
            Clock.systemUtc());
    service =
        new ProspectBoardService(
            new JdbcProspectBoardRepository(JdbcClient.create(ds)),
            periods,
            new ProspectEligibility(null, null),
            new BatchDecisionIT.PlainSealer(),
            new OutboxProspectEvents(outbox));
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void luuNhapKiemQuyTacRoiTrinhVaKhoaChinhSua() {
    BoardView first = tx.execute(x -> service.view(t, annualPeriod, unit));
    assertThat(first.status()).isEqualTo("NHAP");
    assertThat(first.rowVersion()).isZero();
    assertThat(first.entries()).hasSize(3);
    assertThat(entry(first, weak).annualRating()).isEqualTo("KHTNV");
    assertThat(entry(first, flagged).reassignFlag()).isTrue();
    assertThat(entry(first, good).suggestedGroup()).isEqualTo("NHOM_2");

    assertThatThrownBy(
            () ->
                tx.execute(
                    x ->
                        service.save(
                            t,
                            annualPeriod,
                            unit,
                            0,
                            List.of(new EntryInput(weak, "NHOM_1", 1, null, null)),
                            leader)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(ProspectBoardService.NOT_ELIGIBLE));

    List<EntryInput> draft =
        List.of(
            new EntryInput(weak, "NHOM_3", 1, null, null),
            new EntryInput(flagged, "NHOM_4", 1, null, "KH-BD-1"),
            new EntryInput(good, "NHOM_1", 1, null, null));
    BoardView saved = tx.execute(x -> service.save(t, annualPeriod, unit, 0, draft, leader));
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThat(entry(saved, flagged).trainingPlanRef()).isEqualTo("KH-BD-1");

    assertThatThrownBy(() -> tx.execute(x -> service.submit(t, annualPeriod, unit, 1, leader)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(ProspectBoardService.REASON_REQUIRED));

    String reason = "Đồng chí chủ trì thành công đề án chuyển đổi số của đơn vị.";
    List<EntryInput> withReason =
        List.of(draft.get(0), draft.get(1), new EntryInput(good, "NHOM_1", 1, reason, null));
    BoardView again = tx.execute(x -> service.save(t, annualPeriod, unit, 1, withReason, leader));
    assertThat(entry(again, good).reason()).isEqualTo(reason);

    BoardView submitted = tx.execute(x -> service.submit(t, annualPeriod, unit, 2, leader));
    assertThat(submitted.status()).isEqualTo("DA_TRINH");
    assertThatThrownBy(
            () -> tx.execute(x -> service.save(t, annualPeriod, unit, 3, withReason, leader)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(ProspectBoardService.INVALID_STATUS));

    List<Map<String, Object>> events =
        new JdbcTemplate(ds)
            .queryForList(
                "SELECT aggregate_type, convert_from(payload, 'UTF8') AS body"
                    + " FROM appraisal.outbox_event WHERE tenant_id = ?",
                tenant);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("aggregate_type")).isEqualTo("prospect_board");
    assertThat((String) events.get(0).get("body"))
        .contains("DA_TRINH")
        .doesNotContain("NHOM_")
        .doesNotContain(good.toString());

    assertThatThrownBy(() -> tx.execute(x -> service.view(t, quarterPeriod, unit)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(ProspectBoardService.QUARTER));
  }

  @Test
  void khongThongQuaVeNhapRoiThongQuaVaKySo() {
    String reason = "Đồng chí chủ trì thành công đề án chuyển đổi số của đơn vị.";
    List<EntryInput> rows =
        List.of(
            new EntryInput(weak, "NHOM_3", 1, null, null),
            new EntryInput(flagged, "NHOM_4", 1, null, null),
            new EntryInput(good, "NHOM_1", 1, reason, null));
    tx.execute(x -> service.save(t, annualPeriod, unit, 0, rows, leader));
    tx.execute(x -> service.submit(t, annualPeriod, unit, 1, leader));

    BoardView back =
        tx.execute(
            x ->
                service.councilRejected(
                    t, annualPeriod, unit, "Cần cân nhắc lại thứ tự Nhóm 1.", leader));
    assertThat(back.status()).isEqualTo("NHAP");
    assertThat(back.boardVersion()).isEqualTo(2);
    assertThat(back.rejectionReason()).isEqualTo("Cần cân nhắc lại thứ tự Nhóm 1.");

    BoardView resubmitted =
        tx.execute(x -> service.submit(t, annualPeriod, unit, back.rowVersion(), leader));
    UUID sign = UUID.randomUUID();
    tx.execute(
        x -> {
          service.councilApproved(t, annualPeriod, unit, sign, leader);
          return null;
        });
    Instant signedAt = Instant.parse("2030-01-20T03:00:00Z");
    assertThat((boolean) tx.execute(x -> service.onSigned(t, UUID.randomUUID(), signedAt)))
        .isFalse();
    assertThat((boolean) tx.execute(x -> service.onSigned(t, sign, signedAt))).isTrue();
    assertThat((boolean) tx.execute(x -> service.onSigned(t, sign, signedAt))).isTrue();

    BoardView signed = tx.execute(x -> service.view(t, annualPeriod, unit));
    assertThat(signed.status()).isEqualTo("DA_KY");
    assertThat(signed.rowVersion()).isEqualTo(resubmitted.rowVersion() + 2);
    assertThat(signed.entries()).allSatisfy(e -> assertThat(e.decidedAt()).isEqualTo(signedAt));
    assertThatThrownBy(
            () -> tx.execute(x -> service.councilRejected(t, annualPeriod, unit, null, leader)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(ProspectBoardService.INVALID_STATUS));
  }

  private static EntryView entry(BoardView board, UUID person) {
    return board.entries().stream()
        .filter(e -> e.personId().equals(person))
        .findFirst()
        .orElseThrow();
  }

  private void person(Statement st, UUID person, String rating, boolean reassign, String suggested)
      throws SQLException {
    UUID profile = UUID.randomUUID();
    st.execute(
        "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
            + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
            + " policy_pack_version_id) VALUES ('"
            + profile
            + "', gen_random_uuid(), gen_random_uuid(), '"
            + annualPeriod
            + "', "
            + BUCKET
            + ", '"
            + person
            + "', 'DANG', '"
            + unit
            + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid())");
    st.execute(
        "INSERT INTO appraisal.annual_rating (id, created_by, updated_by, period_id, period_bucket,"
            + " person_id, flow, profile_id, system_level, reassign_flag) VALUES (gen_random_uuid(),"
            + " gen_random_uuid(), gen_random_uuid(), '"
            + annualPeriod
            + "', "
            + BUCKET
            + ", '"
            + person
            + "', 'DANG', '"
            + profile
            + "', '"
            + rating
            + "', "
            + reassign
            + ")");
    if (suggested != null) {
      st.execute(
          "INSERT INTO appraisal.prospect_group (id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, group_code, suggested_group) VALUES (gen_random_uuid(),"
              + " gen_random_uuid(), gen_random_uuid(), '"
              + annualPeriod
              + "', "
              + BUCKET
              + ", '"
              + person
              + "', '"
              + suggested
              + "', '"
              + suggested
              + "')");
    }
  }

  private PeriodSnapshot period(UUID id, String from, String to) {
    return new PeriodSnapshot(
        PeriodId.of(id), t, "K", "Kỳ", LocalDate.parse(from), LocalDate.parse(to), PeriodStatus.MO);
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
