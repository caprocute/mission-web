package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskScoringReads;

/**
 * {@link TaskScoringReads} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS), chỉ đọc.
 *
 * <p>Việc lá là nhiệm vụ không có việc con; người được tính là người chủ trì đang hiệu lực (suy
 * luận từ BS-03 DC-06: chế độ {@code MOT_NGUOI_MOT_VIEC} giao mỗi việc lá cho một người). Bản nháp
 * không tính. Mức chất lượng nghiệm thu và số ngày chậm theo nguyên nhân chưa có cột trong schema
 * {@code task} nên trả {@code null}/0 (khoảng trống ghi ở wiki 14).
 */
public class JdbcTaskScoringReads implements TaskScoringReads {

  private static final String ACTIVE_LEAD =
      " a.period_bucket = t.period_bucket AND a.tenant_id = t.tenant_id AND a.task_id = t.id"
          + " AND a.role = 'CHU_TRI' AND a.removed_at IS NULL AND a.recorded_to = 'infinity'"
          + " AND a.valid_to = 'infinity'";

  private static final String HAS_CHILD =
      "EXISTS (SELECT 1 FROM task.task c WHERE c.period_bucket = t.period_bucket"
          + " AND c.tenant_id = t.tenant_id AND c.parent_task_id = t.id)";

  private static final String ITEM_COLUMNS =
      "t.id, t.parent_task_id, t.standard_score, t.difficulty,"
          + " t.coefficient_levels::text AS coefficient_levels, t.task_group, t.axis_code,"
          + " t.component_score, t.status, t.done_quantity > t.planned_quantity AS exceeded,"
          + " (t.status = 'TAM_DUNG' OR EXISTS (SELECT 1 FROM task.task_change_request r"
          + " WHERE r.period_bucket = t.period_bucket AND r.tenant_id = t.tenant_id"
          + " AND r.task_id = t.id AND r.status = 'DA_DUYET'"
          + " AND r.request_type IN ('GIA_HAN', 'TAM_DUNG', 'HUY'))) AS deferred";

  private static final TypeReference<Map<String, String>> LEVELS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcTaskScoringReads(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<WorkItemSnapshot> workItems(
      TenantId tenant, UUID periodId, UUID personId, UUID afterId, int limit) {
    Integer bucket = bucket(tenant, periodId);
    if (bucket == null) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + ITEM_COLUMNS
                + " FROM task.task t WHERE t.period_bucket = ? AND t.tenant_id = ?"
                + " AND t.period_id = ? AND t.status <> 'NHAP'"
                + " AND (CAST(? AS uuid) IS NULL OR t.id > ?)"
                + " AND EXISTS (SELECT 1 FROM task.task_assignee a WHERE"
                + ACTIVE_LEAD
                + " AND a.person_id = ?) AND NOT "
                + HAS_CHILD
                + " ORDER BY t.id LIMIT ?")
        .params(bucket, tenant.value(), periodId, afterId, afterId, personId, limit)
        .query(this::item)
        .list();
  }

  @Override
  public Map<UUID, List<WorkItemSnapshot>> workItems(
      TenantId tenant, UUID periodId, Collection<UUID> personIds, int limitPerPerson) {
    Map<UUID, List<WorkItemSnapshot>> out = new LinkedHashMap<>();
    if (personIds.isEmpty()) {
      return out;
    }
    Integer bucket = bucket(tenant, periodId);
    if (bucket == null) {
      return out;
    }
    // Mỗi người một nhánh LATERAL đi theo chỉ mục ix_task_assignee__person với giới hạn riêng, nên
    // kế hoạch không phụ thuộc số người của trang.
    jdbc.sql(
            "SELECT p.person AS person_id, x.* FROM unnest(CAST(? AS uuid[])) AS p(person)"
                + " CROSS JOIN LATERAL (SELECT "
                + ITEM_COLUMNS
                + " FROM task.task_assignee a JOIN task.task t ON t.period_bucket = a.period_bucket"
                + " AND t.tenant_id = a.tenant_id AND t.id = a.task_id"
                + " WHERE a.period_bucket = ? AND a.tenant_id = ? AND a.person_id = p.person"
                + " AND a.role = 'CHU_TRI' AND a.removed_at IS NULL"
                + " AND a.recorded_to = 'infinity' AND a.valid_to = 'infinity'"
                + " AND t.period_id = ? AND t.status <> 'NHAP' AND NOT "
                + HAS_CHILD
                + " ORDER BY t.id LIMIT ?) x ORDER BY p.person, x.id")
        .params(
            personIds.stream().distinct().toArray(UUID[]::new),
            bucket,
            tenant.value(),
            periodId,
            limitPerPerson)
        .query(
            rs -> {
              out.computeIfAbsent(rs.getObject("person_id", UUID.class), k -> new ArrayList<>())
                  .add(item(rs, 0));
            });
    return out;
  }

