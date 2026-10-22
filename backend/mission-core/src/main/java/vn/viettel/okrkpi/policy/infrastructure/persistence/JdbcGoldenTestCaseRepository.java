package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.domain.GoldenCaseSource;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link GoldenTestCaseRepository} trên {@code policy.golden_test_case}, ghi {@code tenant_id}
 * tường minh.
 */
public class JdbcGoldenTestCaseRepository implements GoldenTestCaseRepository {

  private static final String COLUMNS =
      "id, policy_pack_id, case_code, description, input::text AS input,"
          + " expected::text AS expected, legal_ref, source, status, approved_by, approved_at,"
          + " signature, created_by, row_version";

  private final JdbcClient jdbc;

  public JdbcGoldenTestCaseRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<GoldenTestCase> findCase(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM policy.golden_test_case WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(id)
        .query(JdbcGoldenTestCaseRepository::row)
        .optional();
  }

  @Override
  public Optional<GoldenTestCase> findCaseByCode(TenantId tenant, UUID packId, String caseCode) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.golden_test_case WHERE tenant_id = ? AND policy_pack_id = ?"
                + " AND case_code = ?")
        .param(tenant.value())
        .param(packId)
        .param(caseCode)
        .query(JdbcGoldenTestCaseRepository::row)
        .optional();
  }

  @Override
  public List<GoldenTestCase> listCases(
      TenantId tenant,
      UUID packId,
      GoldenCaseStatus status,
      String q,
      String afterCode,
      int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.golden_test_case WHERE tenant_id = ? AND policy_pack_id = ?"
                + " AND (CAST(? AS varchar) IS NULL OR status = ?)"
                + " AND (CAST(? AS varchar) IS NULL OR case_code ILIKE ? OR description ILIKE ?)"
                + " AND (CAST(? AS varchar) IS NULL OR case_code > ?)"
                + " ORDER BY case_code LIMIT ?")
        .param(tenant.value())
        .param(packId)
        .param(status == null ? null : status.name())
        .param(status == null ? null : status.name())
        .param(q)
        .param(q == null ? null : "%" + q + "%")
        .param(q == null ? null : "%" + q + "%")
        .param(afterCode)
        .param(afterCode)
        .param(limit)
        .query(JdbcGoldenTestCaseRepository::row)
        .list();
  }

  @Override
  public boolean appendCase(TenantId tenant, GoldenTestCase c) {
    return jdbc.sql(
                """
                INSERT INTO policy.golden_test_case
                  (id, tenant_id, created_by, updated_by, policy_pack_id, case_code, description,
                   input, expected, legal_ref, source, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (policy_pack_id, case_code) DO NOTHING
                """)
            .param(c.id())
            .param(tenant.value())
            .param(c.createdBy())
            .param(c.createdBy())
            .param(c.packId())
            .param(c.caseCode())
            .param(c.description())
            .param(c.inputJson())
            .param(c.expectedJson())
            .param(c.legalRef())
            .param(c.source().name())
            .param(c.status().name())
            .update()
        == 1;
  }

  @Override
  public boolean replaceContent(TenantId tenant, GoldenTestCase c, long expected, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.golden_test_case
                   SET description = ?, input = ?::jsonb, expected = ?::jsonb, legal_ref = ?,
                       source = ?, updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ? AND status = 'NHAP'
                """)
            .param(c.description())
            .param(c.inputJson())
            .param(c.expectedJson())
            .param(c.legalRef())
            .param(c.source().name())
            .param(actor)
            .param(tenant.value())
            .param(c.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public boolean storeApproval(TenantId tenant, GoldenTestCase c, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.golden_test_case
                   SET status = 'DA_DUYET', approved_by = ?, approved_at = ?, signature = ?,
                       updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ? AND status = 'NHAP'
                """)
            .param(c.approvedBy())
            .param(Timestamp.from(c.approvedAt()))
            .param(c.signature())
            .param(c.approvedBy())
            .param(tenant.value())
            .param(c.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public boolean storeStatus(
      TenantId tenant, UUID id, GoldenCaseStatus status, long expected, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.golden_test_case
                   SET status = ?, updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(status.name())
            .param(actor)
            .param(tenant.value())
            .param(id)
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public SuiteSummary summary(TenantId tenant, UUID packId) {
    return jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE status = 'DA_DUYET') AS total,
                   count(*) FILTER (WHERE status = 'NHAP') AS pending,
                   max(approved_at) AS last_approved
              FROM policy.golden_test_case WHERE tenant_id = ? AND policy_pack_id = ?
            """)
        .param(tenant.value())
        .param(packId)
        .query(
            (rs, i) -> {
              Timestamp last = rs.getTimestamp("last_approved");
              return new SuiteSummary(
                  rs.getInt("total"), rs.getInt("pending"), last == null ? null : last.toInstant());
            })
        .single();
  }

  private static GoldenTestCase row(ResultSet rs, int i) throws SQLException {
    Timestamp approvedAt = rs.getTimestamp("approved_at");
    Instant at = approvedAt == null ? null : approvedAt.toInstant();
    return new GoldenTestCase(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_id", UUID.class),
        rs.getString("case_code"),
        rs.getString("description"),
        rs.getString("input"),
        rs.getString("expected"),
        rs.getString("legal_ref"),
        GoldenCaseSource.valueOf(rs.getString("source")),
        GoldenCaseStatus.valueOf(rs.getString("status")),
        rs.getObject("approved_by", UUID.class),
        at,
        rs.getBytes("signature"),
        rs.getObject("created_by", UUID.class),
        rs.getLong("row_version"));
  }
}
