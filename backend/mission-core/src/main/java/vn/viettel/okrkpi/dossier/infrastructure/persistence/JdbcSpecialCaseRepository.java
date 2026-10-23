package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link SpecialCaseRepository} trên {@code dossier.special_case}. Người thuộc phạm vi đơn vị là
 * người được kiểm điểm hoặc thành phần của hội nghị kiểm điểm do đơn vị tổ chức trong kỳ, vì bảng
 * không có cột đơn vị (suy luận).
 */
public class JdbcSpecialCaseRepository implements SpecialCaseRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, person_id, case_code, detected_by, detail_enc, dek_id,"
          + " effect, effective_from, effective_to, legal_ref, confirmed_by, confirmed_at,"
          + " row_version";

  /** Điều kiện phạm vi (kỳ, đơn vị, chủ gói) trên bí danh {@code sc}. */
  private static final String SCOPE =
      """
      sc.period_bucket = :bucket AND sc.tenant_id = :tenant AND sc.period_id = :period
        AND (CAST(:person AS uuid) IS NULL OR sc.person_id = CAST(:person AS uuid))
        AND EXISTS (SELECT 1 FROM dossier.review_meeting m
                     WHERE m.period_bucket = sc.period_bucket AND m.tenant_id = sc.tenant_id
                       AND m.period_id = sc.period_id AND m.org_unit_id = :unit
                       AND (m.subject_person_id = sc.person_id
                            OR m.participants_json -> 'participants'
                               @> jsonb_build_array(jsonb_build_object('personId',
                                                                       sc.person_id::text))))
      """;

  private final JdbcClient jdbc;

  public JdbcSpecialCaseRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void lockPerson(TenantId tenant, UUID periodId, UUID personId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("dossier.special_case:" + tenant.value() + ":" + periodId + ":" + personId)
        .query()
        .listOfRows();
  }

  @Override
  public List<SpecialCaseRow> listForPerson(
      TenantId tenant, int bucket, UUID periodId, UUID personId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM dossier.special_case
                WHERE period_bucket = ? AND tenant_id = ? AND period_id = ? AND person_id = ?
                ORDER BY case_code
                """)
        .params(bucket, tenant.value(), periodId, personId)
        .query(JdbcSpecialCaseRepository::row)
        .list();
  }

  @Override
  public List<SpecialCaseRow> listForPeriod(TenantId tenant, int bucket, UUID periodId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM dossier.special_case
                WHERE period_bucket = ? AND tenant_id = ? AND period_id = ?
                ORDER BY person_id, case_code
                """)
        .params(bucket, tenant.value(), periodId)
        .query(JdbcSpecialCaseRepository::row)
        .list();
  }

  @Override
  public void insert(TenantId tenant, SpecialCaseRow row, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.special_case (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, person_id, case_code, detected_by, detail_enc, dek_id, effect,
                effective_from, effective_to, legal_ref, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            row.id(),
            tenant.value(),
            actor,
            actor,
            row.periodId(),
            row.periodBucket(),
            row.personId(),
            row.caseCode(),
            row.detectedBy(),
            row.detailCipher(),
            row.dekId(),
            row.effect(),
            date(row.effectiveFrom()),
            date(row.effectiveTo()),
            row.legalRef(),
            row.confirmedBy(),
            row.confirmedAt() == null ? null : Timestamp.from(row.confirmedAt()))
        .update();
  }

  @Override
  public long update(TenantId tenant, SpecialCaseRow row, long expectedVersion, UUID actor) {
    return jdbc.sql(
            """
            UPDATE dossier.special_case
               SET case_code = ?, detail_enc = ?, dek_id = ?, effect = ?, effective_from = ?,
                   effective_to = ?, legal_ref = ?, confirmed_by = ?, confirmed_at = ?,
                   updated_at = now(), updated_by = ?, row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
            RETURNING row_version
            """)
        .params(
            row.caseCode(),
            row.detailCipher(),
            row.dekId(),
            row.effect(),
            date(row.effectiveFrom()),
            date(row.effectiveTo()),
            row.legalRef(),
            row.confirmedBy(),
            row.confirmedAt() == null ? null : Timestamp.from(row.confirmedAt()),
            actor,
            row.periodBucket(),
            tenant.value(),
            row.id(),
            expectedVersion)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  @Override
  public Optional<SpecialCaseRow> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM dossier.special_case WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcSpecialCaseRepository::row)
        .optional();
  }

  @Override
  public Optional<SpecialCaseRow> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.special_case WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .params(tenant.value(), id)
        .query(JdbcSpecialCaseRepository::row)
        .optional();
  }

  @Override
  public List<SpecialCaseRow> list(TenantId tenant, Filter filter, UUID after, int limit) {
    return jdbc.sql(
            "SELECT sc."
                + COLUMNS.replace(", ", ", sc.")
                + " FROM dossier.special_case sc WHERE "
                + SCOPE
                + """
                  AND (CAST(:after AS uuid) IS NULL OR sc.id > CAST(:after AS uuid))
                ORDER BY sc.id
                LIMIT :limit
                """)
        .param("bucket", filter.periodBucket())
        .param("tenant", tenant.value())
        .param("period", filter.periodId())
        .param("person", filter.personId())
        .param("unit", filter.orgUnitId())
        .param("after", after)
        .param("limit", limit)
        .query(JdbcSpecialCaseRepository::row)
        .list();
  }

  @Override
  public List<GroupCount> groups(TenantId tenant, Filter filter) {
    return jdbc.sql(
            "SELECT sc.case_code, min(sc.legal_ref) AS legal_ref, count(*) AS n"
                + " FROM dossier.special_case sc WHERE "
                + SCOPE
                + " GROUP BY sc.case_code ORDER BY min(sc.legal_ref), sc.case_code")
        .param("bucket", filter.periodBucket())
        .param("tenant", tenant.value())
        .param("period", filter.periodId())
        .param("person", filter.personId())
        .param("unit", filter.orgUnitId())
        .query(
            (rs, n) ->
                new GroupCount(
                    rs.getString("case_code"), rs.getString("legal_ref"), rs.getLong("n")))
        .list();
  }

  @Override
  public Optional<PackageScope> packageScope(TenantId tenant, UUID packageId) {
    return jdbc.sql(
            """
            SELECT period_id, period_bucket, org_unit_id, person_id FROM dossier.dossier_package
             WHERE tenant_id = ? AND id = ?
            """)
        .params(tenant.value(), packageId)
        .query(
            (rs, n) ->
                new PackageScope(
                    rs.getObject("period_id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getObject("person_id", UUID.class)))
        .optional();
  }

  @Override
  public List<UUID> meetingUnitsOf(TenantId tenant, int bucket, UUID periodId, UUID personId) {
    return jdbc.sql(
            """
            SELECT DISTINCT org_unit_id FROM dossier.review_meeting
             WHERE period_bucket = ? AND tenant_id = ? AND period_id = ?
               AND (subject_person_id = ?
                    OR participants_json -> 'participants'
                       @> jsonb_build_array(jsonb_build_object('personId', CAST(? AS text))))
            """)
        .params(bucket, tenant.value(), periodId, personId, personId.toString())
        .query((rs, n) -> rs.getObject(1, UUID.class))
        .list();
  }

  private static Date date(LocalDate day) {
    return day == null ? null : Date.valueOf(day);
  }

  private static LocalDate day(ResultSet rs, String column) throws SQLException {
    Date d = rs.getDate(column);
    return d == null ? null : d.toLocalDate();
  }

  private static SpecialCaseRow row(ResultSet rs, int n) throws SQLException {
    Timestamp confirmed = rs.getTimestamp("confirmed_at");
    return new SpecialCaseRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("person_id", UUID.class),
        rs.getString("case_code"),
        rs.getString("detected_by"),
        rs.getBytes("detail_enc"),
        rs.getObject("dek_id", UUID.class),
        rs.getString("effect"),
        day(rs, "effective_from"),
        day(rs, "effective_to"),
        rs.getString("legal_ref"),
        rs.getObject("confirmed_by", UUID.class),
        confirmed == null ? null : confirmed.toInstant(),
        rs.getLong("row_version"));
  }
}
