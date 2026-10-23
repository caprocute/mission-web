package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.transfer.RatingTransferService;
import vn.viettel.okrkpi.appraisal.infrastructure.persistence.JdbcRatingHistory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.transfer.DossierTransferService;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng thu thập của MOD-04 và MOD-06 cho gói chuyển liên cell (DV-13-10) trên PostgreSQL thật, đọc
 * bằng vai trò dịch vụ có RLS: chỉ mức đã chốt, chỉ gói cá nhân đã ký, chỉ kết luận kiểm điểm (giải
 * mã C2, không lộ người góp ý, ghi sổ cái), không lẫn tenant khác.
 */
class TransferPartsIT {

  static final int BUCKET = 20413;
  static final int ANNUAL_BUCKET = 20410;
  static PostgreSQLContainer<?> pg;

  final UUID tenant = UUID.randomUUID();
  final UUID other = UUID.randomUUID();
  final UUID person = UUID.randomUUID();
  final UUID period = UUID.randomUUID();
  final UUID annualPeriod = UUID.randomUUID();

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "SELECT appraisal.fn_create_period_partitions('appraisal.appraisal_profile', "
              + BUCKET
              + ")");
      st.execute(
          "SELECT appraisal.fn_create_period_partitions('appraisal.annual_rating', "
              + ANNUAL_BUCKET
              + ")");
      for (String t : List.of("review_meeting", "review_comment", "dossier_package")) {
        st.execute(
            "SELECT dossier.fn_create_period_partitions('dossier."
                + t
                + "'::regclass, "
                + BUCKET
                + ")");
      }
    }
  }

  @Test
  void ratingsCarryOnlyDecidedResults() throws SQLException {
    UUID decided = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute("SET session_replication_role = replica");
      profile(st, tenant, decided, period, "DA_KHOA", "'HTTNV'", "NHA_NUOC");
      profile(
          st, tenant, UUID.randomUUID(), UUID.randomUUID(), "DA_QUYET_DINH", "'HTXSNV'", "DANG");
      profile(st, other, UUID.randomUUID(), period, "DA_KHOA", "'KHTNV'", "DANG");
      st.execute(
          "INSERT INTO appraisal.annual_rating (id, tenant_id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, flow, profile_id, quarter_ratings, annual_score,"
              + " rating_code) VALUES (gen_random_uuid(), '"
              + tenant
              + "', gen_random_uuid(), gen_random_uuid(), '"
              + annualPeriod
              + "', "
              + ANNUAL_BUCKET
              + ", '"
              + person
              + "', 'NHA_NUOC', '"
              + decided
              + "', ARRAY['HTTNV','HTNV']::varchar(8)[], 82.500, 'HTTNV')");
    }
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(TenantId.of(tenant), PeriodId.of(period)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(period),
                    TenantId.of(tenant),
                    "Q3-2026",
                    "Quý III năm 2026",
                    LocalDate.parse("2026-07-01"),
                    LocalDate.parse("2026-09-30"),
                    PeriodStatus.DA_KHOA)));
    try (Connection c = service("svc_appraisal", tenant)) {
      RatingTransferService svc =
          new RatingTransferService(
              new JdbcRatingHistory(JdbcClient.create(new SingleConnectionDataSource(c, true))),
              periods);
      RatingTransferService.Ratings r = svc.ratings(TenantId.of(tenant), person).orElseThrow();
      assertThat(r.periods())
          .singleElement()
          .satisfies(
              p -> {
                assertThat(p.rating()).isEqualTo("HTTNV");
                assertThat(p.flow()).isEqualTo("NHA_NUOC");
                assertThat(p.period().code()).isEqualTo("Q3-2026");
              });
      assertThat(r.annual())
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.quarterRatings()).containsExactly("HTTNV", "HTNV");
                assertThat(a.annualScore()).isEqualByComparingTo("82.5");
                assertThat(a.period().code()).isEqualTo(annualPeriod.toString());
              });
      assertThat(svc.ratings(TenantId.of(tenant), UUID.randomUUID())).isEmpty();
    }
  }

  @Test
  void dossierPartsCarrySignedPackagesAndConclusionsOnly() throws SQLException {
    CommentSealer sealer = sealer();
    TenantId t = TenantId.of(tenant);
    UUID conclusion = UUID.randomUUID();
    UUID suggestion = UUID.randomUUID();
    UUID commenter = UUID.randomUUID();
    byte[] hash = new byte[32];
    hash[0] = (byte) 0xab;
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute("SET session_replication_role = replica");
      pkg(st, tenant, "GOI-1", "NAM", "DA_NOP", hash, person);
      pkg(st, tenant, "GOI-2", "QUY", "CHO_KY", null, person);
      pkg(st, tenant, "GOI-3", "NAM", "DA_NOP", hash, null);
      pkg(st, other, "GOI-4", "NAM", "DA_NOP", hash, person);
      comment(
          st,
          tenant,
          conclusion,
          commenter,
          "KET_LUAN",
          sealer.seal(t, conclusion, "Cần khắc phục"));
      comment(
          st, tenant, suggestion, commenter, "GOP_Y", sealer.seal(t, suggestion, "Góp ý riêng"));
    }
    AuditLogger audit = mock(AuditLogger.class);
    try (Connection c = service("svc_dossier", tenant)) {
      DossierTransferService svc =
          new DossierTransferService(
              new JdbcDossierHistory(JdbcClient.create(new SingleConnectionDataSource(c, true))),
              sealer,
              null,
              audit);
      DossierTransferService.SignedDossiers d = svc.signedDossiers(t, person).orElseThrow();
      assertThat(d.packages())
          .singleElement()
          .satisfies(
              p -> {
                assertThat(p.code()).isEqualTo("GOI-1");
                assertThat(p.packageSha256()).startsWith("ab").hasSize(64);
                assertThat(p.manifestSha256()).isEqualTo(p.packageSha256());
              });
      DossierTransferService.Conclusions k = svc.conclusions(t, person).orElseThrow();
      assertThat(k.items())
          .singleElement()
          .satisfies(
              i -> {
                assertThat(i.content()).isEqualTo("Cần khắc phục");
                assertThat(i.kind()).isEqualTo("HAN_CHE");
                assertThat(i.remediationDue()).isEqualTo(LocalDate.parse("2026-12-31"));
              });
      assertThat(k.toString()).doesNotContain(commenter.toString(), "Góp ý riêng");
      ArgumentCaptor<AuditEvent> e = ArgumentCaptor.forClass(AuditEvent.class);
      verify(audit).record(e.capture());
      assertThat(e.getValue().type()).isEqualTo(AuditEventType.SENSITIVE_DATA_ACCESS);
      assertThat(e.getValue().classification()).isEqualTo(Classification.C2);

      AuditLogger quiet = mock(AuditLogger.class);
      DossierTransferService none =
          new DossierTransferService(
              new JdbcDossierHistory(JdbcClient.create(new SingleConnectionDataSource(c, true))),
              sealer,
              null,
              quiet);
      assertThat(none.conclusions(t, UUID.randomUUID())).isEmpty();
      assertThat(none.signedDossiers(t, UUID.randomUUID())).isEmpty();
      verify(quiet, never()).record(any());
    }
  }

  private void profile(
      Statement st, UUID t, UUID id, UUID periodId, String status, String rating, String flow)
      throws SQLException {
    st.execute(
        "INSERT INTO appraisal.appraisal_profile (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
            + " policy_pack_version_id, status, final_rating_code, decided_at, locked_at) VALUES ('"
            + id
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), '"
            + periodId
            + "', "
            + BUCKET
            + ", '"
            + person
            + "', '"
            + flow
            + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '"
            + status
            + "', "
            + rating
            + ", now(), "
            + ("DA_KHOA".equals(status) ? "now()" : "NULL")
            + ")");
  }

  private void pkg(
      Statement st, UUID t, String code, String kind, String status, byte[] hash, UUID personId)
      throws SQLException {
    String h = hash == null ? "NULL" : "'\\x" + java.util.HexFormat.of().formatHex(hash) + "'";
    st.execute(
        "INSERT INTO dossier.dossier_package (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, code, org_unit_id, person_id, package_kind, status, manifest_hash,"
            + " package_sha256, submitted_at) VALUES (gen_random_uuid(), '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), '"
            + period
            + "', "
            + BUCKET
            + ", '"
            + code
            + "', gen_random_uuid(), "
            + (personId == null ? "NULL" : "'" + personId + "'")
            + ", '"
            + kind
            + "', '"
            + status
            + "', "
            + h
            + ", "
            + h
            + ", "
            + (hash == null ? "NULL" : "now()")
            + ")");
  }

  private void comment(
      Statement st, UUID t, UUID id, UUID commenter, String commentKind, CommentSealer.Sealed s)
      throws SQLException {
    st.execute(
        "INSERT INTO dossier.review_comment (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, meeting_id, subject_person_id, commenter_person_id, comment_kind,"
            + " kind, phase, comment_enc, dek_id, remediation_due) VALUES ('"
            + id
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), '"
            + period
            + "', "
            + BUCKET
            + ", gen_random_uuid(), '"
            + person
            + "', '"
            + commenter
            + "', '"
            + commentKind
            + "', 'HAN_CHE', 'HOI_NGHI', '\\x"
            + java.util.HexFormat.of().formatHex(s.cipher())
            + "', '"
            + s.dekId()
            + "', '2026-12-31')");
  }

  private static CommentSealer sealer() {
    DekService deks = mock(DekService.class);
    UUID dekId = UUID.randomUUID();
    when(deks.getOrCreateActiveDek(any(), any())).thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
    when(deks.getDekForDecryption(any(), any())).thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
    return new FieldEncryptorCommentSealer(new FieldEncryptor(deks, new JcaAesGcmCryptoProvider()));
  }

  private static CachedDek dek(UUID dekId, UUID tenant) {
    return new CachedDek(
        dekId,
        tenant,
        DekPurpose.GOP_Y_KIEM_DIEM,
        LocalDate.of(2026, 10, 1),
        DekStatus.ACTIVE,
        new byte[32]);
  }

  private static Connection service(String role, UUID tenant) throws SQLException {
    Connection c =
        DriverManager.getConnection(
            pg.getJdbcUrl(), role, MissionCoreTestDatabase.TEST_DB_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
