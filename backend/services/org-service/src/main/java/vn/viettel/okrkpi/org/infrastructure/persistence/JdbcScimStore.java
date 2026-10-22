package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.ScimStore;

/** DV-04-06 · {@code org.scim_resource}, {@code org.scim_sync_log}. */
@Repository
public class JdbcScimStore implements ScimStore {

  private static final String COLUMNS = "r.id, r.source_system, r.resource_type, r.external_id, r.internal_id, "
      + "r.user_name, r.display_name, r.title_code, r.active, r.deleted_at, r.created_at, r.updated_at, r.row_version";

  /** Thuộc tính lọc được, ánh xạ sang cột (không ghép chuỗi từ đầu vào). */
  private static final Map<String, String> FILTER_COLUMNS = Map.of("username", "r.user_name", "externalid",
      "r.external_id", "displayname", "r.display_name");

  private final JdbcClient jdbc;

  public JdbcScimStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Resource> find(String source, String type, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.scim_resource r WHERE r.tenant_id = :tenant "
            + "AND r.source_system = :source AND r.resource_type = :type AND r.id = :id")
        .param("tenant", Sql.tenant()).param("source", source).param("type", type).param("id", id)
        .query(JdbcScimStore::map).optional();
  }

  @Override
  public Optional<Resource> findByExternalId(String source, String type, String externalId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.scim_resource r WHERE r.tenant_id = :tenant "
            + "AND r.source_system = :source AND r.resource_type = :type AND r.external_id = :ext")
        .param("tenant", Sql.tenant()).param("source", source).param("type", type).param("ext", externalId)
        .query(JdbcScimStore::map).optional();
  }

  @Override
  public List<Resource> list(String source, String type, String attribute, String value, int offset, int limit) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.scim_resource r WHERE " + where(attribute)
            + " ORDER BY r.id LIMIT :limit OFFSET :offset")
        .param("tenant", Sql.tenant()).param("source", source).param("type", type).param("value", value)
        .param("limit", limit).param("offset", offset).query(JdbcScimStore::map).list();
  }

  @Override
  public int count(String source, String type, String attribute, String value) {
    return jdbc.sql("SELECT count(*) FROM org.scim_resource r WHERE " + where(attribute))
        .param("tenant", Sql.tenant()).param("source", source).param("type", type).param("value", value)
        .query(Integer.class).single();
  }

  private static String where(String attribute) {
    String base = "r.tenant_id = :tenant AND r.source_system = :source AND r.resource_type = :type "
        + "AND r.deleted_at IS NULL";
    if (attribute == null) {
      return base + " AND CAST(:value AS text) IS NULL";
    }
    String column = FILTER_COLUMNS.get(attribute.toLowerCase(java.util.Locale.ROOT));
    if (column == null) {
      throw new IllegalArgumentException("Thuộc tính lọc không hỗ trợ");
    }
    return base + " AND " + column + " = :value";
  }

  @Override
  public void insert(Resource r, Actor actor) {
    jdbc.sql("INSERT INTO org.scim_resource (id, tenant_id, source_system, resource_type, external_id, internal_id, "
            + "user_name, display_name, title_code, active, created_by, updated_by) VALUES (:id, :tenant, :source, "
            + ":type, :ext, :internal, :userName, :displayName, :title, :active, :actor, :actor)")
        .param("id", r.id()).param("tenant", Sql.tenant()).param("source", r.source()).param("type", r.type())
        .param("ext", r.externalId()).param("internal", r.internalId()).param("userName", r.userName())
        .param("displayName", r.displayName()).param("title", r.titleCode()).param("active", r.active())
        .param("actor", actor.actorId())
        .update();
  }

  @Override
  public int update(Resource r, Actor actor) {
    return jdbc.sql("UPDATE org.scim_resource SET external_id = :ext, user_name = :userName, "
            + "display_name = :displayName, title_code = :title, active = :active, "
            + "deleted_at = CASE WHEN :deleted THEN coalesce(deleted_at, now()) END, updated_at = now(), "
            + "updated_by = :actor, row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id "
            + "RETURNING row_version")
        .param("ext", r.externalId()).param("userName", r.userName()).param("displayName", r.displayName())
        .param("title", r.titleCode()).param("active", r.active()).param("deleted", r.deleted())
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", r.id())
        .query(Integer.class).single();
  }

  @Override
  public List<UUID> members(String source, UUID unitId, String branch, LocalDate day) {
    return jdbc.sql("SELECT DISTINCT r.id FROM org.scim_resource r JOIN org.assignment a ON a.tenant_id = r.tenant_id "
            + "AND a.person_id = r.internal_id WHERE r.tenant_id = :tenant AND r.source_system = :source "
            + "AND r.resource_type = 'User' AND r.deleted_at IS NULL AND a.org_unit_id = :unit AND a.kind = 'CHINH' "
            + "AND a.branch = :branch AND a.recorded_to = 'infinity' AND a.valid_period @> CAST(:d AS date) "
            + "ORDER BY r.id")
        .param("tenant", Sql.tenant()).param("source", source).param("unit", unitId).param("branch", branch)
        .param("d", day).query(UUID.class).list();
  }

  @Override
  public Optional<UUID> jobTitleIdByCode(String code) {
    return jdbc.sql("SELECT id FROM org.job_title WHERE tenant_id = :tenant AND code = :code")
        .param("tenant", Sql.tenant()).param("code", code).query(UUID.class).optional();
  }

  @Override
  public Optional<byte[]> lastSuccessfulHash(String source, String type, String externalId) {
    return jdbc.sql("SELECT status, request_hash FROM org.scim_sync_log WHERE tenant_id = :tenant "
            + "AND source_system = :source AND resource_type = :type AND external_id = :ext "
            + "ORDER BY received_at DESC, id DESC LIMIT 1")
        .param("tenant", Sql.tenant()).param("source", source).param("type", type).param("ext", externalId)
        .query((rs, n) -> new Object[] {rs.getString(1), rs.getBytes(2)}).optional()
        .filter(row -> !"LOI".equals(row[0])).map(row -> (byte[]) row[1]);
  }

  @Override
  public void log(LogEntry e, Actor actor) {
    jdbc.sql("INSERT INTO org.scim_sync_log (id, tenant_id, source_system, operation, resource_type, external_id, "
            + "internal_id, request_hash, status, error_detail, received_at, created_by, updated_by) VALUES (:id, "
            + ":tenant, :source, :op, :type, :ext, :internal, :hash, :status, :error, clock_timestamp(), :actor, "
            + ":actor)")
        .param("id", e.id()).param("tenant", Sql.tenant()).param("source", e.source()).param("op", e.operation())
        .param("type", e.type()).param("ext", e.externalId()).param("internal", e.internalId())
        .param("hash", e.requestHash()).param("status", e.status()).param("error", e.errorDetail())
        .param("actor", actor.actorId())
        .update();
  }

  private static Resource map(ResultSet rs, int n) throws SQLException {
    return new Resource(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9),
        rs.getObject(10, OffsetDateTime.class) != null, rs.getObject(11, OffsetDateTime.class),
        rs.getObject(12, OffsetDateTime.class), rs.getInt(13));
  }
}
