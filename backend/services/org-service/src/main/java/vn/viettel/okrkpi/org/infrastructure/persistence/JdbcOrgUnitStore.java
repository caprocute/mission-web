package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.TextFold;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.domain.OrgUnitContent;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Adapter {@code org.org_unit} bằng JDBC (truy vấn SQL chuẩn cộng toán tử khoảng của PostgreSQL theo ADR-02). */
@Repository
public class JdbcOrgUnitStore implements OrgUnitStore {

  private static final String COLS = "u.version_id, u.id, u.code, u.name, u.short_name, u.unit_type, "
      + "u.subject_group_id, u.is_collective_subject, u.flows, u.level, u.created_by, " + Sql.axes("u");

  private final JdbcClient jdbc;
  private final BitemporalWriter writer;
  private final java.time.Clock clock;

  public JdbcOrgUnitStore(JdbcClient jdbc, BitemporalWriter writer, java.time.Clock clock) {
    this.jdbc = jdbc;
    this.writer = writer;
    this.clock = clock;
  }

  @Override
  public Optional<OrgUnitVersion> findAt(UUID id, TemporalQuery query) {
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit u WHERE u.tenant_id = :tenant AND u.id = :id "
            + "AND u.valid_period @> CAST(:d AS date) AND " + Sql.recorded("u", query))
        .param("tenant", Sql.tenant()).param("id", id).param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query(JdbcOrgUnitStore::map).optional();
  }

  @Override
  public Map<UUID, OrgUnitVersion> findAllAt(Collection<UUID> ids, TemporalQuery query) {
    Map<UUID, OrgUnitVersion> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT " + COLS + " FROM org.org_unit u WHERE u.tenant_id = :tenant "
            + "AND u.id = ANY(CAST(:ids AS uuid[])) AND u.valid_period @> CAST(:d AS date) AND "
            + Sql.recorded("u", query))
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(ids)).param("d", query.asOf())
        .param("rec", query.recordedAsOf())
        .query(JdbcOrgUnitStore::map).list().forEach(v -> out.put(v.id(), v));
    return out;
  }

  @Override
  public List<OrgUnitVersion> currentVersionsForUpdate(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit u WHERE u.tenant_id = :tenant AND u.id = :id "
            + "AND u.recorded_to = 'infinity' ORDER BY u.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("id", id)
        .query(JdbcOrgUnitStore::map).list();
  }

  @Override
  public List<OrgUnitVersion> allVersions(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit u WHERE u.tenant_id = :tenant AND u.id = :id "
            + "ORDER BY u.recorded_from, u.valid_from")
        .param("tenant", Sql.tenant()).param("id", id)
        .query(JdbcOrgUnitStore::map).list();
  }

  @Override
  public Optional<Integer> entityVersion(UUID id, OffsetDateTime recordedAsOf) {
    Integer v = jdbc.sql("SELECT max(row_version) FROM org.org_unit WHERE tenant_id = :tenant AND id = :id "
            + "AND recorded_from <= coalesce(CAST(:rec AS timestamptz), 'infinity'::timestamptz)")
        .param("tenant", Sql.tenant()).param("id", id).param("rec", recordedAsOf)
        .query(Integer.class).single();
    return Optional.ofNullable(v);
  }

  @Override
  public boolean codeTakenByOther(String code, UUID exceptId, LocalDate from, LocalDate to) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM org.org_unit u WHERE u.tenant_id = :tenant "
            + "AND u.code = :code AND u.id <> :id AND u.recorded_to = 'infinity' "
            + "AND u.valid_period && daterange(CAST(:f AS date), CAST(:t AS date), '[)'))")
        .param("tenant", Sql.tenant()).param("code", code).param("id", exceptId).param("f", from).param("t", to)
        .query(Boolean.class).single());
  }

  @Override
  public void lockCode(String code) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
        .param("k", Sql.tenant() + ":org.org_unit.code:" + code)
        .query((rs, n) -> 1).list();
  }

  @Override
  public void apply(VersionPlan<OrgUnitVersion> plan, int rowVersion, UUID changeOrderId, Actor actor) {
    writer.close("org_unit", plan.toClose(), actor);
    for (OrgUnitVersion v : plan.toInsert()) {
      OrgUnitContent c = v.content();
      // Phiên bản chèn trong giao dịch của một quyết định thuộc quyết định đó; không có quyết định thì giữ căn cứ cũ.
      UUID co = changeOrderId != null ? changeOrderId : v.changeOrderId();
      jdbc.sql("INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, short_name, unit_type, "
              + "subject_group_id, is_collective_subject, flows, level, change_order_id, valid_from, valid_to, "
              + "created_by, updated_by, row_version) VALUES (:vid, :id, :tenant, :code, :name, :short, :type, "
              + ":sg, :coll, CAST(:flows AS varchar(12)[]), :level, :co, :f, "
              + "coalesce(CAST(:t AS date), 'infinity'::date), :actor, :actor, :rv)")
          .param("vid", vn.viettel.okrkpi.org.domain.UuidV7.next(clock))
          .param("id", v.id()).param("tenant", Sql.tenant()).param("code", c.code()).param("name", c.name())
          .param("short", c.shortName()).param("type", c.unitType()).param("sg", c.subjectGroupId())
          .param("coll", c.collectiveSubject()).param("flows", Sql.textArray(c.flows())).param("level", c.level())
          .param("co", co).param("f", v.validPeriod().from()).param("t", v.validPeriod().to())
          .param("actor", actor.actorId()).param("rv", rowVersion)
          .update();
    }
  }

  @Override
  public List<UnitSearchRow> search(String text, TemporalQuery query, int limit) {
    String folded = TextFold.fold(text);
    String like = "%" + folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return jdbc.sql("SELECT u.id, u.code, u.name, u.unit_type FROM org.org_unit u WHERE u.tenant_id = :tenant "
            + "AND u.valid_period @> CAST(:d AS date) AND " + Sql.recorded("u", query)
            + " AND (org.fn_fold_vi(u.code) LIKE :like OR org.fn_fold_vi(u.name) LIKE :like) "
            + "ORDER BY (org.fn_fold_vi(u.code) = :exact) DESC, (org.fn_fold_vi(u.name) LIKE :prefix) DESC, "
            + "u.name, u.id LIMIT :limit")
        .param("tenant", Sql.tenant()).param("d", query.asOf()).param("rec", query.recordedAsOf())
        .param("like", like).param("exact", folded).param("prefix", like.substring(1)).param("limit", limit)
        .query((rs, n) -> new UnitSearchRow(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
            rs.getString("unit_type")))
        .list();
  }

  static OrgUnitVersion map(ResultSet rs, int n) throws SQLException {
    OrgUnitContent content = new OrgUnitContent(rs.getString("code"), rs.getString("name"),
        rs.getString("short_name"), rs.getString("unit_type"), Sql.uuid(rs, "subject_group_id"),
        rs.getBoolean("is_collective_subject"), Sql.strings(rs, "flows"), rs.getString("level"));
    return new OrgUnitVersion(Sql.uuid(rs, "version_id"), Sql.uuid(rs, "id"), content,
        Sql.uuid(rs, "change_order_id"), Sql.period(rs), Sql.ts(rs, "recorded_from"), Sql.ts(rs, "recorded_to"),
        Sql.uuid(rs, "created_by"), rs.getInt("row_version"));
  }
}
