package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.tree.port.TaskTreeRows;

/** Cây nhiệm vụ trên {@code task.task}; đi ngược lên gốc bằng truy vấn đệ quy có chặn vòng. */
public class JdbcTaskTreeRows implements TaskTreeRows {

  /** Chặn độ sâu khi đi ngược, lớn hơn mọi khuôn phân rã hợp lệ. */
  static final int MAX_DEPTH = 16;

  private static final String COLUMNS =
      "id, period_id, node_kind, parent_task_id, status, standard_score, component_score";

  private final JdbcClient jdbc;

  public JdbcTaskTreeRows(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<NodeRow> node(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM task.task WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcTaskTreeRows::row)
        .optional();
  }

  @Override
  public List<NodeRow> path(TenantId tenant, UUID id) {
    return jdbc.sql(
            "WITH RECURSIVE up AS (SELECT "
                + COLUMNS
                + ", 1 AS depth, ARRAY[id] AS seen FROM task.task WHERE tenant_id = ? AND id = ?"
                + " UNION ALL SELECT t.id, t.period_id, t.node_kind, t.parent_task_id, t.status,"
                + " t.standard_score, t.component_score, up.depth + 1, up.seen || t.id"
                + " FROM task.task t JOIN up ON t.id = up.parent_task_id WHERE t.tenant_id = ?"
                + " AND up.depth < ? AND NOT t.id = ANY (up.seen)) SELECT "
                + COLUMNS
                + " FROM up ORDER BY depth DESC")
        .params(tenant.value(), id, tenant.value(), MAX_DEPTH)
        .query(JdbcTaskTreeRows::row)
        .list();
  }

  @Override
  public List<NodeRow> children(TenantId tenant, UUID parentId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task WHERE tenant_id = ? AND parent_task_id = ? ORDER BY code, id")
        .params(tenant.value(), parentId)
        .query(JdbcTaskTreeRows::row)
        .list();
  }

  private static NodeRow row(ResultSet rs, int i) throws SQLException {
    return new NodeRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getString("node_kind"),
        rs.getObject("parent_task_id", UUID.class),
        rs.getString("status"),
        rs.getBigDecimal("standard_score"),
        rs.getBigDecimal("component_score"));
  }
}
