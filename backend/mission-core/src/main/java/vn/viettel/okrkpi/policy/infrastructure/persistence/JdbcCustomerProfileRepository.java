package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.CustomerProfileRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.domain.CustomerProfile;
import vn.viettel.okrkpi.policy.domain.CustomerProfileVersion;
import vn.viettel.okrkpi.policy.domain.ParamConstraint;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link CustomerProfileRepository} trên {@code policyDataSource} (vai trò {@code svc_policy}). */
public class JdbcCustomerProfileRepository implements CustomerProfileRepository {

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private static final String PROFILE_COLUMNS =
      "id, code, name, is_simulated, legal_basis::text AS legal_basis,"
          + " tenant_tags::text AS tenant_tags, created_at, created_by, row_version";

  private static final String VERSION_COLUMNS =
      "id, customer_profile_id, version, status, manifest::text AS manifest, content_hash,"
          + " signature IS NOT NULL AS signed, approved_by, approved_at, signed_at, published_at,"
          + " change_note, created_at, created_by, updated_at, updated_by, row_version";

  private static final String CONSTRAINT_COLUMNS =
      "id, param_key, can_toggle, can_change_levels, min_value, max_value, tighten_direction,"
          + " allowed_scopes::text AS allowed_scopes";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcCustomerProfileRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<CustomerProfile> listProfiles(
      TenantId tenant, String query, String afterCode, int limit) {
    String like = query == null || query.isBlank() ? null : "%" + query.strip() + "%";
    return jdbc.sql(
            "SELECT "
                + PROFILE_COLUMNS
                + " FROM policy.customer_profile WHERE tenant_id = ?"
                + " AND (?::varchar IS NULL OR code ILIKE ? OR name ILIKE ?)"
                + " AND (?::varchar IS NULL OR code > ?)"
                + " ORDER BY code LIMIT ?")
        .param(tenant.value())
        .param(like)
        .param(like)
        .param(like)
        .param(afterCode)
        .param(afterCode)
        .param(limit)
        .query(this::profile)
        .list();
  }

  @Override
  public Optional<CustomerProfile> findProfile(TenantId tenant, UUID profileId) {
    return jdbc.sql(
            "SELECT "
                + PROFILE_COLUMNS
                + " FROM policy.customer_profile WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(profileId)
        .query(this::profile)
        .optional();
  }

  @Override
  public boolean insertProfile(TenantId tenant, CustomerProfile p) {
    return jdbc.sql(
                """
                INSERT INTO policy.customer_profile
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   code, name, is_simulated, legal_basis, tenant_tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """)
            .param(p.id())
            .param(tenant.value())
            .param(Timestamp.from(p.createdAt()))
            .param(p.createdBy())
            .param(Timestamp.from(p.createdAt()))
            .param(p.createdBy())
            .param(p.rowVersion())
            .param(p.code())
            .param(p.name())
            .param(p.simulated())
            .param(write(p.legalBasis()))
            .param(write(p.tenantTags()))
            .update()
        == 1;
  }

  @Override
  public Map<UUID, List<CustomerProfileVersion>> recentVersions(
      TenantId tenant, Collection<UUID> profileIds, int perProfile) {
    Map<UUID, List<CustomerProfileVersion>> result = new LinkedHashMap<>();
    if (profileIds.isEmpty()) {
      return result;
    }
    jdbc.sql(
            "SELECT * FROM (SELECT "
                + VERSION_COLUMNS
                + ", row_number() OVER (PARTITION BY customer_profile_id ORDER BY id DESC) AS rn"
                + " FROM policy.customer_profile_version WHERE tenant_id = ?"
                + " AND customer_profile_id = ANY(?::uuid[]))"
                + " v WHERE rn <= ? ORDER BY customer_profile_id, id DESC")
        .param(tenant.value())
        .param(profileIds.stream().map(UUID::toString).toArray(String[]::new))
        .param(perProfile)
        .query(JdbcCustomerProfileRepository::version)
        .list()
        .forEach(v -> result.computeIfAbsent(v.profileId(), k -> new ArrayList<>()).add(v));
    return result;
  }

  @Override
  public List<CustomerProfileVersion> listVersions(
      TenantId tenant, UUID profileId, PolicyVersionStatus status, UUID afterId, int limit) {
    String statusName = status == null ? null : status.name();
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.customer_profile_version WHERE tenant_id = ?"
                + " AND customer_profile_id = ?"
                + " AND (?::varchar IS NULL OR status = ?)"
                + " AND (?::uuid IS NULL OR id < ?)"
                + " ORDER BY id DESC LIMIT ?")
        .param(tenant.value())
        .param(profileId)
        .param(statusName)
        .param(statusName)
        .param(afterId)
        .param(afterId)
        .param(limit)
        .query(JdbcCustomerProfileRepository::version)
        .list();
  }

