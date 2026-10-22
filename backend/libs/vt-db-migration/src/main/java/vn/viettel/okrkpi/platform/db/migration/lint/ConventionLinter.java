package vn.viettel.okrkpi.platform.db.migration.lint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;

/**
 * Lint quy ước lược đồ sau khi đã áp toàn bộ migration, bằng cách đọc {@code pg_catalog} (TL-03
 * "Quy ước đặt tên", "Kiểu dữ liệu chuẩn", "Phân loại dữ liệu C0 đến C3").
 *
 * <p>Bỏ qua: bảng Flowable ({@code ACT_*}, {@code FLW_*}, do engine tự tạo), bảng lịch sử Flyway,
 * phân vùng con (đối tượng của phân vùng con do PostgreSQL sinh từ bảng cha nên kiểm ở bảng cha),
 * đối tượng thuộc extension.
 */
public final class ConventionLinter {

  private static final Pattern FLOWABLE_TABLE = Pattern.compile("(?i)(act|flw)_.*");

  /** Kiểu chuẩn theo TL-03 "Kiểu dữ liệu chuẩn", ở dạng {@code format_type()} của PostgreSQL. */
  private static final Set<String> STANDARD_TYPES =
      Set.of(
          "uuid",
          "text",
          "timestamp with time zone",
          "date",
          "daterange",
          "tstzrange",
          "numeric(6,3)",
          "numeric(6,4)",
          "numeric(18,4)",
          "integer",
          "bigint",
          "boolean",
          "jsonb",
          "bytea",
          "vector(1024)");

  private static final Pattern VARCHAR_WITH_LENGTH = Pattern.compile("character varying\\(\\d+\\)");

  private ConventionLinter() {}

  /** Lint một schema, trả danh sách vi phạm (rỗng khi đạt). */
  public static List<Violation> lint(Connection connection, String schema) throws SQLException {
    SchemaNames.require(schema);
    List<Violation> violations = new ArrayList<>();
    Map<Long, String> tables = tables(connection, schema);
    lintColumns(connection, schema, tables, violations);
    lintConstraints(connection, schema, tables, violations);
    lintIndexes(connection, schema, tables, violations);
    lintPolicies(connection, schema, tables, violations);
    lintTriggers(connection, schema, tables, violations);
    lintFunctions(connection, schema, violations);
    return violations;
  }

  /** Các cột cần loại khỏi CDC phân tích, dạng {@code schema.bảng.cột} (đầu vào DV-15-02). */
  public static List<String> analyticsCdcExcludedColumns(Connection connection, String schema)
      throws SQLException {
    Map<Long, String> tables = tables(connection, schema);
    List<String> excluded = new ArrayList<>();
    for (Column c : columns(connection, schema)) {
      if (!tables.containsKey(c.tableOid())) {
        continue;
      }
      ColumnClassification.parse(c.comment())
          .filter(ColumnClassification::excludedFromAnalyticsCdc)
          .ifPresent(cls -> excluded.add(schema + "." + c.table() + "." + c.name()));
    }
    excluded.sort(String::compareTo);
    return excluded;
  }

