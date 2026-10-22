package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries;
import vn.viettel.okrkpi.task.domain.mine.QuickFilter;

/**
 * "Việc của tôi" trên PostgreSQL: tập gốc là CTE trên một phân vùng {@code period_bucket}, nhánh
 * phân công đi qua chỉ mục {@code ix_task_assignee__person}, nhánh đăng ký {@code NHAP} của chính
 * người gọi lọc trên {@code task.task} cùng phân vùng. Khoá con trỏ là (hạn hiệu lực, id).
 */
public class JdbcMyTaskQueries implements MyTaskQueries {

  private static final String OPEN = "('DA_GIAO', 'DANG_THUC_HIEN')";
  private static final String DUE = "coalesce(t.extended_due_date, t.due_date)";
  private static final String LATE =
      "(m.status IN " + OPEN + " AND (m.is_overdue OR m.eff_due < ?))";
  private static final String SOON =
      "(m.status IN " + OPEN + " AND NOT m.is_overdue AND m.eff_due >= ? AND m.eff_due <= ?)";

  private final JdbcClient jdbc;

  public JdbcMyTaskQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<MineRow> listMine(
      TenantId tenant,
      int periodBucket,
      MineFilter f,
      LocalDate afterDue,
      UUID afterId,
      int limit) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql = base(tenant, periodBucket, f.personId(), f.periodId(), params);
    sql.append(
            "SELECT m.*, (SELECT count(*) FROM task.task_blocker b WHERE b.period_bucket ="
                + " m.period_bucket AND b.tenant_id = ? AND b.task_id = m.id AND b.status IN"
                + " ('MO', 'DANG_XU_LY')) AS open_blockers FROM mine m WHERE TRUE")
        .append("");
    params.add(tenant.value());
    common(sql, params, f);
    if (f.statuses() != null && !f.statuses().isEmpty()) {
      sql.append(" AND m.status = ANY(?)");
      params.add(f.statuses().toArray(String[]::new));
    }
    if (f.overdue() != null) {
      sql.append(" AND m.is_overdue = ?");
      params.add(f.overdue());
    }
    if (f.todayView()) {
      sql.append(" AND (").append(LATE).append(" OR ").append(SOON).append(")");
      params.add(date(f.today()));
      params.add(date(f.today()));
      params.add(date(f.soonUntil()));
    } else {
      quick(sql, params, f);
    }
    if (afterDue != null && afterId != null) {
      sql.append(" AND (m.eff_due, m.id) > (?, ?)");
      params.add(date(afterDue));
      params.add(afterId);
    }
    sql.append(" ORDER BY m.eff_due, m.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) ->
                new MineRow(
                    JdbcTaskLifecycleRepository.task(rs, i),
                    rs.getString("my_role"),
                    rs.getInt("open_blockers"),
                    rs.getObject("eff_due", LocalDate.class)))
        .list();
  }

  @Override
  public Map<QuickFilter, Integer> countMine(TenantId tenant, int periodBucket, MineFilter f) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql = base(tenant, periodBucket, f.personId(), f.periodId(), params);
    sql.append("SELECT count(*) AS tat_ca, count(*) FILTER (WHERE ")
        .append(LATE)
        .append(") AS cham, count(*) FILTER (WHERE ")
        .append(SOON)
        .append(") AS sap_han, count(*) FILTER (WHERE m.status = 'CHO_NGHIEM_THU') AS cho_nt,")
        .append(" count(*) FILTER (WHERE m.status = 'HOAN_THANH') AS hoan_thanh")
        .append(" FROM mine m WHERE TRUE");
    params.add(date(f.today()));
    params.add(date(f.today()));
    params.add(date(f.soonUntil()));
    common(sql, params, f);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) -> {
              Map<QuickFilter, Integer> m = new EnumMap<>(QuickFilter.class);
              m.put(QuickFilter.TAT_CA, rs.getInt("tat_ca"));
              m.put(QuickFilter.CHAM, rs.getInt("cham"));
              m.put(QuickFilter.SAP_HAN, rs.getInt("sap_han"));
              m.put(QuickFilter.CHO_NGHIEM_THU, rs.getInt("cho_nt"));
              m.put(QuickFilter.HOAN_THANH, rs.getInt("hoan_thanh"));
              return m;
            })
        .single();
  }

  @Override
  public List<UUID> keyResultsOfMine(
      TenantId tenant, int periodBucket, UUID personId, UUID periodId) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql = base(tenant, periodBucket, personId, periodId, params);
    sql.append(
        "SELECT DISTINCT m.key_result_id FROM mine m WHERE m.key_result_id IS NOT NULL"
            + " ORDER BY m.key_result_id LIMIT 200");
    return jdbc.sql(sql.toString()).params(params).query(UUID.class).list();
  }

  @Override
  public List<OpenAssignment> openAssignments(
      TenantId tenant, int periodBucket, UUID personId, UUID periodId) {
    return jdbc.sql(
            "SELECT a.role, "
                + DUE
                + " AS eff_due FROM task.task_assignee a JOIN task.task t ON t.period_bucket ="
                + " a.period_bucket AND t.tenant_id = a.tenant_id AND t.id = a.task_id WHERE"
                + " a.period_bucket = ? AND a.tenant_id = ? AND a.person_id = ?"
                + " AND a.removed_at IS NULL AND a.recorded_to = 'infinity'"
                + " AND a.valid_to = 'infinity' AND t.period_id = ? AND t.status IN "
                + OPEN)
        .params(periodBucket, tenant.value(), personId, periodId)
        .query(
            (rs, i) ->
                new OpenAssignment(rs.getString("role"), rs.getObject("eff_due", LocalDate.class)))
        .list();
  }

  @Override
  public java.util.Optional<String> roleOn(TenantId tenant, UUID taskId, UUID personId) {
    return jdbc.sql(
            "SELECT role FROM task.task_assignee WHERE tenant_id = ? AND task_id = ?"
                + " AND person_id = ? AND removed_at IS NULL AND recorded_to = 'infinity'"
                + " AND valid_to = 'infinity' ORDER BY CASE role WHEN 'CHU_TRI' THEN 0 ELSE 1 END"
                + " LIMIT 1")
        .params(tenant.value(), taskId, personId)
        .query(String.class)
        .optional();
  }

  /**
   * CTE {@code mine}: việc được phân công còn hiệu lực (trừ NHAP) và đăng ký NHAP của chính mình.
   */
  private static StringBuilder base(
      TenantId tenant, int periodBucket, UUID person, UUID periodId, List<Object> params) {
    String cols = "t." + JdbcTaskLifecycleRepository.COLUMNS.replace(", ", ", t.");
    StringBuilder sql =
        new StringBuilder("WITH mine AS (SELECT ")
            .append(cols)
            .append(", ")
            .append(DUE)
            .append(" AS eff_due, a.role AS my_role FROM task.task_assignee a JOIN task.task t")
            .append(" ON t.period_bucket = a.period_bucket AND t.tenant_id = a.tenant_id")
            .append(" AND t.id = a.task_id WHERE a.period_bucket = ? AND a.tenant_id = ?")
            .append(" AND a.person_id = ? AND a.removed_at IS NULL AND a.recorded_to = 'infinity'")
            .append(" AND a.valid_to = 'infinity' AND t.period_id = ? AND t.status <> 'NHAP'")
            .append(" UNION ALL SELECT ")
            .append(cols)
            .append(", ")
            .append(DUE)
            .append(" AS eff_due, 'CHU_TRI' AS my_role FROM task.task t")
            .append(" WHERE t.period_bucket = ? AND t.tenant_id = ? AND t.period_id = ?")
            .append(" AND t.status = 'NHAP' AND t.source = 'DANG_KY' AND t.created_by = ?) ");
    params.add(periodBucket);
    params.add(tenant.value());
    params.add(person);
    params.add(periodId);
    params.add(periodBucket);
    params.add(tenant.value());
    params.add(periodId);
    params.add(person);
    return sql;
  }

  /** Điều kiện vai trò, nhóm, KR, từ khoá: dùng chung cho danh sách và số đếm. */
  private static void common(StringBuilder sql, List<Object> params, MineFilter f) {
    if (f.role() != null) {
      sql.append(" AND m.my_role = ?");
      params.add(f.role());
    }
    if (f.group() != null) {
      sql.append(" AND m.task_group = ?");
      params.add(f.group());
    }
    if (f.keyResultId() != null) {
      sql.append(" AND m.key_result_id = ?");
      params.add(f.keyResultId());
    }
    if (f.q() != null && !f.q().isBlank()) {
      sql.append(" AND (m.title ILIKE ? OR m.code ILIKE ?)");
      String like = "%" + f.q().strip().replace("%", "\\%").replace("_", "\\_") + "%";
      params.add(like);
      params.add(like);
    }
  }

  private static void quick(StringBuilder sql, List<Object> params, MineFilter f) {
    switch (f.quick()) {
      case CHAM -> {
        sql.append(" AND ").append(LATE);
        params.add(date(f.today()));
      }
      case SAP_HAN -> {
        sql.append(" AND ").append(SOON);
        params.add(date(f.today()));
        params.add(date(f.soonUntil()));
      }
      case CHO_NGHIEM_THU -> sql.append(" AND m.status = 'CHO_NGHIEM_THU'");
      case HOAN_THANH -> sql.append(" AND m.status = 'HOAN_THANH'");
      case TAT_CA -> {
        // không thêm điều kiện
      }
    }
  }

  private static Date date(LocalDate d) {
    return Date.valueOf(d);
  }
}
