package vn.viettel.okrkpi.platform.keyrotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Danh sách bảng có cột phong bì C2 trong một CSDL, đọc từ danh mục hệ thống: cột {@code bytea} có
 * hậu tố {@code _enc} hoặc chú thích phân loại {@code "cls":"C2"} (quy ước NEN-030, TL-03), trong
 * bảng có cột {@code dek_id} và {@code tenant_id}. Bảng phân vùng chỉ lấy bảng cha.
 */
final class EncryptedColumnCatalog {

  /** Bảng có cột mã hoá. */
  record Table(
      String schema,
      String name,
      List<String> primaryKey,
      List<String> encColumns,
      boolean hasRowVersion) {
    String qualified() {
      return schema + "." + name;
    }
  }

  private static final String SQL_COLUMNS =
      "SELECT n.nspname, k.relname, a.attname FROM pg_attribute a"
          + " JOIN pg_class k ON k.oid = a.attrelid JOIN pg_namespace n ON n.oid = k.relnamespace"
          + " WHERE k.relkind IN ('r','p') AND NOT k.relispartition AND a.attnum > 0 AND NOT a.attisdropped"
          + " AND a.atttypid = 'bytea'::regtype AND n.nspname NOT IN ('keyring','pg_catalog','information_schema')"
          + " AND (a.attname LIKE '%\\_enc' OR col_description(k.oid, a.attnum) ~ '\"cls\"\\s*:\\s*\"C2\"')"
          + " AND EXISTS (SELECT 1 FROM pg_attribute d WHERE d.attrelid = k.oid AND d.attname = 'dek_id' AND NOT d.attisdropped)"
          + " AND EXISTS (SELECT 1 FROM pg_attribute d WHERE d.attrelid = k.oid AND d.attname = 'tenant_id' AND NOT d.attisdropped)"
          + " ORDER BY 1, 2, 3";

  private static final String SQL_PK =
      "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
          + " AND a.attnum = ANY(i.indkey) WHERE i.indrelid = ?::regclass AND i.indisprimary"
          + " ORDER BY array_position(i.indkey, a.attnum)";

  private EncryptedColumnCatalog() {}

  static List<Table> load(JdbcTemplate jdbc) {
    Map<String, List<String>> cols = new LinkedHashMap<>();
    jdbc.query(
        SQL_COLUMNS,
        (RowCallbackHandler)
            rs ->
                cols.computeIfAbsent(
                        rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                    .add(rs.getString(3)));
    List<Table> out = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : cols.entrySet()) {
      String[] p = e.getKey().split("\\.", 2);
      List<String> pk = jdbc.queryForList(SQL_PK, String.class, quote(p[0]) + "." + quote(p[1]));
      Integer rv =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_attribute WHERE attrelid = ?::regclass AND attname = 'row_version'"
                  + " AND NOT attisdropped",
              Integer.class,
              quote(p[0]) + "." + quote(p[1]));
      out.add(new Table(p[0], p[1], pk, List.copyOf(e.getValue()), rv != null && rv > 0));
    }
    return out;
  }

  static String quote(String ident) {
    return "\"" + ident.replace("\"", "\"\"") + "\"";
  }
}