  /** Bảng thường và bảng phân vùng cha cần lint, theo oid. */
  private static Map<Long, String> tables(Connection connection, String schema)
      throws SQLException {
    String sql =
        """
        select c.oid, c.relname
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = ?
           and c.relkind in ('r', 'p')
           and not c.relispartition
           and c.relname <> ?
           and not exists (select 1 from pg_depend d
                            where d.objid = c.oid and d.classid = 'pg_class'::regclass
                              and d.deptype = 'e')
        """;
    Map<Long, String> tables = new HashMap<>();
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      st.setString(2, SchemaMigrator.HISTORY_TABLE);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(2);
          if (!FLOWABLE_TABLE.matcher(name).matches()) {
            tables.put(rs.getLong(1), name);
          }
        }
      }
    }
    return tables;
  }

  private record Column(
      long tableOid,
      String table,
      String name,
      String type,
      boolean notNull,
      String defaultExpr,
      String comment) {}

  private static List<Column> columns(Connection connection, String schema) throws SQLException {
    String sql =
        """
        select a.attrelid, c.relname, a.attname, format_type(a.atttypid, a.atttypmod),
               a.attnotnull, pg_get_expr(d.adbin, d.adrelid), col_description(a.attrelid, a.attnum)
          from pg_attribute a
          join pg_class c on c.oid = a.attrelid
          join pg_namespace n on n.oid = c.relnamespace
          left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
         where n.nspname = ? and a.attnum > 0 and not a.attisdropped
           and c.relkind in ('r', 'p')
         order by c.relname, a.attnum
        """;
    List<Column> columns = new ArrayList<>();
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          columns.add(
              new Column(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getBoolean(5),
                  rs.getString(6),
                  rs.getString(7)));
        }
      }
    }
    return columns;
  }

  private static void lintColumns(
      Connection connection, String schema, Map<Long, String> tables, List<Violation> out)
      throws SQLException {
    List<Column> columns = columns(connection, schema);
    Map<Long, Set<String>> columnNames = new HashMap<>();
    for (Column c : columns) {
      columnNames.computeIfAbsent(c.tableOid(), k -> new HashSet<>()).add(c.name());
    }
    for (String table : tables.values()) {
      lowercase(schema + "." + table, table, out);
    }
    for (Column c : columns) {
      if (!tables.containsKey(c.tableOid())) {
        continue;
      }
      String object = schema + "." + c.table() + "." + c.name();
      lowercase(object, c.name(), out);
      Optional<ColumnClassification> cls = ColumnClassification.parse(c.comment());
      boolean typeException = cls.flatMap(ColumnClassification::typeException).isPresent();
      if (!typeException
          && !STANDARD_TYPES.contains(c.type())
          && !VARCHAR_WITH_LENGTH.matcher(c.type()).matches()) {
        out.add(
            new Violation(
                LintRule.TYPE_STANDARD,
                object,
                "kiểu '"
                    + c.type()
                    + "' không thuộc danh sách kiểu chuẩn TL-03 (varchar phải có độ dài; dùng"
                    + " timestamptz, numeric(6,3)/(6,4)/(18,4)...); nếu bắt buộc, ghi lý do ở khoá"
                    + " \"type_exception\" của chú thích cột"));
      }
      if (c.name().startsWith("is_") || c.name().startsWith("has_")) {
        boolean ok = "boolean".equals(c.type()) && c.notNull() && "false".equals(c.defaultExpr());
        if (!ok) {
          out.add(
              new Violation(
                  LintRule.FLAG_COLUMN, object, "cột cờ phải là boolean NOT NULL DEFAULT false"));
        }
      }
      if (c.name().endsWith("_at") && !"timestamp with time zone".equals(c.type())) {
        out.add(
            new Violation(
                LintRule.TIMESTAMP_COLUMN,
                object,
                "cột thời điểm hậu tố _at phải là timestamptz, đang là " + c.type()));
      }
      if (cls.isEmpty()) {
        out.add(
            new Violation(
                LintRule.CLASSIFICATION_COMMENT,
                object,
                "thiếu chú thích phân loại hợp lệ: COMMENT ON COLUMN "
                    + object
                    + " IS '{\"cls\":\"C1\",\"desc\":\"...\"}' (cls thuộc C0..C3)"));
      } else if ("C2".equals(cls.get().cls())) {
        List<String> problems = new ArrayList<>();
        if (!"bytea".equals(c.type())) {
          problems.add("kiểu phải là bytea");
        }
        if (!c.name().endsWith("_enc")) {
          problems.add("tên phải có hậu tố _enc");
        }
        if (!columnNames.getOrDefault(c.tableOid(), Set.of()).contains("dek_id")) {
          problems.add("bảng phải có cột dek_id");
        }
        if (!problems.isEmpty()) {
          out.add(
              new Violation(
                  LintRule.CLASSIFICATION_C2_ENCRYPTED,
                  object,
                  "cột C2 phải mã hoá trường: " + String.join(", ", problems)));
        }
      }
    }
  }

  private static void lintConstraints(
      Connection connection, String schema, Map<Long, String> tables, List<Violation> out)
      throws SQLException {
    String sql =
        """
        select con.conname, con.contype, con.conrelid, fc.relname
          from pg_constraint con
          join pg_namespace n on n.oid = con.connamespace
          left join pg_class fc on fc.oid = con.confrelid
         where n.nspname = ? and con.contype in ('p', 'f', 'u', 'c', 'x')
           and con.conrelid <> 0 and con.conparentid = 0
        """;
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String table = tables.get(rs.getLong(3));
          if (table == null) {
            continue;
          }
          String name = rs.getString(1);
          String object = schema + "." + table + " ràng buộc " + name;
          lowercase(object, name, out);
          switch (rs.getString(2)) {
            case "p" -> expectExact(LintRule.NAMING_PRIMARY_KEY, object, name, "pk_" + table, out);
            case "f" ->
                expectPrefix(
                    LintRule.NAMING_FOREIGN_KEY,
                    object,
                    name,
                    "fk_" + table + "__" + rs.getString(4),
                    out);
            case "u" ->
                expectPrefix(LintRule.NAMING_UNIQUE, object, name, "uq_" + table + "__", out);
            case "c" ->
                expectPrefix(LintRule.NAMING_CHECK, object, name, "ck_" + table + "__", out);
            case "x" ->
                expectPrefix(LintRule.NAMING_EXCLUSION, object, name, "ex_" + table + "__", out);
            default -> {
              // contype khác đã bị lọc ở câu truy vấn.
            }
          }
        }
      }
    }
  }

  private static void lintIndexes(
      Connection connection, String schema, Map<Long, String> tables, List<Violation> out)
      throws SQLException {
    String sql =
        """
        select i.relname, x.indrelid
          from pg_index x
          join pg_class i on i.oid = x.indexrelid
          join pg_namespace n on n.oid = i.relnamespace
         where n.nspname = ?
           and not exists (select 1 from pg_constraint con where con.conindid = x.indexrelid)
        """;
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String table = tables.get(rs.getLong(2));
          if (table == null) {
            continue;
          }
          String name = rs.getString(1);
          String object = schema + "." + table + " chỉ mục " + name;
          lowercase(object, name, out);
          expectPrefix(LintRule.NAMING_INDEX, object, name, "ix_" + table + "__", out);
        }
      }
    }
  }

  private static void lintPolicies(
      Connection connection, String schema, Map<Long, String> tables, List<Violation> out)
      throws SQLException {
    String sql =
        """
        select p.polname, p.polrelid
          from pg_policy p
          join pg_class c on c.oid = p.polrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = ?
        """;
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String table = tables.get(rs.getLong(2));
          if (table == null) {
            continue;
          }
          String name = rs.getString(1);
          String object = schema + "." + table + " chính sách " + name;
          lowercase(object, name, out);
          expectPrefix(LintRule.NAMING_POLICY, object, name, "rls_" + table + "__", out);
        }
      }
    }
  }

  private static void lintTriggers(
      Connection connection, String schema, Map<Long, String> tables, List<Violation> out)
      throws SQLException {
    String sql =
        """
        select t.tgname, t.tgrelid
          from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = ? and not t.tgisinternal and t.tgparentid = 0
        """;
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String table = tables.get(rs.getLong(2));
          if (table == null) {
            continue;
          }
          String name = rs.getString(1);
          String object = schema + "." + table + " trigger " + name;
          lowercase(object, name, out);
          expectPrefix(LintRule.NAMING_TRIGGER, object, name, "tg_" + table + "__", out);
        }
      }
    }
  }

  private static void lintFunctions(Connection connection, String schema, List<Violation> out)
      throws SQLException {
    String sql =
        """
        select p.proname
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = ?
           and not exists (select 1 from pg_depend d
                            where d.objid = p.oid and d.classid = 'pg_proc'::regclass
                              and d.deptype = 'e')
        """;
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, schema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          String object = schema + "." + name + "()";
          lowercase(object, name, out);
          expectPrefix(LintRule.NAMING_FUNCTION, object, name, "fn_", out);
        }
      }
    }
  }

  private static void lowercase(String object, String identifier, List<Violation> out) {
    if (!identifier.equals(identifier.toLowerCase(Locale.ROOT))) {
      out.add(
          new Violation(
              LintRule.IDENTIFIER_LOWERCASE,
              object,
              "định danh '" + identifier + "' có chữ hoa; dùng snake_case chữ thường"));
    }
  }

  private static void expectExact(
      LintRule rule, String object, String actual, String expected, List<Violation> out) {
    if (!actual.equals(expected)) {
      out.add(new Violation(rule, object, "tên phải là '" + expected + "'"));
    }
  }

  private static void expectPrefix(
      LintRule rule, String object, String actual, String prefix, List<Violation> out) {
    if (!actual.startsWith(prefix)) {
      out.add(new Violation(rule, object, "tên phải bắt đầu bằng '" + prefix + "'"));
    }
  }
}
