package vn.viettel.okrkpi.platform.tenanttest.analytics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NEN-035, ADR-14, TL-03 "CDC phân tích sang ClickHouse": dưới ngữ cảnh bảo mật của tenant A, mọi
 * bảng phân tích chỉ trả dòng của A (kể cả khi câu SQL lọc thẳng theo tenant B), và bảng phân tích
 * không chứa cột C2 dạng rõ.
 */
public final class AnalyticsTenantLeakProbe {

  private static final Pattern ENCRYPTED_COLUMN = Pattern.compile("\\b([a-z][a-z0-9_]*)_enc\\b");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

  private final TenantScopedQuery scoped;
  private final String tenantColumn;

  public AnalyticsTenantLeakProbe(TenantScopedQuery scoped) {
    this(scoped, "tenant_id");
  }

  public AnalyticsTenantLeakProbe(TenantScopedQuery scoped, String tenantColumn) {
    this.scoped = scoped;
    this.tenantColumn = identifier(tenantColumn);
  }

  /** Ném {@link AssertionError} liệt kê mọi bảng lộ dòng của {@code otherTenant}. */
  public void assertTablesIsolated(
      Collection<String> tables, String ownTenant, String otherTenant) {
    List<String> violations = new ArrayList<>();
    for (String table : tables) {
      String t = identifier(table);
      List<Map<String, String>> tenants =
          scoped.query(ownTenant, "SELECT DISTINCT " + tenantColumn + " AS tenant FROM " + t);
      if (tenants.isEmpty()) {
        violations.add(t + ": không có dòng nào của " + ownTenant + " (thiếu dữ liệu mồi)");
      }
      for (Map<String, String> row : tenants) {
        if (!ownTenant.equals(row.get("tenant"))) {
          violations.add(
              t + ": dưới ngữ cảnh " + ownTenant + " thấy dòng của " + row.get("tenant"));
        }
      }
      List<Map<String, String>> direct =
          scoped.query(
              ownTenant,
              "SELECT count() AS n FROM "
                  + t
                  + " WHERE "
                  + tenantColumn
                  + " = '"
                  + otherTenant.replace("'", "")
                  + "'");
      String n = direct.isEmpty() ? "0" : direct.get(0).getOrDefault("n", "0");
      if (!"0".equals(n)) {
        violations.add(t + ": lọc thẳng theo " + otherTenant + " trả " + n + " dòng");
      }
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "RÒ RỈ TENANT ở kho phân tích:\n  " + String.join("\n  ", violations));
    }
  }

  /**
   * Ném {@link AssertionError} khi bảng phân tích có cột trùng tên một trường C2 ở dạng rõ ({@code
   * columnsByTable}: bảng sang danh sách cột).
   */
  public static void assertNoPlaintextC2Columns(
      Map<String, ? extends Collection<String>> columnsByTable, Set<String> c2Columns) {
    List<String> violations = new ArrayList<>();
    columnsByTable.forEach(
        (table, columns) -> {
          for (String column : columns) {
            if (c2Columns.contains(column.toLowerCase(Locale.ROOT))) {
              violations.add(table + "." + column);
            }
          }
        });
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "Kho phân tích chứa cột C2 dạng rõ (ADR-06, TL-03): " + String.join(", ", violations));
    }
  }

  /**
   * Danh mục tên trường C2 suy từ migration: cột {@code <tên>_enc} trong CSDL nghiệp vụ là bản mã
   * của trường C2 {@code <tên>}; kho phân tích không được có cột {@code <tên>} dạng rõ.
   */
  public static Set<String> c2ColumnsFromMigrations(Path root) {
    Set<String> names = new TreeSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path sql : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
        Matcher m = ENCRYPTED_COLUMN.matcher(Files.readString(sql));
        while (m.find()) {
          names.add(m.group(1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  private static String identifier(String name) {
    if (name == null || !IDENTIFIER.matcher(name).matches()) {
      throw new IllegalArgumentException("Tên bảng hoặc cột không hợp lệ: " + name);
    }
    return name;
  }
}
