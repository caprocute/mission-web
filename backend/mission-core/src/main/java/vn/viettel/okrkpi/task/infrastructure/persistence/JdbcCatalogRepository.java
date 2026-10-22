package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository;
import vn.viettel.okrkpi.task.domain.catalog.AccentFold;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogRules;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/**
 * {@link CatalogRepository} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS theo {@code
 * app.tenant_id}); câu lệnh vẫn lọc {@code tenant_id} tường minh. Cột trùng nghĩa của TL-03 và hợp
 * đồng được ghi cả hai: {@code version} = {@code version_no}, {@code standard_score} = {@code
 * base_score}, {@code task_group} = {@code default_group}.
 */
public class JdbcCatalogRepository implements CatalogRepository {

  private static final String CATALOG_COLUMNS =
      "id, code, name, scope, position_id, org_unit_id, version_no, source, status,"
          + " parent_catalog_id, row_version";

  private static final String ITEM_COLUMNS =
      "id, work_catalog_id, code, name, product_name, unit, difficulty,"
          + " coalesce(base_score, standard_score) AS base_score, quality_standard,"
          + " evidence_requirement, coalesce(default_group, task_group) AS default_group,"
          + " position_id, standard_days, status, row_version";

  /**
   * Chuỗi kế thừa từ các phiên bản gốc (điều kiện {@code %s}) lên danh mục cấp trên, chặn vòng bằng
   * đường đi và độ sâu; mục riêng che mục kế thừa cùng mã.
   */
  private static final String VISIBLE =
      """
      WITH RECURSIVE chain(root, src, depth, path) AS (
        SELECT w.id, w.id, 0, ARRAY[w.id]
          FROM task.work_catalog w
         WHERE w.tenant_id = :t AND %s
        UNION ALL
        SELECT c.root, p.parent_catalog_id, c.depth + 1, c.path || p.parent_catalog_id
          FROM chain c
          JOIN task.work_catalog p ON p.tenant_id = :t AND p.id = c.src
         WHERE p.parent_catalog_id IS NOT NULL
           AND NOT (p.parent_catalog_id = ANY (c.path))
           AND c.depth < %d
      ), visible AS (
        SELECT DISTINCT ON (c.root, i.code)
               i.id, i.work_catalog_id, i.code, i.name, i.product_name, i.unit, i.difficulty,
               coalesce(i.base_score, i.standard_score) AS base_score, i.quality_standard,
               i.evidence_requirement, coalesce(i.default_group, i.task_group) AS default_group,
               i.position_id, i.standard_days, i.status, i.row_version,
               c.root AS root_id, coalesce(i.position_id, rw.position_id) AS effective_position
          FROM chain c
          JOIN task.catalog_item i ON i.tenant_id = :t AND i.work_catalog_id = c.src
          JOIN task.work_catalog rw ON rw.tenant_id = :t AND rw.id = c.root
         ORDER BY c.root, i.code, c.depth
      )
      """;

  private final JdbcClient jdbc;

  public JdbcCatalogRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<WorkCatalog> findCatalog(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + CATALOG_COLUMNS
                + " FROM task.work_catalog WHERE tenant_id = :t AND id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query(JdbcCatalogRepository::catalog)
        .optional();
  }

