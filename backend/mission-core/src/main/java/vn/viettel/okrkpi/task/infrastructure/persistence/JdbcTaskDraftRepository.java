package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.domain.assign.TaskCodes;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;

/** {@link TaskDraftRepository} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). */
public class JdbcTaskDraftRepository implements TaskDraftRepository {

  private static final String DRAFT_COLUMNS =
      "id, code, status, period_id, period_bucket, org_unit_id, assigner_id, is_restricted, source,"
          + " plan_item_id, catalog_item_id, title, task_group, key_result_id, priority, due_date,"
          + " acceptor_id, milestones::text AS milestones, product_name, planned_quantity, unit,"
          + " evidence_required, quality_criteria, source_doc::text AS source_doc, basis,"
          + " row_version";

  private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

  private static final String ACTIVE =
      " AND removed_at IS NULL AND recorded_to = 'infinity' AND valid_to = 'infinity'";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcTaskDraftRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public int nextCodeNumber(TenantId tenant, int periodBucket) {
    String prefix = TaskCodes.prefix(periodBucket);
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("task.code:" + tenant.value() + ":" + prefix)
        .query()
        .listOfRows();
    Integer max =
        jdbc.sql(
                "SELECT max(substr(code, ?)::int) FROM task.task WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND code LIKE ? AND substr(code, ?) ~ '^[0-9]+$'")
            .params(
                prefix.length() + 1,
                periodBucket,
                tenant.value(),
                prefix + "%",
                prefix.length() + 1)
            .query(Integer.class)
            .optional()
            .orElse(null);
    return (max == null ? 0 : max) + 1;
  }

