package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code integ.mapping_xref}: EXCLUDE theo {@code valid_period} chặn ánh xạ chồng thời gian; {@code
 * ON CONFLICT DO NOTHING} biến va chạm giữa hai luồng thành kết quả {@code false}.
 */
public class JdbcMappingXrefs implements MappingXrefs {

  private static final String COLUMNS = "id, external_id, internal_id, valid_from";

  private final JdbcClient jdbc;

  public JdbcMappingXrefs(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Mapping> byExternal(
      TenantId tenant, UUID connectorId, EntityType type, String externalId, LocalDate on) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.mapping_xref WHERE tenant_id = ? AND connector_id = ?"
                + " AND entity_type = ? AND external_id = ? AND valid_period @> ?::date")
        .params(tenant.value(), connectorId, type.name(), externalId, Date.valueOf(on))
        .query(JdbcMappingXrefs::map)
        .optional();
  }

  @Override
  public Optional<Mapping> byInternal(
      TenantId tenant, UUID connectorId, EntityType type, UUID internalId, LocalDate on) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.mapping_xref WHERE tenant_id = ? AND connector_id = ?"
                + " AND entity_type = ? AND internal_id = ? AND valid_period @> ?::date"
                + " ORDER BY valid_from DESC LIMIT 1")
        .params(tenant.value(), connectorId, type.name(), internalId, Date.valueOf(on))
        .query(JdbcMappingXrefs::map)
        .optional();
  }

  @Override
  public List<Located> byInternalAnyConnector(
      TenantId tenant, EntityType type, UUID internalId, LocalDate on) {
    return jdbc.sql(
            "SELECT connector_id, "
                + COLUMNS
                + " FROM integ.mapping_xref WHERE tenant_id = ? AND entity_type = ?"
                + " AND internal_id = ? AND valid_period @> ?::date"
                + " ORDER BY valid_from DESC, id DESC LIMIT 20")
        .params(tenant.value(), type.name(), internalId, Date.valueOf(on))
        .query(
            (rs, row) ->
                new Located(
                    rs.getObject(1, UUID.class),
                    new Mapping(
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getDate(5).toLocalDate())))
        .list();
  }

  @Override
  public boolean insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      EntityType type,
      String externalId,
      UUID internalId,
      LocalDate from,
      String note) {
    return jdbc.sql(
                "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type,"
                    + " external_id, internal_id, valid_from, source_note)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(
                id,
                tenant.value(),
                connectorId,
                type.name(),
                externalId,
                internalId,
                Date.valueOf(from),
                note)
            .update()
        == 1;
  }

  @Override
  public boolean close(TenantId tenant, UUID mappingId, LocalDate to) {
    return jdbc.sql(
                "UPDATE integ.mapping_xref SET valid_to = ? WHERE tenant_id = ? AND id = ?"
                    + " AND valid_to IS NULL AND valid_from < ?")
            .params(Date.valueOf(to), tenant.value(), mappingId, Date.valueOf(to))
            .update()
        == 1;
  }

  private static Mapping map(ResultSet rs, int row) throws SQLException {
    return new Mapping(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getObject(3, UUID.class),
        rs.getDate(4).toLocalDate());
  }
}
