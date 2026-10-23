package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.InstanceMonitorStore;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.ProcessRef;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceFilter;

/**
 * {@link InstanceMonitorStore} trên {@code flowable.wf_task_projection} (BS-01 SCR-1506: "Dữ liệu
 * đọc từ {@code flowable.wf_task_projection} ..., không truy vấn trực tiếp bảng {@code ACT_*}").
 * Phạm vi cell đọc chéo tenant nhờ chính sách RLS {@code rls_wf_task_projection__nen_tang_doc} của
 * V11, chỉ mở khi giao dịch tự đặt {@code app.platform_scope = 'wf_instance_monitor'}; cờ đặt cục
 * bộ giao dịch và được xoá ngay sau câu đọc.
 */
@Repository
public class JdbcInstanceMonitorStore implements InstanceMonitorStore {

  static final String SCOPE_SETTING = "app.platform_scope";
  static final String SCOPE_VALUE = "wf_instance_monitor";

  private static final String SELECT =
      "SELECT "
          + JdbcTaskProjectionStore.COLUMNS
          + ", "
          + JdbcTaskProjectionStore.PROCESS_COLUMNS
          + " FROM flowable.wf_task_projection";

  /** Kẹt: quá hạn, hoặc không có người xử lý lẫn nhóm ứng viên. */
  private static final String STUCK =
      "(COALESCE(due_at < ?, false) OR (assignee_id IS NULL AND candidate_role IS NULL))";

  private final JdbcTemplate jdbc;

  public JdbcInstanceMonitorStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<TaskProjection> find(InstanceFilter filter, InboxPosition after, int fetchSize) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE true");
    List<Object> args = new ArrayList<>();
    if (!filter.cellScope()) {
      sql.append(" AND tenant_id = ?");
      args.add(filter.tenantId());
    }
    if (filter.templateCode() != null) {
      sql.append(" AND template_code = ?");
      args.add(filter.templateCode());
    }
    if (filter.periodId() != null) {
      sql.append(" AND period_id = ?");
      args.add(filter.periodId());
    }
    if (filter.stuck() != null) {
      sql.append(filter.stuck() ? " AND " : " AND NOT ").append(STUCK);
      args.add(JdbcTaskProjectionStore.timestamp(filter.now()));
    }
    if (after != null) {
      if (after.dueAt() == null) {
        sql.append(" AND due_at IS NULL AND task_id > ?");
        args.add(after.taskId());
      } else {
        sql.append(" AND (due_at > ? OR (due_at = ? AND task_id > ?) OR due_at IS NULL)");
        args.add(JdbcTaskProjectionStore.timestamp(after.dueAt()));
        args.add(JdbcTaskProjectionStore.timestamp(after.dueAt()));
        args.add(after.taskId());
      }
    }
    sql.append(" ORDER BY due_at ASC NULLS LAST, task_id ASC LIMIT ?");
    args.add(fetchSize);
    if (!filter.cellScope()) {
      TenantBinding.bind(jdbc, filter.tenantId());
      return jdbc.query(sql.toString(), JdbcInstanceMonitorStore::map, args.toArray());
    }
    TenantBinding.bind(jdbc, TenantContext.require().value());
    jdbc.queryForObject("select set_config(?, ?, true)", String.class, SCOPE_SETTING, SCOPE_VALUE);
    try {
      return jdbc.query(sql.toString(), JdbcInstanceMonitorStore::map, args.toArray());
    } finally {
      jdbc.queryForObject("select set_config(?, '', true)", String.class, SCOPE_SETTING);
    }
  }

  @Override
  public Optional<TaskProjection> find(UUID tenantId, String taskId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc
        .query(
            SELECT + " WHERE tenant_id = ? AND task_id = ?",
            JdbcInstanceMonitorStore::map,
            tenantId,
            taskId)
        .stream()
        .findFirst();
  }

  @Override
  public void updateDue(UUID tenantId, String taskId, Instant dueAt, int rowVersion) {
    TenantBinding.bind(jdbc, tenantId);
    jdbc.update(
        "UPDATE flowable.wf_task_projection SET due_at = ?, row_version = ?"
            + " WHERE tenant_id = ? AND task_id = ?",
        JdbcTaskProjectionStore.timestamp(dueAt),
        rowVersion,
        tenantId,
        taskId);
  }

  @Override
  public void updateAssignee(UUID tenantId, String taskId, UUID assigneeId, int rowVersion) {
    TenantBinding.bind(jdbc, tenantId);
    jdbc.update(
        "UPDATE flowable.wf_task_projection SET assignee_id = ?, row_version = ?"
            + " WHERE tenant_id = ? AND task_id = ?",
        assigneeId,
        rowVersion,
        tenantId,
        taskId);
  }

  static TaskProjection map(ResultSet rs, int rowNum) throws SQLException {
    TaskProjection base = JdbcTaskProjectionStore.map(rs, rowNum);
    ProcessRef process =
        new ProcessRef(
            rs.getString("process_instance_id"),
            rs.getString("activity_id"),
            rs.getString("activity_name"),
            rs.getObject("template_version", Integer.class),
            JdbcTaskProjectionStore.instant(rs.getTimestamp("process_started_at")));
    return new TaskProjection(
        base.taskId(),
        base.tenantId(),
        base.assigneeId(),
        base.candidateRole(),
        base.candidateUnitId(),
        base.businessKey(),
        base.templateCode(),
        base.title(),
        base.periodId(),
        base.dueAt(),
        base.createdAt(),
        base.rowVersion(),
        process);
  }
}
