package vn.viettel.okrkpi.shared.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bảng ánh xạ thuộc tính {@code input.resource.<field>} trong Rego sang tên cột SQL hợp lệ và cấu
 * hình bảng đóng cây đơn vị {@code org_replica.unit_closure_v} (NEN-014, TL-01, TL-03).
 */
public final class ResourceColumnMapping {

  private static final Pattern SAFE_COLUMN_RE =
      Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$");

  private final Map<String, ColumnSpec> columns;
  private final String ownerUnitColumn;
  private final String unitClosureTable;
  private final String defaultTree;
  private final boolean uuidIds;

  private ResourceColumnMapping(
      Map<String, ColumnSpec> columns,
      String ownerUnitColumn,
      String unitClosureTable,
      String defaultTree,
      boolean uuidIds) {
    this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    this.ownerUnitColumn = validateColumn(ownerUnitColumn);
    this.unitClosureTable = validateColumn(unitClosureTable);
    this.defaultTree = (defaultTree == null || defaultTree.isBlank()) ? "NHA_NUOC" : defaultTree;
    this.uuidIds = uuidIds;
  }

  /**
   * Cấu hình mặc định cho bảng nghiệp vụ chuẩn (ví dụ {@code task.task} hoặc {@code okr.objective})
   * với bí danh bảng {@code t} và khóa UUID.
   */
  public static ResourceColumnMapping defaultForAlias(String tableAlias, String tree) {
    String prefix = (tableAlias == null || tableAlias.isBlank()) ? "" : tableAlias.trim() + ".";
    return builder()
        .ownerUnitColumn(prefix + "owner_unit_id")
        .unitClosureTable("org_replica.unit_closure_v")
        .defaultTree(tree)
        .uuidIds(true)
        .mapUuid("tenant_id", prefix + "tenant_id")
        .mapUuid("owner_unit_id", prefix + "owner_unit_id")
        .mapUuid("owner_id", prefix + "created_by")
        .mapUuid("assignee_id", prefix + "lead_id")
        .mapUuid("designated_id", prefix + "lead_id")
        .mapString("flow", prefix + "org_tree")
        .mapString("classification", prefix + "classification")
        .mapString("status", prefix + "status")
        .build();
  }

  public Optional<ColumnSpec> columnFor(String resourceField) {
    return Optional.ofNullable(columns.get(resourceField));
  }

  public String ownerUnitColumn() {
    return ownerUnitColumn;
  }

  public String unitClosureTable() {
    return unitClosureTable;
  }

  public String defaultTree() {
    return defaultTree;
  }

  public boolean uuidIds() {
    return uuidIds;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static String validateColumn(String col) {
    Objects.requireNonNull(col, "column name must not be null");
    if (!SAFE_COLUMN_RE.matcher(col).matches()) {
      throw new IllegalArgumentException("Tên cột/bảng SQL không hợp lệ: " + col);
    }
    return col;
  }

  public record ColumnSpec(String sqlColumn, boolean uuidType) {
    public ColumnSpec {
      validateColumn(sqlColumn);
    }
  }

  public static final class Builder {
    private final Map<String, ColumnSpec> columns = new LinkedHashMap<>();
    private String ownerUnitColumn = "owner_unit_id";
    private String unitClosureTable = "org_replica.unit_closure_v";
    private String defaultTree = "NHA_NUOC";
    private boolean uuidIds = true;

    public Builder ownerUnitColumn(String ownerUnitColumn) {
      this.ownerUnitColumn = validateColumn(ownerUnitColumn);
      return this;
    }

    public Builder unitClosureTable(String unitClosureTable) {
      this.unitClosureTable = validateColumn(unitClosureTable);
      return this;
    }

    public Builder defaultTree(String defaultTree) {
      this.defaultTree = defaultTree;
      return this;
    }

    public Builder uuidIds(boolean uuidIds) {
      this.uuidIds = uuidIds;
      return this;
    }

    public Builder mapUuid(String resourceField, String sqlColumn) {
      columns.put(Objects.requireNonNull(resourceField), new ColumnSpec(sqlColumn, true));
      return this;
    }

    public Builder mapString(String resourceField, String sqlColumn) {
      columns.put(Objects.requireNonNull(resourceField), new ColumnSpec(sqlColumn, false));
      return this;
    }

    public ResourceColumnMapping build() {
      return new ResourceColumnMapping(
          columns, ownerUnitColumn, unitClosureTable, defaultTree, uuidIds);
    }
  }
}
