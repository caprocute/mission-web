package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * {@link PlanRepository} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). Mọi truy vấn
 * mang {@code period_bucket} để chỉ quét một phân vùng. Tên sản phẩm lưu ở {@code
 * plan_item.product_name} (TL-03 bổ sung 23/10/2026); dòng ghi trước khi có cột lấy {@code
 * catalog_item.product_name}, rồi tới nội dung công việc (suy luận).
 */
public class JdbcPlanRepository implements PlanRepository {

  private static final UUID MIN_UUID = new UUID(0, 0);
  private static final TypeReference<List<Assignee>> ASSIGNEES = new TypeReference<>() {};
  private static final TypeReference<List<RemovedItem>> REMOVED = new TypeReference<>() {};

  private static final String PLAN_COLS =
      "p.id, p.period_id, p.period_bucket, p.plan_type, p.owner_unit_id, p.owner_person_id,"
          + " p.current_version_id, p.row_version";

  private static final String VERSION_COLS =
      "v.id AS v_id, v.plan_id, v.version_no, v.status, v.is_current, v.submitted_at,"
          + " v.approved_at, v.content_hash, v.review_comment, v.change_reason,"
          + " v.row_version AS v_row_version, (SELECT count(*) FROM task.plan_item i"
          + " WHERE i.period_bucket = v.period_bucket AND i.tenant_id = v.tenant_id"
          + " AND i.plan_version_id = v.id) AS item_count";

  private static final String ITEM_COLS =
      "i.id, i.item_no, i.catalog_item_id, c.code AS catalog_code, i.key_result_id, i.title,"
          + " i.task_group, i.lead_unit_id, i.lead_person_id, i.co_assignees::text AS co_assignees,"
          + " coalesce(i.product_name, c.product_name, i.title) AS product_name, i.planned_quantity, i.unit,"
          + " i.difficulty, i.standard_score, i.due_date, i.origin_item_id, i.change_reason,"
          + " i.recurrence";

  private static final String CATALOG_JOIN =
      " LEFT JOIN task.catalog_item c ON c.tenant_id = i.tenant_id AND c.id = i.catalog_item_id";

  /** Người gọi hệ thống của tác vụ định kỳ. */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private final JdbcClient jdbc;
  private final JdbcTemplate batch;
  private final ObjectMapper json;

  public JdbcPlanRepository(DataSource dataSource, ObjectMapper json) {
    this.jdbc = JdbcClient.create(dataSource);
    this.batch = new JdbcTemplate(dataSource);
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public boolean insertPlan(TenantId tenant, PlanRow plan, UUID versionId, UUID actor) {
    int n =
        jdbc.sql(
                "WITH p AS (INSERT INTO task.plan (id, created_by, updated_by, period_id,"
                    + " period_bucket, plan_type, owner_unit_id, owner_person_id,"
                    + " current_version_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING RETURNING id, period_id, period_bucket)"
                    + " INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                    + " period_bucket, plan_id, version_no, status)"
                    + " SELECT ?, ?, ?, period_id, period_bucket, id, 1, 'NHAP' FROM p")
            .params(
                plan.id(),
                actor,
                actor,
                plan.periodId(),
                plan.periodBucket(),
                plan.planType(),
                plan.ownerUnitId(),
                plan.ownerPersonId(),
                versionId,
                versionId,
                actor,
                actor)
            .update();
    return n == 1;
  }

  @Override
  public Optional<PlanRow> findPlan(TenantId tenant, UUID planId) {
    return jdbc.sql("SELECT " + PLAN_COLS + " FROM task.plan p WHERE p.tenant_id = ? AND p.id = ?")
        .params(tenant.value(), planId)
        .query(JdbcPlanRepository::plan)
        .optional();
  }

