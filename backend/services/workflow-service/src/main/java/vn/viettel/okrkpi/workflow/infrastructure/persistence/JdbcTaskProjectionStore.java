package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.TaskProjectionStore;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.InboxCounts;
import vn.viettel.okrkpi.workflow.domain.InboxFilter;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/**
 * {@link TaskProjectionStore} trên {@code flowable.wf_task_projection} (TL-03). Hộp việc chạm hai
 * chỉ mục {@code (tenant_id, assignee_id, due_at)} và {@code (tenant_id, candidate_role,
 * candidate_unit_id, due_at)}, không nối bảng {@code ACT_*}.
 */
@Repository
public class JdbcTaskProjectionStore implements TaskProjectionStore {

  static final String COLUMNS =
      "task_id, tenant_id, assignee_id, candidate_role, candidate_unit_id, business_type,"
          + " business_id, template_code, title, period_id, due_at, created_at, row_version";

  /** Cột thông tin tiến trình bổ sung ở V10 cho giám sát (DV-06-10). */
  static final String PROCESS_COLUMNS =
      "process_instance_id, activity_id, activity_name, template_version, process_started_at";

  private static final RowMapper<TaskProjection> MAPPER = JdbcTaskProjectionStore::map;

  private final JdbcTemplate jdbc;

  public JdbcTaskProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(TaskProjection p) {
    TenantBinding.bind(jdbc, p.tenantId());
    jdbc.update(
        "INSERT INTO flowable.wf_task_projection ("
            + COLUMNS
            + ", "
            + PROCESS_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (task_id) DO UPDATE SET assignee_id = EXCLUDED.assignee_id,"
            + " candidate_role = EXCLUDED.candidate_role,"
            + " candidate_unit_id = EXCLUDED.candidate_unit_id, title = EXCLUDED.title,"
            + " period_id = EXCLUDED.period_id, due_at = EXCLUDED.due_at,"
            + " row_version = EXCLUDED.row_version,"
            + " process_instance_id = COALESCE(EXCLUDED.process_instance_id,"
            + " wf_task_projection.process_instance_id),"
            + " activity_id = COALESCE(EXCLUDED.activity_id, wf_task_projection.activity_id),"
            + " activity_name = COALESCE(EXCLUDED.activity_name, wf_task_projection.activity_name),"
            + " template_version = COALESCE(EXCLUDED.template_version,"
            + " wf_task_projection.template_version),"
            + " process_started_at = COALESCE(EXCLUDED.process_started_at,"
            + " wf_task_projection.process_started_at)",
        p.taskId(),
        p.tenantId(),
        p.assigneeId(),
        p.candidateRole(),
        p.candidateUnitId(),
        p.businessKey().type(),
        p.businessKey().id(),
        p.templateCode(),
        p.title(),
        p.periodId(),
        timestamp(p.dueAt()),
        timestamp(p.createdAt()),
        p.rowVersion(),
        p.process().processInstanceId(),
        p.process().activityId(),
        p.process().activityName(),
        p.process().templateVersion(),
        timestamp(p.process().startedAt()));
  }

  @Override
  public void reassign(UUID tenantId, String taskId, UUID assigneeId, int rowVersion) {
    TenantBinding.bind(jdbc, tenantId);
    jdbc.update(
        "UPDATE flowable.wf_task_projection SET assignee_id = ?, row_version = ?"
            + " WHERE tenant_id = ? AND task_id = ?",
        assigneeId,
        rowVersion,
        tenantId,
        taskId);
  }

  @Override
  public void delete(UUID tenantId, String taskId) {
    TenantBinding.bind(jdbc, tenantId);
    jdbc.update(
        "DELETE FROM flowable.wf_task_projection WHERE tenant_id = ? AND task_id = ?",
        tenantId,
        taskId);
  }

  @Override
  public List<TaskProjection> findInbox(InboxFilter filter, InboxPosition after, int fetchSize) {
    TenantBinding.bind(jdbc, filter.approver().tenantId());
    SqlQuery q = inboxQuery(filter, after, fetchSize);
    return jdbc.query(q.sql(), MAPPER, q.args());
  }

