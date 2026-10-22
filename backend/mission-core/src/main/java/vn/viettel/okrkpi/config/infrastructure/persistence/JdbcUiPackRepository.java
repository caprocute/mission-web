package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.UiPackRepository;
import vn.viettel.okrkpi.config.domain.UiPack;
import vn.viettel.okrkpi.config.domain.UiPackContent;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.ui_pack}, {@code config.ui_pack_version} bằng JDBC (CORE-08-08). */
public class JdbcUiPackRepository implements UiPackRepository {

  private static final String PACK_COLUMNS = "id, code, name, current_version_id, row_version";
  private static final String VERSION_COLUMNS =
      "id, ui_pack_id, version, status, tokens::text AS tokens, layouts::text AS layouts,"
          + " assets_json::text AS assets, component_registry_version, policy_pack_version_id,"
          + " elder_mode, few_steps, bundle_sha256, bundle_object_key, signature, published_at, created_by, created_at,"
          + " updated_by, updated_at, row_version";

  private final JdbcClient jdbc;

  public JdbcUiPackRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UiPack> listPacks(TenantId tenantId, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT "
                + PACK_COLUMNS
                + " FROM config.ui_pack WHERE tenant_id = ? AND (?::uuid IS NULL OR id > ?)"
                + " ORDER BY id LIMIT ?")
        .param(tenantId.value())
        .param(afterId)
        .param(afterId)
        .param(limit)
        .query(packMapper())
        .list();
  }

  @Override
  public Optional<UiPack> findPack(TenantId tenantId, UUID packId) {
    return jdbc.sql(
            "SELECT " + PACK_COLUMNS + " FROM config.ui_pack WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(packId)
        .query(packMapper())
        .optional();
  }

  @Override
  public void insertPack(TenantId tenantId, UiPack pack, UUID actorId) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.sql(
            """
            INSERT INTO config.ui_pack
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version, code, name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(pack.id())
        .param(tenantId.value())
        .param(now)
        .param(actorId)
        .param(now)
        .param(actorId)
        .param(pack.rowVersion())
        .param(pack.code())
        .param(pack.name())
        .update();
  }

  @Override
  public List<UiPackVersion> listVersions(
      TenantId tenantId, UUID packId, UiPackVersionStatus status, UUID beforeId, int limit) {
    String s = status == null ? null : status.name();
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM config.ui_pack_version WHERE tenant_id = ? AND ui_pack_id = ?"
                + " AND (?::varchar IS NULL OR status = ?) AND (?::uuid IS NULL OR id < ?)"
                + " ORDER BY id DESC LIMIT ?")
        .param(tenantId.value())
        .param(packId)
        .param(s)
        .param(s)
        .param(beforeId)
        .param(beforeId)
        .param(limit)
        .query(versionMapper())
        .list();
  }

  @Override
  public Optional<UiPackVersion> findVersion(TenantId tenantId, UUID packId, String version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM config.ui_pack_version WHERE tenant_id = ? AND ui_pack_id = ?"
                + " AND version = ?")
        .param(tenantId.value())
        .param(packId)
        .param(version)
        .query(versionMapper())
        .optional();
  }

  @Override
  public Optional<UiPackVersion> findVersionById(TenantId tenantId, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM config.ui_pack_version WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(versionId)
        .query(versionMapper())
        .optional();
  }

  @Override
  public Optional<String> findInProgressVersion(TenantId tenantId, UUID packId) {
    return jdbc.sql(
            """
            SELECT version FROM config.ui_pack_version
             WHERE tenant_id = ? AND ui_pack_id = ? AND status IN ('NHAP', 'DANG_KIEM_TRA', 'CHO_DUYET')
             ORDER BY id DESC LIMIT 1
            """)
        .param(tenantId.value())
        .param(packId)
        .query(String.class)
        .optional();
  }

  @Override
  public boolean insertVersion(TenantId tenantId, UiPackVersion v) {
    UiPackContent c = v.content();
    try {
      return jdbc.sql(
                  """
                  INSERT INTO config.ui_pack_version
                    (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                     ui_pack_id, version, tokens, layouts, assets_json, component_registry_version,
                     policy_pack_version_id, status, elder_mode, few_steps)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                  ON CONFLICT (ui_pack_id, version) DO NOTHING
                  """)
              .param(v.id())
              .param(tenantId.value())
              .param(Timestamp.from(v.createdAt()))
              .param(v.createdBy())
              .param(Timestamp.from(v.updatedAt()))
              .param(v.updatedBy())
              .param(v.rowVersion())
              .param(v.uiPackId())
              .param(v.version())
              .param(c.tokensJson())
              .param(c.layoutsJson())
              .param(c.assetsJson())
              .param(c.componentRegistryVersion())
              .param(c.policyPackVersionId())
              .param(v.status().name())
              .param(c.elderMode())
              .param(c.fewSteps())
              .update()
          == 1;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public boolean updateVersion(TenantId tenantId, UiPackVersion v, int expectedVersion) {
    UiPackContent c = v.content();
    return jdbc.sql(
                """
                UPDATE config.ui_pack_version
                   SET status = ?, tokens = ?::jsonb, layouts = ?::jsonb, assets_json = ?::jsonb,
                       component_registry_version = ?, policy_pack_version_id = ?, elder_mode = ?,
                       few_steps = ?, bundle_sha256 = ?, bundle_object_key = ?,
                       signature = ?, published_at = ?, updated_at = ?, updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(v.status().name())
            .param(c.tokensJson())
            .param(c.layoutsJson())
            .param(c.assetsJson())
            .param(c.componentRegistryVersion())
            .param(c.policyPackVersionId())
            .param(c.elderMode())
            .param(c.fewSteps())
            .param(v.bundleSha256() == null ? null : HexFormat.of().parseHex(v.bundleSha256()))
            .param(v.bundleObjectKey())
            .param(v.signature() == null ? null : Base64.getDecoder().decode(v.signature()))
            .param(v.publishedAt() == null ? null : Timestamp.from(v.publishedAt()))
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenantId.value())
            .param(v.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public Optional<UiPack> findCurrentPack(TenantId tenantId) {
    return jdbc.sql(
            "SELECT p.id, p.code, p.name, p.current_version_id, p.row_version"
                + " FROM config.ui_pack p JOIN config.ui_pack_version v"
                + " ON v.tenant_id = p.tenant_id AND v.id = p.current_version_id"
                + " WHERE p.tenant_id = ? ORDER BY v.published_at DESC NULLS LAST, p.id LIMIT 1")
        .param(tenantId.value())
        .query(packMapper())
        .optional();
  }

  @Override
  public boolean setCurrentVersion(
      TenantId tenantId, UiPack pack, UUID versionId, UUID actorId, Instant at) {
    return jdbc.sql(
                """
                UPDATE config.ui_pack
                   SET current_version_id = ?, updated_at = ?, updated_by = ?,
                       row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(versionId)
            .param(Timestamp.from(at))
            .param(actorId)
            .param(tenantId.value())
            .param(pack.id())
            .param(pack.rowVersion())
            .update()
        == 1;
  }

  private static RowMapper<UiPack> packMapper() {
    return (ResultSet rs, int row) ->
        new UiPack(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("current_version_id", UUID.class),
            rs.getInt("row_version"));
  }

  private static RowMapper<UiPackVersion> versionMapper() {
    return (ResultSet rs, int row) -> {
      byte[] sha = rs.getBytes("bundle_sha256");
      byte[] signature = rs.getBytes("signature");
      return new UiPackVersion(
          rs.getObject("id", UUID.class),
          rs.getObject("ui_pack_id", UUID.class),
          rs.getString("version"),
          UiPackVersionStatus.valueOf(rs.getString("status")),
          new UiPackContent(
              rs.getString("tokens"),
              rs.getString("layouts"),
              rs.getString("assets"),
              rs.getString("component_registry_version"),
              rs.getObject("policy_pack_version_id", UUID.class),
              rs.getBoolean("elder_mode"),
              rs.getBoolean("few_steps")),
          sha == null ? null : HexFormat.of().formatHex(sha),
          rs.getString("bundle_object_key"),
          signature == null ? null : Base64.getEncoder().encodeToString(signature),
          instant(rs, "published_at"),
          rs.getObject("created_by", UUID.class),
          instant(rs, "created_at"),
          rs.getObject("updated_by", UUID.class),
          instant(rs, "updated_at"),
          rs.getInt("row_version"));
    };
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