  @Override
  public void insertDraft(TenantId tenant, NewDraft d, Instant at) {
    TaskDraftSpec s = d.spec();
    jdbc.sql(
            "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
                + " title, task_group, is_key_task, source, plan_item_id, catalog_item_id,"
                + " key_result_id, assigner_id, acceptor_id, due_date, milestones,"
                + " planned_quantity, unit, difficulty, standard_score, dup_fingerprint,"
                + " state_machine_version, status, org_unit_id, priority, product_name,"
                + " evidence_required, quality_criteria, basis, source_doc, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?::jsonb, ?, ?, ?, ?, ?, ?, 'NHAP', ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            d.id(),
            d.assignerId(),
            d.assignerId(),
            d.periodId(),
            d.periodBucket(),
            d.code(),
            s.title(),
            s.taskGroup(),
            "THEN_CHOT".equals(s.taskGroup()),
            s.source(),
            s.planItemId(),
            s.catalogItemId(),
            s.keyResultId(),
            d.assignerId(),
            s.acceptorId(),
            s.dueDate(),
            milestones(s),
            s.quantity(),
            s.unit(),
            d.difficulty(),
            d.standardScore(),
            d.fingerprint(),
            d.stateMachineVersion(),
            d.orgUnitId(),
            s.priority(),
            s.productName(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            s.basis(),
            sourceDoc(s),
            Timestamp.from(at),
            Timestamp.from(at))
        .update();
    insertAssignees(
        tenant, d.periodId(), d.periodBucket(), d.id(), s, d.leadUnitId(), d.assignerId(), at);
  }

  @Override
  public Optional<DraftRow> findDraft(TenantId tenant, UUID taskId) {
    return read(tenant, taskId);
  }

  private Optional<DraftRow> read(TenantId tenant, UUID taskId) {
    Optional<DraftRow> head =
        jdbc.sql("SELECT " + DRAFT_COLUMNS + " FROM task.task WHERE tenant_id = ? AND id = ?")
            .params(tenant.value(), taskId)
            .query(this::draft)
            .optional();
    return head.map(
        d -> {
          List<AssigneeRow> people = assignees(tenant, d.periodBucket(), taskId);
          UUID lead =
              people.stream()
                  .filter(a -> "CHU_TRI".equals(a.role()) && "PERSON".equals(a.kind()))
                  .map(AssigneeRow::id)
                  .findFirst()
                  .orElse(null);
          List<TaskDraftSpec.Assignee> co =
              people.stream()
                  .filter(a -> "PHOI_HOP".equals(a.role()))
                  .map(a -> new TaskDraftSpec.Assignee(a.kind(), a.id()))
                  .toList();
          TaskDraftSpec s = d.spec();
          return new DraftRow(
              d.id(),
              d.code(),
              d.status(),
              d.periodId(),
              d.periodBucket(),
              d.orgUnitId(),
              d.assignerId(),
              d.restricted(),
              new TaskDraftSpec(
                  s.periodId(),
                  s.source(),
                  s.planItemId(),
                  s.catalogItemId(),
                  s.title(),
                  s.taskGroup(),
                  s.keyResultId(),
                  s.priority(),
                  lead,
                  co,
                  s.dueDate(),
                  s.acceptorId(),
                  s.milestones(),
                  s.productName(),
                  s.quantity(),
                  s.unit(),
                  s.evidenceRequired(),
                  s.qualityCriteria(),
                  s.sourceDocNumber(),
                  s.sourceDocTitle(),
                  s.basis()),
              d.rowVersion());
        });
  }

  @Override
  public long storeDraft(
      TenantId tenant,
      DraftRow current,
      long expectedVersion,
      TaskDraftSpec s,
      Integer difficulty,
      BigDecimal standardScore,
      UUID actorId,
      Instant at) {
    int n =
        jdbc.sql(
                "UPDATE task.task SET source = ?, plan_item_id = ?, catalog_item_id = ?, title = ?,"
                    + " task_group = ?, is_key_task = ?, key_result_id = ?, priority = ?,"
                    + " acceptor_id = ?, due_date = ?, milestones = ?::jsonb, product_name = ?,"
                    + " planned_quantity = ?, unit = ?, evidence_required = ?,"
                    + " quality_criteria = ?, basis = ?, source_doc = ?::jsonb, difficulty = ?,"
                    + " standard_score = ?, updated_by = ?, updated_at = ?,"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND row_version = ? AND status = 'NHAP'")
            .params(
                s.source(),
                s.planItemId(),
                s.catalogItemId(),
                s.title(),
                s.taskGroup(),
                "THEN_CHOT".equals(s.taskGroup()),
                s.keyResultId(),
                s.priority(),
                s.acceptorId(),
                s.dueDate(),
                milestones(s),
                s.productName(),
                s.quantity(),
                s.unit(),
                s.evidenceRequired(),
                s.qualityCriteria(),
                s.basis(),
                sourceDoc(s),
                difficulty,
                standardScore,
                actorId,
                Timestamp.from(at),
                current.periodBucket(),
                tenant.value(),
                current.id(),
                expectedVersion)
            .update();
    if (n != 1) {
      return -1;
    }
    UUID leadUnit =
        assignees(tenant, current.periodBucket(), current.id()).stream()
            .filter(a -> "CHU_TRI".equals(a.role()) && "UNIT".equals(a.kind()))
            .map(AssigneeRow::id)
            .findFirst()
            .orElse(null);
    jdbc.sql(
            "DELETE FROM task.task_assignee WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ?")
        .params(current.periodBucket(), tenant.value(), current.id())
        .update();
    insertAssignees(
        tenant,
        current.periodId(),
        current.periodBucket(),
        current.id(),
        s,
        s.leadPersonId() == null ? leadUnit : null,
        actorId,
        at);
    return expectedVersion + 1;
  }

  @Override
  public boolean discardDraft(TenantId tenant, int periodBucket, UUID taskId, long expectedVersion) {
    jdbc.sql(
            "DELETE FROM task.task_assignee WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ?")
        .params(periodBucket, tenant.value(), taskId)
        .update();
    return jdbc.sql(
                "DELETE FROM task.task WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                    + " AND row_version = ? AND status = 'NHAP'")
            .params(periodBucket, tenant.value(), taskId, expectedVersion)
            .update()
        == 1;
  }

  @Override
  public void markAssigned(TenantId tenant, int periodBucket, UUID taskId, Instant at) {
    jdbc.sql(
            "UPDATE task.task SET assigned_at = ? WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ?")
        .params(Timestamp.from(at), periodBucket, tenant.value(), taskId)
        .update();
  }

  @Override
  public List<AssigneeRow> assignees(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT assignee_type, person_id, org_unit_id, role FROM task.task_assignee"
                + " WHERE period_bucket = ? AND tenant_id = ? AND task_id = ?"
                + ACTIVE
                + " ORDER BY CASE role WHEN 'CHU_TRI' THEN 0 ELSE 1 END, created_at, id")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                "CA_NHAN".equals(rs.getString(1))
                    ? new AssigneeRow("PERSON", rs.getObject(2, UUID.class), rs.getString(4))
                    : new AssigneeRow("UNIT", rs.getObject(3, UUID.class), rs.getString(4)))
        .list();
  }

  @Override
  public boolean planItemApproved(TenantId tenant, int periodBucket, UUID planItemId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM task.plan_item i JOIN task.plan_version v"
                + " ON v.period_bucket = i.period_bucket AND v.tenant_id = i.tenant_id"
                + " AND v.id = i.plan_version_id WHERE i.period_bucket = ? AND i.tenant_id = ?"
                + " AND i.id = ? AND v.status = 'DA_DUYET' AND NOT EXISTS (SELECT 1"
                + " FROM task.plan_version n WHERE n.period_bucket = v.period_bucket"
                + " AND n.tenant_id = v.tenant_id AND n.plan_id = v.plan_id"
                + " AND n.status = 'DA_DUYET' AND n.version_no > v.version_no))")
        .params(periodBucket, tenant.value(), planItemId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<CatalogDefaults> catalogDefaults(TenantId tenant, UUID catalogItemId) {
    return jdbc.sql(
            "SELECT difficulty, standard_score FROM task.catalog_item WHERE tenant_id = ?"
                + " AND id = ?")
        .params(tenant.value(), catalogItemId)
        .query(
            (rs, i) -> {
              int difficulty = rs.getInt(1);
              boolean none = rs.wasNull();
              return new CatalogDefaults(none ? null : difficulty, rs.getBigDecimal(2));
            })
        .optional();
  }

  @Override
  public List<UnitTaskRow> listUnitTasks(
      TenantId tenant, int periodBucket, UnitFilter f, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT t.")
            .append(JdbcTaskLifecycleRepository.COLUMNS.replace(", ", ", t."))
            .append(", t.is_restricted, (SELECT coalesce(a.person_id, a.org_unit_id)")
            .append(" FROM task.task_assignee a WHERE a.period_bucket = t.period_bucket")
            .append(" AND a.tenant_id = t.tenant_id AND a.task_id = t.id AND a.role = 'CHU_TRI'")
            .append(" AND a.removed_at IS NULL AND a.recorded_to = 'infinity'")
            .append(" AND a.valid_to = 'infinity' LIMIT 1) AS lead_id")
            .append(" FROM task.task t WHERE t.period_bucket = ? AND t.tenant_id = ?")
            .append(" AND t.period_id = ? AND (t.org_unit_id = ANY(?) OR EXISTS (SELECT 1")
            .append(" FROM task.task_assignee u WHERE u.period_bucket = t.period_bucket")
            .append(" AND u.tenant_id = t.tenant_id AND u.task_id = t.id")
            .append(" AND u.org_unit_id = ANY(?)")
            .append(ACTIVE.replace(" AND ", " AND u."))
            .append("))");
    List<Object> params = new ArrayList<>();
    UUID[] units = f.unitIds().toArray(UUID[]::new);
    params.add(periodBucket);
    params.add(tenant.value());
    params.add(f.periodId());
    params.add(units);
    params.add(units);
    if (f.statuses() != null && !f.statuses().isEmpty()) {
      sql.append(" AND t.status = ANY(?)");
      params.add(f.statuses().toArray(String[]::new));
    }
    if (f.keyResultId() != null) {
      sql.append(" AND t.key_result_id = ?");
      params.add(f.keyResultId());
    }
    if (f.overdue() != null) {
      sql.append(" AND t.is_overdue = ?");
      params.add(f.overdue());
    }
    if (f.q() != null && !f.q().isBlank()) {
      sql.append(" AND (t.title ILIKE ? OR t.code ILIKE ?) AND NOT t.is_restricted");
      String like = "%" + f.q().strip().replace("%", "\\%").replace("_", "\\_") + "%";
      params.add(like);
      params.add(like);
    }
    if (afterId != null) {
      sql.append(" AND t.id > ?");
      params.add(afterId);
    }
    sql.append(" ORDER BY t.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) ->
                new UnitTaskRow(
                    JdbcTaskLifecycleRepository.task(rs, i),
                    rs.getObject("lead_id", UUID.class),
                    rs.getBoolean("is_restricted")))
        .list();
  }

  private void insertAssignees(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      TaskDraftSpec s,
      UUID leadUnitId,
      UUID actorId,
      Instant at) {
    LocalDate from = LocalDate.ofInstant(at, ZONE);
    if (s.leadPersonId() != null) {
      assignee(
          periodId,
          periodBucket,
          taskId,
          "CA_NHAN",
          s.leadPersonId(),
          null,
          "CHU_TRI",
          actorId,
          from);
    } else if (leadUnitId != null) {
      assignee(
          periodId, periodBucket, taskId, "DON_VI", null, leadUnitId, "CHU_TRI", actorId, from);
    }
    for (TaskDraftSpec.Assignee a : s.coAssignees().stream().distinct().toList()) {
      if (a.person()) {
        assignee(
            periodId, periodBucket, taskId, "CA_NHAN", a.id(), null, "PHOI_HOP", actorId, from);
      } else {
        assignee(periodId, periodBucket, taskId, "DON_VI", null, a.id(), "PHOI_HOP", actorId, from);
      }
    }
  }

  private void assignee(
      UUID periodId,
      int periodBucket,
      UUID taskId,
      String type,
      UUID person,
      UUID unit,
      String role,
      UUID actorId,
      LocalDate from) {
    jdbc.sql(
            "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id, period_bucket,"
                + " task_id, assignee_type, person_id, org_unit_id, role, valid_from)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            actorId,
            actorId,
            periodId,
            periodBucket,
            taskId,
            type,
            person,
            unit,
            role,
            from)
        .update();
  }

  private DraftRow draft(ResultSet rs, int i) throws SQLException {
    JsonNode doc = read(rs.getString("source_doc"));
    List<TaskDraftSpec.Milestone> milestones = new ArrayList<>();
    for (JsonNode m : read(rs.getString("milestones"))) {
      milestones.add(
          new TaskDraftSpec.Milestone(
              LocalDate.parse(m.path("date").asText()), m.path("content").asText("")));
    }
    UUID periodId = rs.getObject("period_id", UUID.class);
    return new DraftRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("status"),
        periodId,
        rs.getInt("period_bucket"),
        rs.getObject("org_unit_id", UUID.class),
        rs.getObject("assigner_id", UUID.class),
        rs.getBoolean("is_restricted"),
        new TaskDraftSpec(
            periodId,
            rs.getString("source"),
            rs.getObject("plan_item_id", UUID.class),
            rs.getObject("catalog_item_id", UUID.class),
            rs.getString("title"),
            rs.getString("task_group"),
            rs.getObject("key_result_id", UUID.class),
            rs.getString("priority"),
            null,
            List.of(),
            rs.getObject("due_date", LocalDate.class),
            rs.getObject("acceptor_id", UUID.class),
            milestones,
            rs.getString("product_name"),
            rs.getBigDecimal("planned_quantity"),
            rs.getString("unit"),
            rs.getString("evidence_required"),
            rs.getString("quality_criteria"),
            doc.hasNonNull("number") ? doc.get("number").asText() : null,
            doc.hasNonNull("title") ? doc.get("title").asText() : null,
            rs.getString("basis")),
        rs.getLong("row_version"));
  }

  private String milestones(TaskDraftSpec s) {
    if (s.milestones().isEmpty()) {
      return null;
    }
    ArrayNode arr = json.createArrayNode();
    s.milestones()
        .forEach(m -> arr.addObject().put("date", m.date().toString()).put("content", m.content()));
    return arr.toString();
  }

  private String sourceDoc(TaskDraftSpec s) {
    if (s.sourceDocNumber() == null && s.sourceDocTitle() == null) {
      return null;
    }
    ObjectNode o = json.createObjectNode();
    o.put("number", s.sourceDocNumber() == null ? "" : s.sourceDocNumber());
    o.put("title", s.sourceDocTitle() == null ? "" : s.sourceDocTitle());
    return o.toString();
  }

  private JsonNode read(String text) {
    if (text == null) {
      return json.createObjectNode();
    }
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      return json.createObjectNode();
    }
  }
}
