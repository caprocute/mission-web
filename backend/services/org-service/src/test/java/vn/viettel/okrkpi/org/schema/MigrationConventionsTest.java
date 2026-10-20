package vn.viettel.okrkpi.org.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.org.support.Fixtures;

/**
 * Lint quy ước sau khi áp toàn bộ migration lên PostgreSQL 17 (tạm thay {@code MigrationConventionsTest} của NEN-030):
 * chú thích phân loại C0–C3 trên mọi cột, cột C2 là {@code bytea} hậu tố {@code _enc} và bảng có {@code dek_id}, tên
 * ràng buộc, RLS bắt buộc, quyền của {@code svc_org}.
 */
class MigrationConventionsTest {

  private static final Set<String> BITEMPORAL = Set.of("org_unit", "org_unit_relation", "person", "position",
      "assignment", "reporting_line");
  private static final Set<String> ALLOWED_UPDATE = Set.of("recorded_to", "updated_at", "updated_by", "row_version");
  private static JdbcTemplate db;
  private static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void setUp() {
    db = new Fixtures().admin();
  }

  private static List<String> tables() {
    return db.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE n.nspname = 'org' AND c.relkind IN ('r','p') AND NOT c.relispartition "
        + "AND c.relname <> 'flyway_schema_history' ORDER BY 1", String.class);
  }

  @Test
  void moiCotCoChuThichPhanLoaiHopLe() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Map<String, Object> row : db.queryForList("SELECT c.relname AS t, a.attname AS col, "
        + "format_type(a.atttypid, a.atttypmod) AS type, col_description(c.oid, a.attnum) AS d "
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid "
        + "WHERE n.nspname = 'org' AND c.relkind IN ('r','p') AND NOT c.relispartition AND a.attnum > 0 "
        + "AND NOT a.attisdropped AND c.relname <> 'flyway_schema_history'")) {
      String where = row.get("t") + "." + row.get("col");
      Object d = row.get("d");
      if (d == null) {
        problems.add(where + ": thiếu chú thích");
        continue;
      }
      JsonNode meta = JSON.readTree(d.toString());
      String cls = meta.path("cls").asText();
      if (!Set.of("C0", "C1", "C2", "C3").contains(cls) || !meta.has("desc")) {
        problems.add(where + ": chú thích sai cú pháp");
      }
      if ("C2".equals(cls)) {
        if (!"bytea".equals(row.get("type")) || !row.get("col").toString().endsWith("_enc")) {
          problems.add(where + ": cột C2 phải là bytea hậu tố _enc");
        }
        Integer dek = db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'org' "
            + "AND table_name = ? AND column_name = 'dek_id'", Integer.class, row.get("t"));
        if (dek == null || dek == 0) {
          problems.add(where + ": bảng có cột C2 phải có dek_id");
        }
      }
      if (row.get("col").toString().endsWith("_at") && !row.get("type").toString().startsWith("timestamp with")) {
        problems.add(where + ": cột _at phải là timestamptz");
      }
      if (!row.get("col").toString().equals(row.get("col").toString().toLowerCase())) {
        problems.add(where + ": tên có chữ hoa");
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  void khongCotNaoTenEncMaKhongPhaiC2() {
    List<String> plain = db.queryForList("SELECT c.relname || '.' || a.attname FROM pg_class c "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid "
        + "WHERE n.nspname = 'org' AND NOT c.relispartition AND c.relkind IN ('r','p') AND a.attname LIKE '%\\_enc' "
        + "AND (col_description(c.oid, a.attnum)::jsonb ->> 'cls') <> 'C2'", String.class);
    assertThat(plain).isEmpty();
  }

  @Test
  void tenRangBuocTheoQuyUoc() {
    List<String> bad = new ArrayList<>();
    for (Map<String, Object> row : db.queryForList("SELECT con.conname, con.contype::text AS type, c.relname "
        + "FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE n.nspname = 'org' AND NOT c.relispartition AND c.relname <> 'flyway_schema_history'")) {
      String name = row.get("conname").toString();
      String prefix = switch (row.get("type").toString()) {
        case "p" -> "pk_";
        case "f" -> "fk_";
        case "u" -> "uq_";
        case "c" -> "ck_";
        case "x" -> "ex_";
        default -> "";
      };
      if (!name.startsWith(prefix + row.get("relname")) || !name.equals(name.toLowerCase())) {
        bad.add(name);
      }
    }
    for (String index : db.queryForList("SELECT i.relname FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid "
        + "JOIN pg_class t ON t.oid = x.indrelid JOIN pg_namespace n ON n.oid = t.relnamespace "
        + "WHERE n.nspname = 'org' AND NOT t.relispartition AND t.relname <> 'flyway_schema_history'", String.class)) {
      if (!Pattern.matches("(ix|pk|uq|ex)_[a-z0-9_]+", index)) {
        bad.add(index);
      }
    }
    assertThat(bad).isEmpty();
  }

  @Test
  void moiBangVaPhanVungBatRlsBatBuoc() {
    List<String> bad = new ArrayList<>();
    for (Map<String, Object> row : db.queryForList("SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
        + "EXISTS (SELECT 1 FROM pg_policies p WHERE p.schemaname = 'org' AND p.tablename = c.relname "
        + "AND p.policyname = 'rls_' || c.relname || '__tenant') AS has_policy "
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE n.nspname = 'org' AND c.relkind IN ('r','p') AND c.relname <> 'flyway_schema_history'")) {
      if (!Boolean.TRUE.equals(row.get("relrowsecurity")) || !Boolean.TRUE.equals(row.get("relforcerowsecurity"))
          || !Boolean.TRUE.equals(row.get("has_policy"))) {
        bad.add(row.get("relname").toString());
      }
    }
    assertThat(bad).isEmpty();
    assertThat(db.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = 'svc_org'", Boolean.class))
        .isFalse();
  }

  @Test
  void svcOrgKhongCoDeleteVaChiSuaCotDongPhienBan() {
    for (String table : tables()) {
      assertThat(db.queryForObject("SELECT has_table_privilege('svc_org', 'org.' || ?, 'DELETE')", Boolean.class,
          table)).as("DELETE trên " + table).isFalse();
      assertThat(db.queryForObject("SELECT has_table_privilege('svc_org', 'org.' || ?, 'TRUNCATE')", Boolean.class,
          table)).as("TRUNCATE trên " + table).isFalse();
    }
    for (String table : BITEMPORAL) {
      List<String> updatable = db.queryForList("SELECT a.attname FROM pg_attribute a "
          + "WHERE a.attrelid = ('org.' || ?)::regclass AND a.attnum > 0 AND NOT a.attisdropped "
          + "AND has_column_privilege('svc_org', a.attrelid, a.attname, 'UPDATE')", String.class, table);
      assertThat(Set.copyOf(updatable)).as(table).isEqualTo(ALLOWED_UPDATE);
    }
  }

  @Test
  void migrationKhongCapDeleteChoSvcOrg() throws IOException {
    Pattern grant = Pattern.compile("GRANT\\s+([^;]*?)\\s+ON\\s+[^;]*?TO\\s+[^;]*svc_org", Pattern.CASE_INSENSITIVE);
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration/org"))) {
      for (Path f : files.toList()) {
        Matcher m = grant.matcher(Files.readString(f));
        while (m.find()) {
          assertThat(m.group(1).toUpperCase()).as(f.getFileName().toString()).doesNotContain("DELETE")
              .doesNotContain("ALL");
        }
      }
    }
  }
}