  @Override
  public List<ResponsibleItem> responsibleItems(TenantId tenant, UUID periodId, UUID personId) {
    Integer bucket = bucket(tenant, periodId);
    if (bucket == null) {
      return List.of();
    }
    List<Root> roots =
        jdbc.sql(
                "SELECT t.id, t.node_kind, t.planned_quantity, t.done_quantity FROM task.task t"
                    + " WHERE t.period_bucket = ? AND t.tenant_id = ? AND t.period_id = ?"
                    + " AND t.status <> 'NHAP'"
                    + " AND EXISTS (SELECT 1 FROM task.task_assignee a WHERE"
                    + ACTIVE_LEAD
                    + " AND a.person_id = ?) AND "
                    + HAS_CHILD
                    + " ORDER BY t.id")
            .params(bucket, tenant.value(), periodId, personId)
            .query(
                (rs, i) ->
                    new Root(
                        rs.getObject("id", UUID.class),
                        rs.getString("node_kind"),
                        rs.getBigDecimal("planned_quantity"),
                        rs.getBigDecimal("done_quantity")))
            .list();
    if (roots.isEmpty()) {
      return List.of();
    }
    String ids =
        roots.stream().map(r -> r.id().toString()).collect(Collectors.joining(",", "{", "}"));
    // Mọi hậu duệ của các mục phụ trách; mỗi nút một lần dù nằm dưới nhiều mục lồng nhau.
    List<Node> nodes =
        jdbc.sql(
                "WITH RECURSIVE sub AS (SELECT t.id, t.parent_task_id, t.standard_score,"
                    + " t.status FROM task.task t WHERE t.period_bucket = ? AND t.tenant_id = ?"
                    + " AND t.parent_task_id = ANY (CAST(? AS uuid[]))"
                    + " UNION SELECT c.id, c.parent_task_id, c.standard_score, c.status"
                    + " FROM task.task c JOIN sub s ON c.period_bucket = ? AND c.tenant_id = ?"
                    + " AND c.parent_task_id = s.id)"
                    + " SELECT s.id, s.parent_task_id, s.standard_score, s.status,"
                    + " NOT EXISTS (SELECT 1 FROM task.task c WHERE c.period_bucket = ?"
                    + " AND c.tenant_id = ? AND c.parent_task_id = s.id) AS leaf FROM sub s")
            .params(bucket, tenant.value(), ids, bucket, tenant.value(), bucket, tenant.value())
            .query(
                (rs, i) ->
                    new Node(
                        rs.getObject("id", UUID.class),
                        rs.getObject("parent_task_id", UUID.class),
                        rs.getBigDecimal("standard_score"),
                        rs.getString("status"),
                        rs.getBoolean("leaf")))
            .list();
    return aggregate(roots, nodes);
  }

  /**
   * Phân vùng của kỳ (mọi nhiệm vụ của một kỳ cùng {@code period_bucket}, việc con cùng kỳ với việc
   * cha); {@code null} khi kỳ chưa có nhiệm vụ.
   */
  private Integer bucket(TenantId tenant, UUID periodId) {
    return jdbc.sql(
            "SELECT period_bucket FROM task.task WHERE tenant_id = ? AND period_id = ? LIMIT 1")
        .params(tenant.value(), periodId)
        .query(Integer.class)
        .optional()
        .orElse(null);
  }

  /**
   * Gán mỗi việc lá cho mục phụ trách gần nhất phía trên nó rồi cộng; việc lá dưới mục con không
   * được cộng thêm vào mục cha (BS-03 DC-03 "không cộng dồn hai lần").
   */
  static List<ResponsibleItem> aggregate(List<Root> roots, List<Node> nodes) {
    Set<UUID> rootIds = roots.stream().map(Root::id).collect(Collectors.toSet());
    Map<UUID, UUID> parent = new HashMap<>();
    nodes.forEach(n -> parent.put(n.id(), n.parentId()));
    Map<UUID, Sum> sums = new HashMap<>();
    for (Node n : nodes) {
      if (!n.leaf() || "NHAP".equals(n.status())) {
        continue;
      }
      UUID owner = n.parentId();
      int guard = 0;
      while (owner != null && !rootIds.contains(owner) && guard++ < 64) {
        owner = parent.get(owner);
      }
      if (owner == null || !rootIds.contains(owner)) {
        continue;
      }
      Sum s = sums.computeIfAbsent(owner, k -> new Sum());
      BigDecimal score = n.standardScore() == null ? BigDecimal.ZERO : n.standardScore();
      s.count++;
      s.standard = s.standard.add(score);
      if ("HOAN_THANH".equals(n.status())) {
        s.completed = s.completed.add(score);
      }
    }
    List<ResponsibleItem> out = new ArrayList<>(roots.size());
    for (Root r : roots) {
      Sum s = sums.getOrDefault(r.id(), new Sum());
      BigDecimal ratio =
          r.planned() == null || r.planned().signum() <= 0 || r.done() == null
              ? null
              : r.done().divide(r.planned(), 4, RoundingMode.HALF_UP);
      out.add(
          new ResponsibleItem(
              "KPI".equals(r.nodeKind()) ? "KPI" : "NHIEM_VU",
              r.id(),
              s.count,
              s.standard,
              s.completed,
              ratio,
              0));
    }
    return out;
  }

  private WorkItemSnapshot item(ResultSet rs, int i) throws SQLException {
    return new WorkItemSnapshot(
        rs.getObject("id", UUID.class),
        rs.getObject("parent_task_id", UUID.class),
        rs.getBigDecimal("standard_score"),
        rs.getObject("difficulty", Integer.class),
        levels(rs.getString("coefficient_levels")),
        rs.getString("task_group"),
        rs.getString("axis_code"),
        rs.getBigDecimal("component_score"),
        rs.getString("status"),
        null,
        0,
        0,
        rs.getBoolean("exceeded"),
        false,
        rs.getBoolean("deferred"));
  }

  private Map<String, String> levels(String text) {
    if (text == null || text.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(text, LEVELS);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("coefficient_levels không hợp lệ", e);
    }
  }

  record Root(UUID id, String nodeKind, BigDecimal planned, BigDecimal done) {}

  record Node(UUID id, UUID parentId, BigDecimal standardScore, String status, boolean leaf) {}

  private static final class Sum {
    int count;
    BigDecimal standard = BigDecimal.ZERO;
    BigDecimal completed = BigDecimal.ZERO;
  }
}