  /**
   * Câu truy vấn một trang hộp việc (công khai để kiểm thử hiệu năng chạy {@code EXPLAIN ANALYZE}
   * trên đúng câu lệnh của ứng dụng).
   */
  public static SqlQuery inboxQuery(InboxFilter filter, InboxPosition after, int fetchSize) {
    StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS);
    sql.append(" FROM flowable.wf_task_projection WHERE ");
    List<Object> args = new ArrayList<>();
    appendFilter(sql, args, filter);
    if (after != null) {
      if (after.dueAt() == null) {
        sql.append(" AND due_at IS NULL AND task_id > ?");
        args.add(after.taskId());
      } else {
        sql.append(" AND (due_at > ? OR (due_at = ? AND task_id > ?) OR due_at IS NULL)");
        args.add(timestamp(after.dueAt()));
        args.add(timestamp(after.dueAt()));
        args.add(after.taskId());
      }
    }
    sql.append(" ORDER BY due_at ASC NULLS LAST, task_id ASC LIMIT ?");
    args.add(fetchSize);
    return new SqlQuery(sql.toString(), args.toArray());
  }

  /**
   * Câu SQL kèm tham số.
   *
   * @param sql câu lệnh có dấu {@code ?}
   * @param args tham số theo thứ tự
   */
  @SuppressWarnings("ArrayRecordComponent")
  public record SqlQuery(String sql, Object[] args) {

    public SqlQuery {
      args = args.clone();
    }

    @Override
    public Object[] args() {
      return args.clone();
    }
  }

  @Override
  public InboxCounts count(InboxFilter filter, Instant now, Instant endOfToday) {
    TenantBinding.bind(jdbc, filter.approver().tenantId());
    StringBuilder sql =
        new StringBuilder(
            "SELECT count(*), count(*) FILTER (WHERE due_at < ?),"
                + " count(*) FILTER (WHERE due_at >= ? AND due_at < ?)"
                + " FROM flowable.wf_task_projection WHERE ");
    List<Object> args = new ArrayList<>();
    args.add(timestamp(now));
    args.add(timestamp(now));
    args.add(timestamp(endOfToday));
    appendFilter(sql, args, filter);
    return jdbc.queryForObject(
        sql.toString(),
        (rs, n) -> new InboxCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
        args.toArray());
  }

  /**
   * Việc giao thẳng cho người gọi, hoặc việc chưa giao có nhóm ứng viên khớp vai trò và đơn vị đang
   * chọn của người gọi.
   */
  private static void appendFilter(StringBuilder sql, List<Object> args, InboxFilter filter) {
    Approver approver = filter.approver();
    sql.append("tenant_id = ? AND (assignee_id = ?");
    args.add(approver.tenantId());
    args.add(approver.actorId());
    if (approver.unitId() != null && !approver.roles().isEmpty()) {
      sql.append(" OR (assignee_id IS NULL AND candidate_unit_id = ? AND candidate_role IN (");
      args.add(approver.unitId());
      List<String> roles = approver.roles().stream().sorted().toList();
      for (int i = 0; i < roles.size(); i++) {
        sql.append(i == 0 ? "?" : ", ?");
        args.add(roles.get(i));
      }
      sql.append("))");
    }
    sql.append(')');
    if (filter.businessType() != null) {
      sql.append(" AND business_type = ?");
      args.add(filter.businessType());
    }
    if (filter.periodId() != null) {
      sql.append(" AND period_id = ?");
      args.add(filter.periodId());
    }
  }

  static TaskProjection map(ResultSet rs, int rowNum) throws SQLException {
    return new TaskProjection(
        rs.getString("task_id"),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("assignee_id", UUID.class),
        rs.getString("candidate_role"),
        rs.getObject("candidate_unit_id", UUID.class),
        new BusinessKey(rs.getString("business_type"), rs.getObject("business_id", UUID.class)),
        rs.getString("template_code"),
        rs.getString("title"),
        rs.getObject("period_id", UUID.class),
        instant(rs.getTimestamp("due_at")),
        instant(rs.getTimestamp("created_at")),
        rs.getInt("row_version"));
  }

  static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
