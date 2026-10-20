package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort;
import vn.viettel.okrkpi.org.domain.TreeKind;

/** CTE đệ quy trên quan hệ cha con (TL-03 "Mẫu truy vấn"), lọc theo hai trục. */
@Repository
public class JdbcTreeQuery implements TreeQueryPort {

  private static final String REL = "r.tenant_id = :tenant AND r.tree = :tree AND r.relation_type = 'TRUC_THUOC' "
      + "AND r.valid_period @> CAST(:d AS date) AND ";

  private final JdbcClient jdbc;

  public JdbcTreeQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<TreeRow> forest(TreeKind tree, TemporalQuery query, int depth) {
    String sql = "WITH RECURSIVE roots AS ("
        + " SELECT DISTINCT r.parent_unit_id AS id FROM org.org_unit_relation r WHERE " + REL + Sql.recorded("r", query)
        + " AND NOT EXISTS (SELECT 1 FROM org.org_unit_relation p WHERE p.tenant_id = :tenant AND p.tree = :tree"
        + "  AND p.relation_type = 'TRUC_THUOC' AND p.child_unit_id = r.parent_unit_id"
        + "  AND p.valid_period @> CAST(:d AS date) AND " + Sql.recorded("p", query) + ")"
        // Đơn vị chưa có quan hệ trực thuộc nào tại thời điểm hỏi là gốc riêng của cây mặc định theo loại đơn vị
        // (cùng quy tắc TreeKind.defaultFor), để không biến mất khỏi cây.
        + " UNION SELECT u.id FROM org.org_unit u WHERE u.tenant_id = :tenant"
        + "  AND u.valid_period @> CAST(:d AS date) AND " + Sql.recorded("u", query)
        + "  AND (CASE WHEN u.unit_type IN ('TO_CHUC_DANG', 'CHI_BO') THEN 'DANG' ELSE 'CHUYEN_MON' END) = :tree"
        + "  AND NOT EXISTS (SELECT 1 FROM org.org_unit_relation x WHERE x.tenant_id = :tenant"
        + "   AND x.relation_type = 'TRUC_THUOC' AND x.child_unit_id = u.id"
        + "   AND x.valid_period @> CAST(:d AS date) AND " + Sql.recorded("x", query) + ")"
        + "  AND NOT EXISTS (SELECT 1 FROM org.org_unit_relation y WHERE y.tenant_id = :tenant"
        + "   AND y.relation_type = 'TRUC_THUOC' AND y.parent_unit_id = u.id"
        + "   AND y.valid_period @> CAST(:d AS date) AND " + Sql.recorded("y", query) + ")"
        + "), t(id, parent_id, depth) AS ("
        + " SELECT id, CAST(NULL AS uuid), 0 FROM roots"
        + " UNION ALL"
        + " SELECT r.child_unit_id, r.parent_unit_id, t.depth + 1 FROM org.org_unit_relation r"
        + "  JOIN t ON r.parent_unit_id = t.id WHERE " + REL + Sql.recorded("r", query) + " AND t.depth < :depth"
        + ")" + select(query);
    return run(sql, tree, query, depth, null);
  }

  @Override
  public List<TreeRow> subtree(TreeKind tree, UUID root, TemporalQuery query, int depth) {
    String sql = "WITH RECURSIVE t(id, parent_id, depth) AS ("
        + " SELECT r.child_unit_id, r.parent_unit_id, 1 FROM org.org_unit_relation r WHERE " + REL
        + Sql.recorded("r", query) + " AND r.parent_unit_id = :root"
        + " UNION ALL"
        + " SELECT r.child_unit_id, r.parent_unit_id, t.depth + 1 FROM org.org_unit_relation r"
        + "  JOIN t ON r.parent_unit_id = t.id WHERE " + REL + Sql.recorded("r", query) + " AND t.depth < :depth"
        + ")" + select(query);
    return run(sql, tree, query, depth, root);
  }

  @Override
  public List<TreeRow> ancestors(TreeKind tree, UUID unit, TemporalQuery query) {
    String sql = "WITH RECURSIVE up(id, lvl) AS ("
        + " SELECT r.parent_unit_id, 1 FROM org.org_unit_relation r WHERE " + REL + Sql.recorded("r", query)
        + " AND r.child_unit_id = :root"
        + " UNION ALL"
        + " SELECT r.parent_unit_id, up.lvl + 1 FROM org.org_unit_relation r JOIN up ON r.child_unit_id = up.id"
        + "  WHERE " + REL + Sql.recorded("r", query) + " AND up.lvl < 64"
        + ") SELECT up.id, CAST(NULL AS uuid) AS parent_id, up.lvl AS depth, u.code, u.name, u.unit_type,"
        + " false AS has_more FROM up JOIN org.org_unit u ON u.tenant_id = :tenant AND u.id = up.id"
        + " AND u.valid_period @> CAST(:d AS date) AND " + Sql.recorded("u", query) + " ORDER BY up.lvl DESC";
    return run(sql, tree, query, 0, unit);
  }

  private static String select(TemporalQuery query) {
    return " SELECT t.id, t.parent_id, t.depth, u.code, u.name, u.unit_type,"
        + " (t.depth = :depth AND EXISTS (SELECT 1 FROM org.org_unit_relation c WHERE c.tenant_id = :tenant"
        + "  AND c.tree = :tree AND c.relation_type = 'TRUC_THUOC' AND c.parent_unit_id = t.id"
        + "  AND c.valid_period @> CAST(:d AS date) AND " + Sql.recorded("c", query) + ")) AS has_more"
        + " FROM t JOIN org.org_unit u ON u.tenant_id = :tenant AND u.id = t.id"
        + " AND u.valid_period @> CAST(:d AS date) AND " + Sql.recorded("u", query);
  }

  private List<TreeRow> run(String sql, TreeKind tree, TemporalQuery query, int depth, UUID root) {
    return jdbc.sql(sql)
        .param("tenant", Sql.tenant()).param("tree", tree.name()).param("d", query.asOf())
        .param("rec", query.recordedAsOf()).param("depth", depth).param("root", root)
        .query((rs, n) -> new TreeRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "parent_id"), rs.getInt("depth"),
            rs.getString("code"), rs.getString("name"), rs.getString("unit_type"), rs.getBoolean("has_more")))
        .list();
  }
}