  @Override
  public List<WorkCatalog> listCatalogs(
      TenantId tenant, UUID orgUnit, CatalogKey after, int fetch) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(CATALOG_COLUMNS)
            .append(" FROM (SELECT ")
            .append(CATALOG_COLUMNS)
            .append(
                ", CASE status WHEN 'HIEU_LUC' THEN 0 WHEN 'NHAP' THEN 1 ELSE 2 END AS rk"
                    + " FROM task.work_catalog WHERE tenant_id = :t");
    if (orgUnit != null) {
      sql.append(" AND org_unit_id = :unit");
    }
    sql.append(") x");
    if (after != null) {
      sql.append(
          " WHERE (x.rk, x.name COLLATE \"C\", -x.version_no, x.id)"
              + " > (:rk, CAST(:name AS varchar) COLLATE \"C\", :negv, :id)");
    }
    sql.append(" ORDER BY x.rk, x.name COLLATE \"C\", x.version_no DESC, x.id LIMIT :n");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("t", tenant.value()).param("n", fetch);
    if (orgUnit != null) {
      spec = spec.param("unit", orgUnit);
    }
    if (after != null) {
      spec =
          spec.param("rk", after.rank())
              .param("name", after.name())
              .param("negv", -after.version())
              .param("id", after.id());
    }
    return spec.query(JdbcCatalogRepository::catalog).list();
  }

  @Override
  public Optional<WorkCatalog> findLatest(TenantId tenant, String code, CatalogStatus status) {
    return jdbc.sql(
            "SELECT "
                + CATALOG_COLUMNS
                + " FROM task.work_catalog WHERE tenant_id = :t AND code = :code"
                + " AND status = :s ORDER BY version_no DESC LIMIT 1")
        .param("t", tenant.value())
        .param("code", code)
        .param("s", status.name())
        .query(JdbcCatalogRepository::catalog)
        .optional();
  }

  @Override
  public int maxVersion(TenantId tenant, String code) {
    return jdbc.sql(
            "SELECT coalesce(max(version_no), 0) FROM task.work_catalog"
                + " WHERE tenant_id = :t AND code = :code")
        .param("t", tenant.value())
        .param("code", code)
        .query(Integer.class)
        .single();
  }

  @Override
  public void insertCatalog(TenantId tenant, UUID actor, WorkCatalog c) {
    try {
      jdbc.sql(
              """
              INSERT INTO task.work_catalog
                (id, tenant_id, created_by, updated_by, row_version, code, name, scope,
                 position_id, org_unit_id, version, version_no, source, status, parent_catalog_id)
              VALUES (:id, :t, :a, :a, :rv, :code, :name, :scope, :pos, :unit, :v, :v, :src,
                      :status, :parent)
              """)
          .param("id", c.id())
          .param("t", tenant.value())
          .param("a", actor)
          .param("rv", c.rowVersion())
          .param("code", c.code())
          .param("name", c.name())
          .param("scope", c.scope())
          .param("pos", c.positionId())
          .param("unit", c.orgUnitId())
          .param("v", c.version())
          .param("src", c.source())
          .param("status", c.status().name())
          .param("parent", c.parentId())
          .update();
    } catch (DataIntegrityViolationException e) {
      if (constraint(e, "uq_work_catalog__code_version")) {
        throw new DuplicateVersionException(e);
      }
      throw e;
    }
  }

  @Override
  public boolean changeStatus(
      TenantId tenant, UUID actor, UUID id, CatalogStatus status, int expectedRowVersion) {
    return jdbc.sql(
                """
                UPDATE task.work_catalog
                   SET status = :s, updated_at = now(), updated_by = :a,
                       row_version = row_version + 1
                 WHERE tenant_id = :t AND id = :id AND row_version = :rv
                """)
            .param("s", status.name())
            .param("a", actor)
            .param("t", tenant.value())
            .param("id", id)
            .param("rv", expectedRowVersion)
            .update()
        == 1;
  }

  @Override
  public int replaceStatus(
      TenantId tenant, UUID actor, String code, CatalogStatus from, CatalogStatus to) {
    return jdbc.sql(
            """
            UPDATE task.work_catalog
               SET status = :to, updated_at = now(), updated_by = :a,
                   row_version = row_version + 1
             WHERE tenant_id = :t AND code = :code AND status = :from
            """)
        .param("to", to.name())
        .param("a", actor)
        .param("t", tenant.value())
        .param("code", code)
        .param("from", from.name())
        .update();
  }

  @Override
  public void changeParent(TenantId tenant, UUID actor, UUID id, UUID parentId) {
    jdbc.sql(
            """
            UPDATE task.work_catalog
               SET parent_catalog_id = :p, updated_at = now(), updated_by = :a,
                   row_version = row_version + 1
             WHERE tenant_id = :t AND id = :id
            """)
        .param("p", parentId)
        .param("a", actor)
        .param("t", tenant.value())
        .param("id", id)
        .update();
  }

  @Override
  public Optional<UUID> parentOf(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT parent_catalog_id FROM task.work_catalog WHERE tenant_id = :t AND id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
        .optional()
        .flatMap(o -> o);
  }

  @Override
  public Map<UUID, String> catalogNames(TenantId tenant, Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT id, name FROM task.work_catalog WHERE tenant_id = :t AND id IN (:ids)")
        .param("t", tenant.value())
        .param("ids", List.copyOf(ids))
        .query(
            (ResultSet rs) -> {
              out.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
    return out;
  }

  @Override
  public Map<UUID, String> positionLabels(TenantId tenant, Collection<UUID> positionIds) {
    Map<UUID, String> out = new HashMap<>();
    if (positionIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT DISTINCT ON (position_id) position_id, name
              FROM task.work_catalog
             WHERE tenant_id = :t AND position_id IN (:ids)
             ORDER BY position_id, (status = 'HIEU_LUC') DESC, version_no DESC
            """)
        .param("t", tenant.value())
        .param("ids", List.copyOf(positionIds))
        .query(
            (ResultSet rs) -> {
              out.put(rs.getObject("position_id", UUID.class), rs.getString("name"));
            });
    return out;
  }

  @Override
  public List<VisibleItem> visibleItems(TenantId tenant, UUID catalogId) {
    return jdbc.sql(
            VISIBLE.formatted("w.id = :root", CatalogRules.MAX_DEPTH)
                + " SELECT * FROM visible ORDER BY code COLLATE \"C\", id")
        .param("t", tenant.value())
        .param("root", catalogId)
        .query(JdbcCatalogRepository::visible)
        .list();
  }

  @Override
  public List<VisibleItem> applicableItems(
      TenantId tenant,
      UUID orgUnit,
      Set<UUID> positions,
      String foldedQuery,
      String afterCode,
      UUID afterId,
      int fetch) {
    String roots = "w.status = 'HIEU_LUC'" + (orgUnit == null ? "" : " AND w.org_unit_id = :unit");
    StringBuilder sql =
        new StringBuilder(VISIBLE.formatted(roots, CatalogRules.MAX_DEPTH))
            .append(" SELECT * FROM (SELECT DISTINCT ON (id) * FROM visible")
            .append(" WHERE status = 'AP_DUNG'");
    if (positions != null) {
      sql.append(" AND effective_position IN (:positions)");
    }
    if (foldedQuery != null) {
      sql.append(
          " AND (translate(lower(name), :from, :to) LIKE :q ESCAPE '\\'"
              + " OR translate(lower(code), :from, :to) LIKE :q ESCAPE '\\')");
    }
    sql.append(" ORDER BY id) x");
    if (afterCode != null) {
      sql.append(
          " WHERE (x.code COLLATE \"C\" > CAST(:ac AS varchar) COLLATE \"C\""
              + " OR (x.code = :ac AND x.id > :aid))");
    }
    sql.append(" ORDER BY x.code COLLATE \"C\", x.id LIMIT :n");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("t", tenant.value()).param("n", fetch);
    if (orgUnit != null) {
      spec = spec.param("unit", orgUnit);
    }
    if (positions != null) {
      spec = spec.param("positions", List.copyOf(positions));
    }
    if (foldedQuery != null) {
      spec =
          spec.param("from", AccentFold.FROM)
              .param("to", AccentFold.TO)
              .param("q", "%" + likeEscape(foldedQuery) + "%");
    }
    if (afterCode != null) {
      spec = spec.param("ac", afterCode).param("aid", afterId);
    }
    return spec.query(JdbcCatalogRepository::visible).list();
  }

  @Override
  public Optional<CatalogItem> findItem(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + ITEM_COLUMNS + " FROM task.catalog_item WHERE tenant_id = :t AND id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query((rs, n) -> item(rs))
        .optional();
  }

  @Override
  public Optional<CatalogItem> findItemByCode(TenantId tenant, UUID catalogId, String code) {
    return jdbc.sql(
            "SELECT "
                + ITEM_COLUMNS
                + " FROM task.catalog_item"
                + " WHERE tenant_id = :t AND work_catalog_id = :c AND code = :code")
        .param("t", tenant.value())
        .param("c", catalogId)
        .param("code", code)
        .query((rs, n) -> item(rs))
        .optional();
  }

  @Override
  public List<String> itemCodes(TenantId tenant, UUID catalogId) {
    return jdbc.sql(
            "SELECT code FROM task.catalog_item WHERE tenant_id = :t AND work_catalog_id = :c")
        .param("t", tenant.value())
        .param("c", catalogId)
        .query(String.class)
        .list();
  }

  @Override
  public void insertItem(TenantId tenant, UUID actor, CatalogItem i) {
    try {
      jdbc.sql(
              """
              INSERT INTO task.catalog_item
                (id, tenant_id, created_by, updated_by, row_version, work_catalog_id, code, name,
                 product_name, unit, difficulty, standard_score, base_score, quality_standard,
                 evidence_requirement, task_group, default_group, position_id, standard_days,
                 status)
              VALUES (:id, :t, :a, :a, :rv, :c, :code, :name, :product, :unit, :difficulty,
                      :score, :score, :quality, :evidence, :grp, :grp, :pos, :days, :status)
              """)
          .param("id", i.id())
          .param("t", tenant.value())
          .param("a", actor)
          .param("rv", i.rowVersion())
          .param("c", i.catalogId())
          .params(itemParams(i))
          .update();
    } catch (DataIntegrityViolationException e) {
      throw translateItem(e);
    }
  }

  @Override
  public boolean reviseItem(TenantId tenant, UUID actor, CatalogItem i, int expectedRowVersion) {
    try {
      return jdbc.sql(
                  """
                  UPDATE task.catalog_item
                     SET code = :code, name = :name, product_name = :product, unit = :unit,
                         difficulty = :difficulty, standard_score = :score, base_score = :score,
                         quality_standard = :quality, evidence_requirement = :evidence,
                         task_group = :grp, default_group = :grp, position_id = :pos,
                         standard_days = :days, status = :status, updated_at = now(),
                         updated_by = :a, row_version = row_version + 1
                   WHERE tenant_id = :t AND id = :id AND row_version = :rv
                  """)
              .param("a", actor)
              .param("t", tenant.value())
              .param("id", i.id())
              .param("rv", expectedRowVersion)
              .params(itemParams(i))
              .update()
          == 1;
    } catch (DataIntegrityViolationException e) {
      throw translateItem(e);
    }
  }

  @Override
  public int copyItems(TenantId tenant, UUID actor, UUID from, UUID to) {
    return jdbc.sql(
            """
            INSERT INTO task.catalog_item
              (id, tenant_id, created_by, updated_by, work_catalog_id, code, name, product_name,
               unit, difficulty, standard_score, quality_standard, evidence_requirement,
               task_group, data_source, position_id, default_group, standard_days, base_score,
               status)
            SELECT gen_random_uuid(), tenant_id, :a, :a, :to, code, name, product_name, unit,
                   difficulty, standard_score, quality_standard, evidence_requirement, task_group,
                   data_source, position_id, default_group, standard_days, base_score, status
              FROM task.catalog_item
             WHERE tenant_id = :t AND work_catalog_id = :from
            """)
        .param("a", actor)
        .param("to", to)
        .param("t", tenant.value())
        .param("from", from)
        .update();
  }

  @Override
  public List<PeriodUse> usage(TenantId tenant, Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT catalog_item_id, period_id, count(*) AS n
              FROM task.task
             WHERE tenant_id = :t AND catalog_item_id IN (:ids)
             GROUP BY catalog_item_id, period_id
            UNION ALL
            SELECT catalog_item_id, period_id, count(*) AS n
              FROM task.plan_item
             WHERE tenant_id = :t AND catalog_item_id IN (:ids)
             GROUP BY catalog_item_id, period_id
            """)
        .param("t", tenant.value())
        .param("ids", List.copyOf(itemIds))
        .query(
            (rs, n) ->
                new PeriodUse(
                    rs.getObject("catalog_item_id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getInt("n")))
        .list();
  }

  private static Map<String, Object> itemParams(CatalogItem i) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", i.code());
    p.put("name", i.name());
    p.put("product", i.productName());
    p.put("unit", i.unit());
    p.put("difficulty", i.difficulty());
    p.put("score", i.baseScore());
    p.put("quality", i.qualityCriteria());
    p.put("evidence", i.evidenceRequired());
    p.put("grp", i.group());
    p.put("pos", i.positionId());
    p.put("days", i.standardDays());
    p.put("status", i.status());
    return p;
  }

  private static RuntimeException translateItem(DataIntegrityViolationException e) {
    return constraint(e, "uq_catalog_item__code") ? new DuplicateCodeException(e) : e;
  }

  private static boolean constraint(DataIntegrityViolationException e, String name) {
    String message = e.getMostSpecificCause().getMessage();
    return message != null && message.contains(name);
  }

  private static String likeEscape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static WorkCatalog catalog(ResultSet rs, int n) throws SQLException {
    return new WorkCatalog(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("scope"),
        rs.getObject("position_id", UUID.class),
        rs.getObject("org_unit_id", UUID.class),
        rs.getInt("version_no"),
        rs.getString("source"),
        CatalogStatus.valueOf(rs.getString("status")),
        rs.getObject("parent_catalog_id", UUID.class),
        rs.getInt("row_version"));
  }

  private static CatalogItem item(ResultSet rs) throws SQLException {
    int days = rs.getInt("standard_days");
    Integer standardDays = rs.wasNull() ? null : days;
    return new CatalogItem(
        rs.getObject("id", UUID.class),
        rs.getObject("work_catalog_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("product_name"),
        rs.getString("unit"),
        rs.getInt("difficulty"),
        rs.getBigDecimal("base_score"),
        rs.getString("quality_standard"),
        rs.getString("evidence_requirement"),
        rs.getString("default_group"),
        rs.getObject("position_id", UUID.class),
        standardDays,
        rs.getString("status"),
        rs.getInt("row_version"));
  }

  private static VisibleItem visible(ResultSet rs, int n) throws SQLException {
    return new VisibleItem(
        item(rs),
        rs.getObject("root_id", UUID.class),
        rs.getObject("effective_position", UUID.class));
  }
}
