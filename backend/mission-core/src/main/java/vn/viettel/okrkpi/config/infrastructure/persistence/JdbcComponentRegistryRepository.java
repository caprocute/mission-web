package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.ComponentRegistryRepository;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.kernel.Ids;

/** Kho {@code config.component_registry} bằng JDBC (CORE-08-09); bảng không có tenant. */
public class JdbcComponentRegistryRepository implements ComponentRegistryRepository {

  private static final String COLUMNS =
      "component_key, registry_version, props_schema::text AS props_schema,"
          + " allowed_classification, status, container, since, mobile";

  private final JdbcClient jdbc;

  public JdbcComponentRegistryRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<String> versions() {
    return jdbc.sql("SELECT DISTINCT registry_version FROM config.component_registry")
        .query(String.class)
        .list();
  }

  @Override
  public List<UiComponent> list(
      String version, UiComponent.Status status, String afterKey, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.component_registry WHERE registry_version = ?"
                + " AND (?::varchar IS NULL OR status = ?) AND (?::varchar IS NULL OR component_key > ?)"
                + " ORDER BY component_key LIMIT ?")
        .param(version)
        .param(status == null ? null : status.name())
        .param(status == null ? null : status.name())
        .param(afterKey)
        .param(afterKey)
        .param(limit)
        .query(mapper())
        .list();
  }

  @Override
  public List<UiComponent> all(String version) {
    return list(version, null, null, 10_000);
  }

  @Override
  public void insertVersion(List<UiComponent> components) {
    for (UiComponent c : components) {
      jdbc.sql(
              "INSERT INTO config.component_registry (id, registry_version, component_key,"
                  + " props_schema, allowed_classification, status, container, since, mobile)"
                  + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT (registry_version, component_key) DO NOTHING")
          .param(Ids.newUuidV7())
          .param(c.registryVersion())
          .param(c.componentKey())
          .param(c.propsSchemaJson())
          .param(c.allowedClassification())
          .param(c.status().name())
          .param(c.container())
          .param(c.since())
          .param(c.mobile())
          .update();
    }
  }

  private static RowMapper<UiComponent> mapper() {
    return (rs, n) ->
        new UiComponent(
            rs.getString("component_key"),
            rs.getString("registry_version"),
            rs.getString("props_schema"),
            rs.getString("allowed_classification"),
            UiComponent.Status.valueOf(rs.getString("status")),
            rs.getBoolean("container"),
            rs.getString("since"),
            rs.getBoolean("mobile"),
            null);
  }
}