  @Override
  public List<PlanRow> listPlans(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      UUID unitId,
      PlanVersionStatus status,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(PLAN_COLS)
            .append(
                " FROM task.plan p WHERE p.period_bucket = ? AND p.tenant_id = ?"
                    + " AND p.period_id = ? AND p.id > ?");
    List<Object> params = new ArrayList<>(List.of(periodBucket, tenant.value(), periodId));
    params.add(afterId == null ? MIN_UUID : afterId);
    if (unitId != null) {
      sql.append(" AND p.owner_unit_id = ?");
      params.add(unitId);
    }
    if (status != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM task.plan_version v WHERE v.period_bucket = p.period_bucket"
              + " AND v.tenant_id = p.tenant_id AND v.id = p.current_version_id AND v.status = ?)");
      params.add(status.name());
    }
    sql.append(" ORDER BY p.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(JdbcPlanRepository::plan).list();
  }

  @Override
  public List<VersionRow> versions(TenantId tenant, int periodBucket, UUID planId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLS
                + " FROM task.plan_version v WHERE v.period_bucket = ? AND v.tenant_id = ?"
                + " AND v.plan_id = ? ORDER BY v.version_no DESC")
        .params(periodBucket, tenant.value(), planId)
        .query(JdbcPlanRepository::version)
        .list();
  }

  @Override
  public Optional<VersionRow> lockVersion(
      TenantId tenant, int periodBucket, UUID planId, int versionNo) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLS
                + " FROM task.plan_version v WHERE v.period_bucket = ? AND v.tenant_id = ?"
                + " AND v.plan_id = ? AND v.version_no = ? FOR UPDATE OF v")
        .params(periodBucket, tenant.value(), planId, versionNo)
        .query(JdbcPlanRepository::version)
        .optional();
  }

  @Override
  public List<ItemRow> items(TenantId tenant, int periodBucket, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + ITEM_COLS
                + " FROM task.plan_item i"
                + CATALOG_JOIN
                + " WHERE i.period_bucket = ? AND i.tenant_id = ? AND i.plan_version_id = ?"
                + " ORDER BY i.item_no")
        .params(periodBucket, tenant.value(), versionId)
        .query((rs, n) -> item(rs))
        .list();
  }

  @Override
  public long replaceItems(
      TenantId tenant, PlanRow plan, VersionRow version, List<ItemRow> items, UUID actor) {
    jdbc.sql(
            "DELETE FROM task.plan_item WHERE period_bucket = ? AND tenant_id = ?"
                + " AND plan_version_id = ?")
        .params(plan.periodBucket(), tenant.value(), version.id())
        .update();
    batch.batchUpdate(
        "INSERT INTO task.plan_item (id, created_by, updated_by, period_id, period_bucket,"
            + " plan_version_id, item_no, catalog_item_id, key_result_id, title, task_group,"
            + " lead_unit_id, lead_person_id, co_unit_ids, planned_quantity, unit, difficulty,"
            + " standard_score, due_date, origin_item_id, change_reason, co_assignees, recurrence,"
            + " product_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?,"
            + " ?, ?, ?, ?::jsonb, ?, ?)",
        items.stream()
            .map(
                i ->
                    new Object[] {
                      i.id(),
                      actor,
                      actor,
                      plan.periodId(),
                      plan.periodBucket(),
                      version.id(),
                      i.itemNo(),
                      i.catalogItemId(),
                      i.keyResultId(),
                      i.title(),
                      i.taskGroup(),
                      "UNIT".equals(i.lead().kind()) ? i.lead().id() : null,
                      "PERSON".equals(i.lead().kind()) ? i.lead().id() : null,
                      uuidArray(
                          i.coAssignees().stream()
                              .filter(a -> "UNIT".equals(a.kind()))
                              .map(Assignee::id)
                              .toList()),
                      i.quantity(),
                      i.unit(),
                      i.difficulty(),
                      i.standardScore(),
                      i.dueDate(),
                      i.originItemId(),
                      i.changeReason(),
                      i.coAssignees().isEmpty() ? null : write(i.coAssignees()),
                      i.recurrence(),
                      i.productName()
                    })
            .toList());
    // BS-03 DC-01 (CORE-02-27): mức hệ số và trục sao chép từ mục danh mục của dòng.
    jdbc.sql(
            "UPDATE task.plan_item p SET coefficient_levels = c.coefficient_levels, axis_code ="
                + " c.axis_code FROM task.catalog_item c WHERE p.period_bucket = ? AND"
                + " p.tenant_id = ? AND p.plan_version_id = ? AND c.tenant_id = p.tenant_id AND"
                + " c.id = p.catalog_item_id")
        .params(plan.periodBucket(), tenant.value(), version.id())
        .update();
    return jdbc.sql(
            "UPDATE task.plan_version SET row_version = row_version + 1, updated_by = ?,"
                + " updated_at = now() WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " AND row_version = ? RETURNING row_version")
        .params(actor, plan.periodBucket(), tenant.value(), version.id(), version.rowVersion())
        .query(Long.class)
        .optional()
        .orElseThrow(NoSuchElementException::new);
  }

  @Override
  public Map<UUID, CatalogRow> catalogItems(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, CatalogRow> out = new HashMap<>();
    jdbc.sql(
            "SELECT id, code, product_name, unit, difficulty, standard_score, quality_standard,"
                + " evidence_requirement FROM task.catalog_item WHERE tenant_id = ?"
                + " AND id = ANY(?::uuid[])")
        .params(tenant.value(), uuidArray(ids))
        .query(
            (rs, n) ->
                new CatalogRow(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getInt(5),
                    rs.getBigDecimal(6),
                    rs.getString(7),
                    rs.getString(8)))
        .list()
        .forEach(c -> out.put(c.id(), c));
    return out;
  }

  @Override
  public Optional<Map.Entry<PlanRow, VersionRow>> effectiveOf(
      TenantId tenant, int periodBucket, UUID periodId, UUID ownerUnitId, UUID ownerPersonId) {
    return jdbc.sql(
            "SELECT "
                + PLAN_COLS
                + ", "
                + VERSION_COLS
                + " FROM task.plan p JOIN task.plan_version v ON v.period_bucket = p.period_bucket"
                + " AND v.tenant_id = p.tenant_id AND v.plan_id = p.id"
                + " WHERE p.period_bucket = ? AND p.tenant_id = ?"
                + " AND (?::uuid IS NULL OR p.period_id = ?) AND p.owner_unit_id = ?"
                + " AND p.owner_person_id IS NOT DISTINCT FROM ? AND v.status = 'DA_DUYET'"
                + " ORDER BY v.approved_at DESC NULLS LAST, v.version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), periodId, periodId, ownerUnitId, ownerPersonId)
        .query(
            (rs, n) ->
                (Map.Entry<PlanRow, VersionRow>)
                    new AbstractMap.SimpleImmutableEntry<>(plan(rs, n), version(rs, n)))
        .optional();
  }

  @Override
  public Set<UUID> closedItems(TenantId tenant, int periodBucket, Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql(
                "SELECT DISTINCT plan_item_id FROM task.task WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND plan_item_id = ANY(?::uuid[])"
                    + " AND status IN ('HOAN_THANH', 'HUY')")
            .params(periodBucket, tenant.value(), uuidArray(itemIds))
            .query(UUID.class)
            .list());
  }

  @Override
  public Set<UUID> linkedKeyResults(TenantId tenant, int periodBucket, UUID periodId, UUID planId) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT i.key_result_id FROM task.plan_item i JOIN task.plan_version v"
                    + " ON v.period_bucket = i.period_bucket AND v.tenant_id = i.tenant_id"
                    + " AND v.id = i.plan_version_id WHERE i.period_bucket = ? AND i.tenant_id = ?"
                    + " AND v.plan_id = ? AND i.key_result_id IS NOT NULL"
                    + " UNION SELECT key_result_id FROM task.task WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND period_id = ? AND key_result_id IS NOT NULL")
            .params(periodBucket, tenant.value(), planId, periodBucket, tenant.value(), periodId)
            .query(UUID.class)
            .list());
  }

  @Override
  public List<ApprovedItemRow> approvedItems(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      PlanVersionStatus status,
      UUID afterPlanId,
      int afterItemNo,
      int limit) {
    return jdbc.sql(
            "WITH v AS (SELECT DISTINCT ON (pv.plan_id) pv.id, pv.plan_id, pv.version_no"
                + " FROM task.plan_version pv WHERE pv.period_bucket = ? AND pv.tenant_id = ?"
                + " AND pv.period_id = ? AND pv.status = ?"
                + " ORDER BY pv.plan_id, pv.version_no DESC)"
                + " SELECT v.plan_id, v.version_no, "
                + ITEM_COLS
                + ", c.quality_standard, c.evidence_requirement FROM v JOIN task.plan_item i"
                + " ON i.period_bucket = ? AND i.tenant_id = ? AND i.plan_version_id = v.id"
                + CATALOG_JOIN
                + " WHERE NOT EXISTS (SELECT 1 FROM task.task t WHERE t.period_bucket = ?"
                + " AND t.tenant_id = ? AND t.plan_item_id = i.id)"
                + " AND (v.plan_id, i.item_no) > (?, ?) ORDER BY v.plan_id, i.item_no LIMIT ?")
        .params(
            periodBucket,
            tenant.value(),
            periodId,
            status.name(),
            periodBucket,
            tenant.value(),
            periodBucket,
            tenant.value(),
            afterPlanId == null ? MIN_UUID : afterPlanId,
            afterItemNo,
            limit)
        .query(
            (rs, n) ->
                new ApprovedItemRow(
                    rs.getObject("plan_id", UUID.class),
                    periodId,
                    rs.getInt("version_no"),
                    item(rs),
                    rs.getString("quality_standard"),
                    rs.getString("evidence_requirement")))
        .list();
  }

  @Override
  public Optional<ApprovalRow> approvalRow(TenantId tenant, int periodBucket, UUID versionId) {
    return jdbc.sql(
            "SELECT created_by, sign_request_id, approved_by FROM task.plan_version"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(periodBucket, tenant.value(), versionId)
        .query(JdbcPlanRepository::approval)
        .optional();
  }

  @Override
  public long attachSignRequest(
      TenantId tenant, PlanRow plan, VersionRow version, UUID signRequestId, UUID actor) {
    return jdbc.sql(
            "UPDATE task.plan_version SET sign_request_id = ?, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " AND status = 'CHO_DUYET' RETURNING row_version")
        .params(signRequestId, actor, plan.periodBucket(), tenant.value(), version.id())
        .query(Long.class)
        .single();
  }

  @Override
  public boolean linkProcessInstance(
      TenantId tenant, int periodBucket, UUID versionId, String processInstanceId) {
    return jdbc.sql(
                "UPDATE task.plan_version SET wf_process_instance_id = ? WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND id = ? AND status = 'CHO_DUYET'")
            .params(processInstanceId, periodBucket, tenant.value(), versionId)
            .update()
        > 0;
  }

  @Override
  public Optional<SignedTarget> versionById(TenantId tenant, UUID versionId, boolean forUpdate) {
    Optional<int[]> bucket =
        jdbc.sql("SELECT period_bucket FROM task.plan_version WHERE tenant_id = ? AND id = ?")
            .params(tenant.value(), versionId)
            .query((rs, n) -> new int[] {rs.getInt(1)})
            .optional();
    if (bucket.isEmpty()) {
      return Optional.empty();
    }
    int b = bucket.get()[0];
    return jdbc.sql(
            "SELECT "
                + VERSION_COLS
                + ", v.created_by, v.sign_request_id, v.approved_by FROM task.plan_version v"
                + " WHERE v.period_bucket = ? AND v.tenant_id = ? AND v.id = ?"
                + (forUpdate ? " FOR UPDATE OF v" : ""))
        .params(b, tenant.value(), versionId)
        .query((rs, n) -> new Object[] {version(rs, n), approval(rs, n)})
        .optional()
        .flatMap(
            row -> {
              VersionRow v = (VersionRow) row[0];
              return findPlan(tenant, v.planId())
                  .map(p -> new SignedTarget(p, v, (ApprovalRow) row[1]));
            });
  }

  @Override
  public Approved markApproved(
      TenantId tenant, PlanRow plan, VersionRow version, UUID approver, Instant at) {
    UUID superseded =
        jdbc.sql(
                "UPDATE task.plan_version SET is_current = false, updated_by = ?, updated_at = now()"
                    + " WHERE period_bucket = ? AND tenant_id = ? AND plan_id = ? AND is_current"
                    + " AND id <> ? RETURNING id")
            .params(approver, plan.periodBucket(), tenant.value(), plan.id(), version.id())
            .query(UUID.class)
            .optional()
            .orElse(null);
    long rowVersion =
        jdbc.sql(
                "UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true,"
                    + " approved_by = ?, approved_at = ?, updated_by = ?, updated_at = now(),"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND status = 'CHO_DUYET' RETURNING row_version")
            .params(
                approver,
                Timestamp.from(at),
                approver,
                plan.periodBucket(),
                tenant.value(),
                version.id())
            .query(Long.class)
            .single();
    return new Approved(superseded, rowVersion);
  }

  @Override
  public Optional<UUID> signRequestInitiator(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT coalesce(approved_by, updated_by) FROM task.plan_version WHERE tenant_id = ?"
                + " AND sign_request_id = ? LIMIT 1")
        .params(tenant.value(), signRequestId)
        .query(UUID.class)
        .optional();
  }

  private static ApprovalRow approval(ResultSet rs, int n) throws SQLException {
    return new ApprovalRow(
        rs.getObject("created_by", UUID.class),
        rs.getObject("sign_request_id", UUID.class),
        rs.getObject("approved_by", UUID.class));
  }

  private static PlanRow plan(ResultSet rs, int n) throws SQLException {
    return new PlanRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("plan_type"),
        rs.getObject("owner_unit_id", UUID.class),
        rs.getObject("owner_person_id", UUID.class),
        rs.getObject("current_version_id", UUID.class),
        rs.getLong("row_version"));
  }

  private static VersionRow version(ResultSet rs, int n) throws SQLException {
    Timestamp submitted = rs.getTimestamp("submitted_at");
    Timestamp approved = rs.getTimestamp("approved_at");
    return new VersionRow(
        rs.getObject("v_id", UUID.class),
        rs.getObject("plan_id", UUID.class),
        rs.getInt("version_no"),
        PlanVersionStatus.valueOf(rs.getString("status")),
        rs.getBoolean("is_current"),
        submitted == null ? null : submitted.toInstant(),
        approved == null ? null : approved.toInstant(),
        rs.getBytes("content_hash"),
        rs.getString("review_comment"),
        rs.getString("change_reason"),
        rs.getInt("item_count"),
        rs.getLong("v_row_version"));
  }

  private ItemRow item(ResultSet rs) throws SQLException {
    UUID leadUnit = rs.getObject("lead_unit_id", UUID.class);
    UUID leadPerson = rs.getObject("lead_person_id", UUID.class);
    Integer difficulty = (Integer) rs.getObject("difficulty", Integer.class);
    return new ItemRow(
        rs.getObject("id", UUID.class),
        rs.getInt("item_no"),
        rs.getObject("catalog_item_id", UUID.class),
        rs.getString("catalog_code"),
        rs.getObject("key_result_id", UUID.class),
        rs.getString("title"),
        rs.getString("task_group"),
        leadUnit != null
            ? new Assignee("UNIT", leadUnit, "")
            : new Assignee("PERSON", leadPerson, ""),
        read(rs.getString("co_assignees")),
        rs.getString("product_name"),
        rs.getBigDecimal("planned_quantity"),
        rs.getString("unit"),
        difficulty,
        rs.getBigDecimal("standard_score"),
        rs.getObject("due_date", java.time.LocalDate.class),
        rs.getObject("origin_item_id", UUID.class),
        rs.getString("change_reason"),
        rs.getString("recurrence"));
  }

  private List<Assignee> read(String value) {
    if (value == null) {
      return List.of();
    }
    try {
      return json.readValue(value, ASSIGNEES);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("co_assignees không hợp lệ", e);
    }
  }

  @Override
  public void insertAdjustment(
      TenantId tenant,
      PlanRow plan,
      UUID versionId,
      int versionNo,
      String changeReason,
      List<ItemRow> items,
      UUID actor) {
    jdbc.sql(
            "INSERT INTO task.plan_version (id, created_by, updated_by, period_id, period_bucket,"
                + " plan_id, version_no, status, change_reason) VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " 'NHAP', ?)")
        .params(
            versionId,
            actor,
            actor,
            plan.periodId(),
            plan.periodBucket(),
            plan.id(),
            versionNo,
            changeReason)
        .update();
    VersionRow created =
        new VersionRow(
            versionId,
            plan.id(),
            versionNo,
            PlanVersionStatus.NHAP,
            false,
            null,
            null,
            null,
            null,
            changeReason,
            0,
            0);
    replaceItems(tenant, plan, created, items, actor);
    jdbc.sql(
            "UPDATE task.plan SET current_version_id = ?, row_version = row_version + 1,"
                + " updated_by = ?, updated_at = now() WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ?")
        .params(versionId, actor, plan.periodBucket(), tenant.value(), plan.id())
        .update();
  }

  @Override
  public List<RemovedItem> removedItems(TenantId tenant, int periodBucket, UUID versionId) {
    String value =
        jdbc.sql(
                "SELECT removed_items::text FROM task.plan_version WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND id = ?")
            .params(periodBucket, tenant.value(), versionId)
            .query(String.class)
            .optional()
            .orElse(null);
    if (value == null) {
      return List.of();
    }
    try {
      return List.copyOf(json.readValue(value, REMOVED));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("removed_items không hợp lệ", e);
    }
  }

  @Override
  public void replaceRemovedItems(
      TenantId tenant, PlanRow plan, VersionRow version, List<RemovedItem> removed) {
    String value;
    try {
      value = removed.isEmpty() ? null : json.writeValueAsString(removed);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được removed_items", e);
    }
    jdbc.sql(
            "UPDATE task.plan_version SET removed_items = ?::jsonb WHERE period_bucket = ?"
                + " AND tenant_id = ? AND id = ?")
        .params(value, plan.periodBucket(), tenant.value(), version.id())
        .update();
  }

  private String write(List<Assignee> value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được co_assignees", e);
    }
  }

  @Override
  public List<PendingPlan> pendingApproval(TenantId tenant, int periodBucket, UUID periodId) {
    return jdbc.sql(
            "SELECT p.id, p.owner_unit_id, v.id AS version_id, v.created_by, v.row_version"
                + " FROM task.plan p JOIN task.plan_version v ON v.period_bucket = p.period_bucket"
                + " AND v.tenant_id = p.tenant_id AND v.plan_id = p.id AND v.version_no = 1"
                + " WHERE p.period_bucket = ? AND p.tenant_id = ? AND p.period_id = ?"
                + " AND v.status <> 'DA_DUYET' ORDER BY p.id")
        .params(periodBucket, tenant.value(), periodId)
        .query(
            (rs, n) ->
                new PendingPlan(
                    rs.getObject("id", UUID.class),
                    rs.getObject("owner_unit_id", UUID.class),
                    rs.getObject("version_id", UUID.class),
                    rs.getObject("created_by", UUID.class),
                    rs.getLong("row_version")))
        .list();
  }

  @Override
  public long markSubmitted(
      TenantId tenant, PlanRow plan, VersionRow version, byte[] contentHash, UUID actor) {
    return jdbc.sql(
            "UPDATE task.plan_version SET status = 'CHO_DUYET', submitted_at = now(),"
                + " content_hash = ?, return_reason = NULL, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " RETURNING row_version")
        .params(contentHash, actor, plan.periodBucket(), tenant.value(), version.id())
        .query(Long.class)
        .single();
  }

  @Override
  public long scoreItem(
      TenantId tenant,
      PlanRow plan,
      VersionRow version,
      UUID itemId,
      int difficulty,
      BigDecimal standardScore,
      byte[] contentHash,
      UUID actor) {
    jdbc.sql(
            "UPDATE task.plan_item SET difficulty = ?, standard_score = ?, updated_by = ?,"
                + " updated_at = now() WHERE period_bucket = ? AND tenant_id = ?"
                + " AND plan_version_id = ? AND id = ?")
        .params(
            difficulty,
            standardScore,
            actor,
            plan.periodBucket(),
            tenant.value(),
            version.id(),
            itemId)
        .update();
    return jdbc.sql(
            "UPDATE task.plan_version SET content_hash = ?, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " RETURNING row_version")
        .params(contentHash, actor, plan.periodBucket(), tenant.value(), version.id())
        .query(Long.class)
        .single();
  }

  @Override
  public UUID markReturned(
      TenantId tenant, PlanRow plan, VersionRow version, String comment, UUID actor) {
    return jdbc.sql(
            "UPDATE task.plan_version SET status = 'TRA_LAI', return_reason = ?,"
                + " review_comment = ?, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " RETURNING created_by")
        .params(comment, comment, actor, plan.periodBucket(), tenant.value(), version.id())
        .query(UUID.class)
        .single();
  }

  @Override
  public List<ReviewRow> awaitingReview(
      TenantId tenant, Collection<UUID> unitIds, UUID afterPlanId, int limit) {
    return awaiting(tenant, "p.owner_unit_id", unitIds, afterPlanId, limit);
  }

  @Override
  public List<ReviewRow> awaitingReviewOfPlans(
      TenantId tenant, Collection<UUID> planIds, UUID afterPlanId, int limit) {
    return awaiting(tenant, "p.id", planIds, afterPlanId, limit);
  }

  private List<ReviewRow> awaiting(
      TenantId tenant, String column, Collection<UUID> ids, UUID afterPlanId, int limit) {
    return jdbc.sql(
            "SELECT "
                + PLAN_COLS
                + ", "
                + VERSION_COLS
                + " FROM task.plan p JOIN task.plan_version v ON v.period_bucket = p.period_bucket"
                + " AND v.tenant_id = p.tenant_id AND v.plan_id = p.id"
                + " WHERE p.tenant_id = ? AND "
                + column
                + " = ANY (?::uuid[])"
                + " AND v.status = 'CHO_DUYET' AND p.id > ? ORDER BY p.id, v.version_no LIMIT ?")
        .params(
            tenant.value(),
            uuidArray(ids),
            afterPlanId == null ? new UUID(0L, 0L) : afterPlanId,
            limit)
        .query((rs, n) -> new ReviewRow(plan(rs, n), version(rs, n)))
        .list();
  }

  /**
   * Giữ chỗ bằng {@code task.idempotency_key} của người gọi hệ thống: khoá chính có ngày tạo nên
   * chặn trùng trong ngày, đúng phạm vi của tác vụ nhắc hạn (suy luận, không thêm bảng mới).
   */
  @Override
  public boolean claimNotice(TenantId tenant, String key) {
    return jdbc.sql(
                "INSERT INTO task.idempotency_key (tenant_id, actor_id, idem_key, request_hash,"
                    + " status, response_code) VALUES (?, ?, ?, sha256(convert_to(?, 'UTF8')),"
                    + " 'XONG', 200) ON CONFLICT DO NOTHING")
            .params(tenant.value(), SYSTEM_ACTOR, key, key)
            .update()
        == 1;
  }

  private static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }
}
