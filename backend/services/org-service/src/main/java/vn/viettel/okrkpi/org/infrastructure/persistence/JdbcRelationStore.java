package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.port.RelationStore;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UnitRelationVersion;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Adapter {@code org.org_unit_relation}. */
@Repository
public class JdbcRelationStore implements RelationStore {

  private static final String COLS = "r.version_id, r.id, r.tree, r.parent_unit_id, r.child_unit_id, "
      + "r.relation_type, " + Sql.axes("r");

  private final JdbcClient jdbc;
  private final BitemporalWriter writer;
  private final Clock clock;

  public JdbcRelationStore(JdbcClient jdbc, BitemporalWriter writer, Clock clock) {
    this.jdbc = jdbc;
    this.writer = writer;
    this.clock = clock;
  }

  @Override
  public Optional<UnitRelationVersion> parentAt(UUID child, TreeKind tree, TemporalQuery query) {
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit_relation r WHERE r.tenant_id = :tenant "
            + "AND r.tree = :tree AND r.child_unit_id = :child AND r.relation_type = 'TRUC_THUOC' "
            + "AND r.valid_period @> CAST(:d AS date) AND " + Sql.recorded("r", query) + " LIMIT 1")
        .param("tenant", Sql.tenant()).param("tree", tree.name()).param("child", child)
        .param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query(JdbcRelationStore::map).optional();
  }

  @Override
  public Set<TreeKind> parentTreesAt(UUID child, TemporalQuery query) {
    Set<TreeKind> trees = EnumSet.noneOf(TreeKind.class);
    jdbc.sql("SELECT DISTINCT r.tree FROM org.org_unit_relation r WHERE r.tenant_id = :tenant "
            + "AND r.child_unit_id = :child AND r.relation_type = 'TRUC_THUOC' "
            + "AND r.valid_period @> CAST(:d AS date) AND " + Sql.recorded("r", query))
        .param("tenant", Sql.tenant()).param("child", child).param("d", query.asOf())
        .param("rec", query.recordedAsOf())
        .query((rs, n) -> trees.add(TreeKind.valueOf(rs.getString(1)))).list();
    return trees;
  }

  @Override
  public List<UnitRelationVersion> parentHistory(UUID child) {
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit_relation r WHERE r.tenant_id = :tenant "
            + "AND r.child_unit_id = :child AND r.relation_type = 'TRUC_THUOC' ORDER BY r.recorded_from, r.valid_from")
        .param("tenant", Sql.tenant()).param("child", child)
        .query(JdbcRelationStore::map).list();
  }

  @Override
  public List<UnitRelationVersion> currentTouchingForUpdate(Collection<UUID> unitIds) {
    if (unitIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLS + " FROM org.org_unit_relation r WHERE r.tenant_id = :tenant "
            + "AND r.recorded_to = 'infinity' AND (r.parent_unit_id = ANY(CAST(:ids AS uuid[])) "
            + "OR r.child_unit_id = ANY(CAST(:ids AS uuid[]))) ORDER BY r.id, r.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(unitIds))
        .query(JdbcRelationStore::map).list();
  }

  /**
   * Đi xuống từ {@code child} trên phiên bản hiện hành, mang theo phần giao của khoảng hiệu lực dọc đường đi để chỉ
   * coi là vòng khi các quan hệ cùng hiệu lực tại một thời điểm trong {@code [from, to)}.
   */
  @Override
  public boolean wouldCreateCycle(TreeKind tree, UUID child, UUID newParent, LocalDate from, LocalDate to) {
    return Boolean.TRUE.equals(jdbc.sql("""
            WITH RECURSIVE d(id, p) AS (
              SELECT r.child_unit_id, r.valid_period * daterange(CAST(:f AS date), CAST(:t AS date), '[)')
                FROM org.org_unit_relation r
               WHERE r.tenant_id = :tenant AND r.tree = :tree AND r.parent_unit_id = :child
                 AND r.relation_type = 'TRUC_THUOC' AND r.recorded_to = 'infinity'
                 AND r.valid_period && daterange(CAST(:f AS date), CAST(:t AS date), '[)')
              UNION
              SELECT r.child_unit_id, r.valid_period * d.p
                FROM org.org_unit_relation r JOIN d ON r.parent_unit_id = d.id
               WHERE r.tenant_id = :tenant AND r.tree = :tree AND r.relation_type = 'TRUC_THUOC'
                 AND r.recorded_to = 'infinity' AND r.valid_period && d.p
            )
            SELECT EXISTS (SELECT 1 FROM d WHERE d.id = :parent)
            """)
        .param("tenant", Sql.tenant()).param("tree", tree.name()).param("child", child).param("parent", newParent)
        .param("f", from).param("t", to)
        .query(Boolean.class).single());
  }

  @Override
  public boolean hasChildrenFrom(UUID parent, LocalDate from) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM org.org_unit_relation r "
            + "WHERE r.tenant_id = :tenant AND r.parent_unit_id = :parent AND r.relation_type = 'TRUC_THUOC' "
            + "AND r.recorded_to = 'infinity' AND r.valid_period && daterange(CAST(:f AS date), NULL, '[)'))")
        .param("tenant", Sql.tenant()).param("parent", parent).param("f", from)
        .query(Boolean.class).single());
  }

  @Override
  public void apply(VersionPlan<UnitRelationVersion> plan, UUID changeOrderId, Actor actor) {
    writer.close("org_unit_relation", plan.toClose(), actor);
    Map<UUID, Integer> next = writer.nextVersions("org_unit_relation",
        plan.toInsert().stream().map(UnitRelationVersion::id).distinct().toList());
    for (UnitRelationVersion v : plan.toInsert()) {
      // Phiên bản chèn trong giao dịch của một quyết định thuộc quyết định đó; không có quyết định thì giữ căn cứ cũ.
      UUID co = changeOrderId != null ? changeOrderId : v.changeOrderId();
      jdbc.sql("INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id, "
              + "relation_type, change_order_id, valid_from, valid_to, created_by, updated_by, row_version) "
              + "VALUES (:vid, :id, :tenant, :tree, :parent, :child, :type, :co, :f, "
              + "coalesce(CAST(:t AS date), 'infinity'::date), :actor, :actor, :rv)")
          .param("vid", UuidV7.next(clock)).param("id", v.id()).param("tenant", Sql.tenant())
          .param("tree", v.tree().name()).param("parent", v.parentUnitId()).param("child", v.childUnitId())
          .param("type", v.relationType()).param("co", co).param("f", v.validPeriod().from())
          .param("t", v.validPeriod().to()).param("actor", actor.actorId()).param("rv", next.get(v.id()))
          .update();
    }
  }

  static UnitRelationVersion map(ResultSet rs, int n) throws SQLException {
    return new UnitRelationVersion(Sql.uuid(rs, "version_id"), Sql.uuid(rs, "id"), TreeKind.valueOf(rs.getString("tree")),
        Sql.uuid(rs, "parent_unit_id"), Sql.uuid(rs, "child_unit_id"), rs.getString("relation_type"),
        Sql.uuid(rs, "change_order_id"), Sql.period(rs), Sql.ts(rs, "recorded_from"), Sql.ts(rs, "recorded_to"),
        rs.getInt("row_version"));
  }
}
