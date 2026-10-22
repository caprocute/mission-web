package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PolicyResolutionRepository} trên {@code policyDataSource}. */
public class JdbcPolicyResolutionRepository implements PolicyResolutionRepository {

  private static final String VERSION_COLUMNS =
      "p.id AS pack_id, p.code, p.origin, p.flow, v.id AS version_id, v.version, v.status,"
          + " v.content_hash, v.bundle_object_key, v.effective_from, v.manifest::text AS manifest";

  private final JdbcClient jdbc;

  public JdbcPolicyResolutionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<ResolvedPack> matchBinding(
      TenantId tenant, UUID periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    return jdbc.sql(
            """
            SELECT p.id AS pack_id, p.code, v.id AS version_id, v.version, v.content_hash,
                   v.bundle_object_key, b.id AS binding_id, b.is_frozen
              FROM policy.policy_binding b
              JOIN policy.policy_pack_version v ON v.id = b.policy_pack_version_id
              JOIN policy.policy_pack p ON p.id = v.policy_pack_id
             WHERE b.tenant_id = ? AND b.period_id = ? AND b.flow IN (?, 'CA_HAI')
               AND (b.subject_group_id IS NULL OR b.subject_group_id = ?)
               AND (b.org_unit_id IS NULL OR b.org_unit_id = ?)
               AND v.content_hash IS NOT NULL
             ORDER BY (b.org_unit_id IS NOT NULL) DESC, (b.subject_group_id IS NOT NULL) DESC,
                      (b.flow = ?) DESC, b.id
             LIMIT 1
            """)
        .param(tenant.value())
        .param(periodId)
        .param(flow)
        .param(subjectGroupId)
        .param(orgUnitId)
        .param(flow)
        .query(
            (rs, i) ->
                new ResolvedPack(
                    rs.getObject("pack_id", UUID.class),
                    rs.getString("code"),
                    rs.getObject("version_id", UUID.class),
                    rs.getString("version"),
                    HexFormat.of().formatHex(rs.getBytes("content_hash")),
                    rs.getString("bundle_object_key"),
                    rs.getObject("binding_id", UUID.class),
                    rs.getBoolean("is_frozen")))
        .optional();
  }

  @Override
  public List<PublishedVersion> publishedVersions(TenantId tenant, String flow) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version v"
                + " JOIN policy.policy_pack p ON p.id = v.policy_pack_id"
                + " WHERE v.tenant_id = ? AND p.flow = ? AND v.status IN ('DA_PHAT_HANH', 'NGUNG')"
                + " ORDER BY p.code, v.id")
        .param(tenant.value())
        .param(flow)
        .query(JdbcPolicyResolutionRepository::version)
        .list();
  }

  @Override
  public void pinBinding(
      TenantId tenant,
      UUID bindingId,
      UUID versionId,
      UUID periodId,
      String flow,
      boolean frozen,
      UUID actor) {
    jdbc.sql(
            """
            INSERT INTO policy.policy_binding
              (id, tenant_id, created_by, updated_by, policy_pack_version_id, period_id, flow,
               subject_group_id, org_unit_id, is_frozen)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            ON CONFLICT ON CONSTRAINT uq_policy_binding__scope DO NOTHING
            """)
        .param(bindingId)
        .param(tenant.value())
        .param(actor)
        .param(actor)
        .param(versionId)
        .param(periodId)
        .param(flow)
        .param(frozen)
        .update();
  }

  @Override
  public Optional<PublishedVersion> version(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version v"
                + " JOIN policy.policy_pack p ON p.id = v.policy_pack_id"
                + " WHERE v.tenant_id = ? AND v.id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyResolutionRepository::version)
        .optional();
  }

  @Override
  public Optional<PublishedVersion> versionByCode(TenantId tenant, String code, String version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version v"
                + " JOIN policy.policy_pack p ON p.id = v.policy_pack_id"
                + " WHERE v.tenant_id = ? AND p.code = ? AND v.version = ?")
        .param(tenant.value())
        .param(code)
        .param(version)
        .query(JdbcPolicyResolutionRepository::version)
        .optional();
  }

  private static PublishedVersion version(ResultSet rs, int i) throws SQLException {
    byte[] hash = rs.getBytes("content_hash");
    return new PublishedVersion(
        rs.getObject("pack_id", UUID.class),
        rs.getString("code"),
        rs.getString("origin"),
        rs.getString("flow"),
        rs.getObject("version_id", UUID.class),
        rs.getString("version"),
        PolicyVersionStatus.valueOf(rs.getString("status")),
        hash == null ? null : HexFormat.of().formatHex(hash),
        rs.getString("bundle_object_key"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("manifest"));
  }
}
