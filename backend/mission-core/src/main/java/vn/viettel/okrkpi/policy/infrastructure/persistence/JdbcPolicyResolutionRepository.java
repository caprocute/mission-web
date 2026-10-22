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
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PolicyResolutionRepository} trên {@code policyDataSource} (vai trò {@code svc_policy}).
 */
public class JdbcPolicyResolutionRepository implements PolicyResolutionRepository {

  private static final String VERSION_COLUMNS =
      "p.id AS pack_id, p.code, p.flow AS pack_flow, v.id AS version_id, v.version, v.status,"
          + " v.effective_from, v.manifest #>> '{metadata,hieu_luc,tu_ky}' AS tu_ky,"
          + " v.manifest #>> '{metadata,hieu_luc,den_ky}' AS den_ky, v.content_hash,"
          + " v.bundle_object_key";

  private static final String VERSION_FROM =
      " FROM policy.policy_pack_version v JOIN policy.policy_pack p ON p.id = v.policy_pack_id";

  private final JdbcClient jdbc;

  public JdbcPolicyResolutionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<BindingRow> bindings(TenantId tenant, UUID periodId, String flow) {
    return jdbc.sql(
            "SELECT b.id AS binding_id, b.flow AS binding_flow, b.subject_group_id, b.org_unit_id,"
                + " b.is_frozen, "
                + VERSION_COLUMNS
                + " FROM policy.policy_binding b"
                + " JOIN policy.policy_pack_version v ON v.id = b.policy_pack_version_id"
                + " JOIN policy.policy_pack p ON p.id = v.policy_pack_id"
                + " WHERE b.tenant_id = ? AND b.period_id = ? AND b.flow IN (?, 'CA_HAI')")
        .param(tenant.value())
        .param(periodId)
        .param(flow)
        .query(
            (rs, i) ->
                new BindingRow(
                    rs.getObject("binding_id", UUID.class),
                    rs.getString("binding_flow"),
                    rs.getObject("subject_group_id", UUID.class),
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getBoolean("is_frozen"),
                    version(rs, i)))
        .list();
  }

  @Override
  public List<PackVersionRow> releasedVersions(TenantId tenant, String flow) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + VERSION_FROM
                + " WHERE v.tenant_id = ? AND p.flow = ? AND v.status IN ('DA_PHAT_HANH', 'NGUNG')")
        .param(tenant.value())
        .param(flow)
        .query(JdbcPolicyResolutionRepository::version)
        .list();
  }

  @Override
  public List<PackVersionRow> versionsOfPack(TenantId tenant, String code) {
    return jdbc.sql(
            "SELECT " + VERSION_COLUMNS + VERSION_FROM + " WHERE v.tenant_id = ? AND p.code = ?")
        .param(tenant.value())
        .param(code)
        .query(JdbcPolicyResolutionRepository::version)
        .list();
  }

  @Override
  public Optional<PackVersionRow> version(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT " + VERSION_COLUMNS + VERSION_FROM + " WHERE v.tenant_id = ? AND v.id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyResolutionRepository::version)
        .optional();
  }

  @Override
  public Optional<String> manifest(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT manifest::text FROM policy.policy_pack_version WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(String.class)
        .optional();
  }

  @Override
  public boolean pin(
      TenantId tenant, UUID bindingId, UUID periodId, String flow, UUID versionId, UUID actor) {
    return jdbc.sql(
                """
                INSERT INTO policy.policy_binding
                  (id, tenant_id, created_by, updated_by, policy_pack_version_id, period_id, flow)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_policy_binding__scope DO NOTHING
                """)
            .param(bindingId)
            .param(tenant.value())
            .param(actor)
            .param(actor)
            .param(versionId)
            .param(periodId)
            .param(flow)
            .update()
        == 1;
  }

  private static PackVersionRow version(ResultSet rs, int i) throws SQLException {
    byte[] hash = rs.getBytes("content_hash");
    return new PackVersionRow(
        rs.getObject("pack_id", UUID.class),
        rs.getString("code"),
        rs.getString("pack_flow"),
        rs.getObject("version_id", UUID.class),
        rs.getString("version"),
        PolicyVersionStatus.valueOf(rs.getString("status")),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("tu_ky"),
        rs.getString("den_ky"),
        hash == null ? null : HexFormat.of().formatHex(hash),
        rs.getString("bundle_object_key"));
  }
}