  @Override
  public Optional<CustomerProfileVersion> findVersion(
      TenantId tenant, UUID profileId, String version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.customer_profile_version WHERE tenant_id = ?"
                + " AND customer_profile_id = ? AND version = ?")
        .param(tenant.value())
        .param(profileId)
        .param(version)
        .query(JdbcCustomerProfileRepository::version)
        .optional();
  }

  @Override
  public Optional<CustomerProfileVersion> findVersionById(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.customer_profile_version WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcCustomerProfileRepository::version)
        .optional();
  }

  @Override
  public List<String> versionNumbers(TenantId tenant, UUID profileId) {
    return jdbc.sql(
            "SELECT version FROM policy.customer_profile_version WHERE tenant_id = ?"
                + " AND customer_profile_id = ?")
        .param(tenant.value())
        .param(profileId)
        .query(String.class)
        .list();
  }

  @Override
  public boolean insertVersion(TenantId tenant, CustomerProfileVersion v) {
    return jdbc.sql(
                """
                INSERT INTO policy.customer_profile_version
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   customer_profile_id, version, status, manifest, change_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (customer_profile_id, version) DO NOTHING
                """)
            .param(v.id())
            .param(tenant.value())
            .param(Timestamp.from(v.createdAt()))
            .param(v.createdBy())
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(v.profileId())
            .param(v.version())
            .param(v.status().name())
            .param(v.manifestJson())
            .param(v.changeNote())
            .update()
        == 1;
  }

  @Override
  public boolean storeVersion(TenantId tenant, CustomerProfileVersion v, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.customer_profile_version
                   SET status = ?, manifest = ?::jsonb, content_hash = ?, change_note = ?,
                       updated_at = ?, updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(v.status().name())
            .param(v.manifestJson())
            .param(v.contentHash() == null ? null : HexFormat.of().parseHex(v.contentHash()))
            .param(v.changeNote())
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenant.value())
            .param(v.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public boolean storeApproval(
      TenantId tenant, CustomerProfileVersion v, SignedHash signed, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.customer_profile_version
                   SET status = 'DA_PHAT_HANH', signature = ?, signer_cert_ref = ?, signed_at = ?,
                       tsa_token = ?, approved_by = ?, approved_at = ?, published_at = ?,
                       updated_at = ?, updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                   AND status = 'CHO_PHE_DUYET' AND signature IS NULL AND created_by <> ?
                """)
            .param(signed.signature())
            .param(signed.certificateRef())
            .param(Timestamp.from(signed.signedAt()))
            .param(signed.tsaToken())
            .param(v.approvedBy())
            .param(Timestamp.from(v.approvedAt()))
            .param(Timestamp.from(v.publishedAt()))
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenant.value())
            .param(v.id())
            .param(expected)
            .param(v.approvedBy())
            .update()
        == 1;
  }

  @Override
  public List<ParamConstraint> constraints(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + CONSTRAINT_COLUMNS
                + " FROM policy.param_constraint WHERE tenant_id = ?"
                + " AND customer_profile_version_id = ? ORDER BY param_key")
        .param(tenant.value())
        .param(versionId)
        .query(this::constraint)
        .list();
  }

  @Override
  public int countConstraints(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT count(*) FROM policy.param_constraint WHERE tenant_id = ?"
                + " AND customer_profile_version_id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(Integer.class)
        .single();
  }

  @Override
  public void replaceConstraints(
      TenantId tenant, UUID versionId, List<ParamConstraint> constraints, UUID actor) {
    jdbc.sql(
            "DELETE FROM policy.param_constraint WHERE tenant_id = ?"
                + " AND customer_profile_version_id = ?")
        .param(tenant.value())
        .param(versionId)
        .update();
    for (ParamConstraint c : constraints) {
      jdbc.sql(
              """
              INSERT INTO policy.param_constraint
                (id, tenant_id, created_by, updated_by, customer_profile_version_id, param_key,
                 can_toggle, can_change_levels, min_value, max_value, tighten_direction,
                 allowed_scopes)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
              """)
          .param(c.id() == null ? Ids.newUuidV7() : c.id())
          .param(tenant.value())
          .param(actor)
          .param(actor)
          .param(versionId)
          .param(c.paramKey())
          .param(c.canToggle())
          .param(c.canChangeLevels())
          .param(c.minValue())
          .param(c.maxValue())
          .param(c.tightenDirection())
          .param(write(c.allowedScopes()))
          .update();
    }
  }

  @Override
  public List<CustomerProfileVersion> publishedVersions(TenantId tenant) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.customer_profile_version WHERE tenant_id = ?"
                + " AND status = 'DA_PHAT_HANH' ORDER BY published_at DESC, id DESC")
        .param(tenant.value())
        .query(JdbcCustomerProfileRepository::version)
        .list();
  }

  @Override
  public boolean pinBinding(TenantId tenant, UUID bindingId, UUID profileVersionId, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.policy_binding
                   SET customer_profile_version_id = ?, updated_at = now(), updated_by = ?,
                       row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ?
                """)
            .param(profileVersionId)
            .param(actor)
            .param(tenant.value())
            .param(bindingId)
            .update()
        == 1;
  }

  @Override
  public Optional<UUID> pinnedVersion(TenantId tenant, UUID bindingId) {
    return jdbc.sql(
            "SELECT customer_profile_version_id FROM policy.policy_binding"
                + " WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(bindingId)
        .query((rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
        .optional()
        .flatMap(o -> o);
  }

  private CustomerProfile profile(ResultSet rs, int i) throws SQLException {
    return new CustomerProfile(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBoolean("is_simulated"),
        read(rs.getString("legal_basis")),
        read(rs.getString("tenant_tags")),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        rs.getLong("row_version"));
  }

  private static CustomerProfileVersion version(ResultSet rs, int i) throws SQLException {
    byte[] hash = rs.getBytes("content_hash");
    return new CustomerProfileVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("customer_profile_id", UUID.class),
        rs.getString("version"),
        PolicyVersionStatus.valueOf(rs.getString("status")),
        rs.getString("manifest"),
        hash == null ? null : HexFormat.of().formatHex(hash),
        rs.getBoolean("signed"),
        rs.getObject("approved_by", UUID.class),
        instant(rs, "approved_at"),
        instant(rs, "signed_at"),
        instant(rs, "published_at"),
        rs.getString("change_note"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "updated_at"),
        rs.getObject("updated_by", UUID.class),
        rs.getLong("row_version"));
  }

  private ParamConstraint constraint(ResultSet rs, int i) throws SQLException {
    return new ParamConstraint(
        rs.getObject("id", UUID.class),
        rs.getString("param_key"),
        rs.getBoolean("can_toggle"),
        rs.getBoolean("can_change_levels"),
        strip(rs.getBigDecimal("min_value")),
        strip(rs.getBigDecimal("max_value")),
        rs.getString("tighten_direction"),
        read(rs.getString("allowed_scopes")));
  }

  private static java.math.BigDecimal strip(java.math.BigDecimal v) {
    return v == null ? null : v.stripTrailingZeros();
  }

  private String write(List<String> values) {
    try {
      return json.writeValueAsString(values == null ? List.of() : values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được danh sách JSON", e);
    }
  }

  private List<String> read(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    try {
      return json.readValue(text, STRINGS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không đọc được danh sách JSON", e);
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
