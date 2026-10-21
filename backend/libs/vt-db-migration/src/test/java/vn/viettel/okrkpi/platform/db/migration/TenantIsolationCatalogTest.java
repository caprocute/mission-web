package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PGobject;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * Lớp cơ sở kiểm thử rò rỉ tenant ở tầng CSDL (NEN-034 · ADR-07, ADR-22 · TL-03 "Kiểm thử rò rỉ
 * tenant" lớp 1–2 và mục "Phân vùng"):
 *
 * <ul>
 *   <li><b>Lớp 1 – danh mục</b>: chạy truy vấn danh mục TL-03 L742–752 sau khi áp toàn bộ migration
 *       của mô-đun, mở rộng cho cả phân vùng con ({@code relispartition}); mọi bảng có {@code
 *       tenant_id} phải bật {@code relrowsecurity}, {@code relforcerowsecurity} và có ít nhất một
 *       chính sách {@code pg_policy}; phân vùng con phải thu hồi quyền trực tiếp của {@code svc_},
 *       {@code ro_}, {@code cdc_}, {@code PUBLIC}; bảng không có {@code tenant_id} phải nằm trong
 *       danh sách ngoại lệ {@link #tenantlessTableExceptions()} kèm lý do.
 *   <li><b>Lớp 2 – hai tenant sinh tự động</b>: với mỗi bảng có {@code tenant_id}, tự động sinh
 *       dòng hợp lệ tối thiểu cho tenant A và tenant B từ siêu dữ liệu cột, {@code NOT NULL},
 *       {@code CHECK} và khoá ngoại trong cùng schema (hoặc dùng {@link #rowFixtures()} khi bảng có
 *       ràng buộc phức tạp), rồi dưới ngữ cảnh A kiểm {@code SELECT/UPDATE/DELETE} dòng B = 0 dòng,
 *       {@code INSERT} mang {@code tenant_id} của B bị chặn ({@code 42501}), và không đặt ngữ cảnh
 *       thì {@code SELECT} trả rỗng.
 *   <li><b>Phân vùng</b>: kiểm vai trò {@code svc_<schema>} bị từ chối truy vấn trực tiếp vào phân
 *       vùng con ({@code 42501}), và với bảng phân vùng theo kỳ ở chế độ {@code pool} kiểm tạo đủ
 *       16 phân vùng băm con {@code _h00.._h15}.
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TenantIsolationCatalogTest {

  protected static final PostgreSQLContainer<?> POSTGRES =
      TestPostgres.container("td_rls_isolation");

  static {
    POSTGRES.start();
  }

  protected static final String TEST_PASSWORD = "mat-khau-thu-nghiem";
  protected static final UUID TENANT_A = UUID.fromString("01927a00-0000-7000-8000-00000000010a");
  protected static final UUID TENANT_B = UUID.fromString("01927a00-0000-7000-8000-00000000010b");
  protected static final int SAMPLE_PERIOD_BUCKET = 20271;

  private static final Pattern FLOWABLE_TABLE = Pattern.compile("(?i)(act|flw)_.*");
  private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'([^']+)'");

  /** Fixture tuỳ chỉnh cho bảng có ràng buộc phức tạp không tự tổng hợp được từ danh mục. */
  @FunctionalInterface
  public interface RowFixture {
    /**
     * Trả bản đồ tên cột → giá trị cho một dòng của {@code tenantId} (seed phân biệt dòng A và B).
     */
    Map<String, Object> synthesize(UUID tenantId, int seed);
  }

  private String databaseName;

  /** Các schema cần áp migration và kiểm thử cô lập tenant, theo thứ tự áp. */
  protected abstract List<String> schemas();

  /** Tuỳ chọn Flyway; mặc định theo quy ước thư mục chuẩn. */
  protected MigrationOptions options() {
    return MigrationOptions.defaults();
  }

  /**
   * Mô hình thuê bao đặt trên CSDL thử nghiệm ({@code silo}, {@code bridge}, {@code pool}). Mặc
   * định {@code pool} để kiểm luôn 16 phân vùng con băm theo {@code tenant_id}.
   */
  protected String tenancyModel() {
    return "pool";
  }

  /**
   * Danh sách bảng không có cột {@code tenant_id} được phép tồn tại: khoá là {@code
   * <schema>.<bảng>}, giá trị là lý do nghiệp vụ/kỹ thuật (không được rỗng).
   */
  protected Map<String, String> tenantlessTableExceptions() {
    return Map.of();
  }

  /**
   * Fixture dòng mẫu cho bảng có ràng buộc phức tạp không tự sinh được; khoá là {@code
   * <schema>.<bảng>}.
   */
  protected Map<String, RowFixture> rowFixtures() {
    return Map.of();
  }

  @BeforeAll
  void bootstrapAndMigrateSchemas() throws SQLException {
    this.databaseName =
        "td_iso_"
            + getClass().getSimpleName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    try (Connection root =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = root.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + databaseName);
      st.execute("CREATE DATABASE " + databaseName);
    }
    prepareDatabase(jdbcUrl(), schemas(), options(), tenancyModel(), getClass().getClassLoader());
  }

  protected final String jdbcUrl() {
    return POSTGRES.getJdbcUrl().replace("/td_rls_isolation", "/" + databaseName);
  }

  /** Khởi tạo vai trò, đặt mô hình thuê bao và chạy Flyway cho danh sách schema trên một CSDL. */
  public static void prepareDatabase(
      String jdbcUrl,
      List<String> schemas,
      MigrationOptions options,
      String tenancyModel,
      ClassLoader classLoader)
      throws SQLException {
    Map<String, String> passwords = new HashMap<>();
    for (String schema : schemas) {
      for (String role : RoleBootstrapPlan.roles(schema)) {
        passwords.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", TEST_PASSWORD);
      }
    }
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), passwords);
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      bootstrap.applySchemas(admin, schemas);
      if (tenancyModel != null && !tenancyModel.isBlank()) {
        bootstrap.setTenancyModel(admin, tenancyModel);
      }
    }
    SchemaMigrator migrator = new SchemaMigrator(options, classLoader);
    for (String schema : schemas) {
      migrator.migrate(
          jdbcUrl,
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), TEST_PASSWORD));
    }
  }

  @Test
  void lop1_danhMucRlsForceChinhSachVaPhanVungCon() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ensurePeriodPartitionsCreated(jdbcUrl(), admin, schemas());
      List<String> violations = verifyCatalog(admin, schemas(), tenantlessTableExceptions());
      assertThat(violations)
          .as("Vi phạm danh mục RLS lớp 1 (TL-03 L742-752):%n%s", String.join("\n", violations))
          .isEmpty();
    }
  }

  @Test
  void lop2_coLapHaiTenantSinhTuDongTrenMoiBang() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ensurePeriodPartitionsCreated(jdbcUrl(), admin, schemas());
      List<String> violations =
          verifyTwoTenantIsolation(jdbcUrl(), admin, schemas(), rowFixtures());
      assertThat(violations)
          .as("Vi phạm cô lập hai tenant lớp 2:%n%s", String.join("\n", violations))
          .isEmpty();
    }
  }

  @Test
  void phanVungConChanTruyCapTrucTiepVaChia16PhanVungBamOPool() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ensurePeriodPartitionsCreated(jdbcUrl(), admin, schemas());
      List<String> violations =
          verifyPartitionProtection(jdbcUrl(), admin, schemas(), tenancyModel());
      assertThat(violations)
          .as("Vi phạm bảo vệ phân vùng con / phân vùng băm:%n%s", String.join("\n", violations))
          .isEmpty();
    }
  }

  /**
   * Lớp 1: Kiểm tra danh mục RLS (truy vấn TL-03 L742–752 mở rộng cho cả phân vùng con và danh sách
   * ngoại lệ bảng không có {@code tenant_id}).
   */
  public static List<String> verifyCatalog(
      Connection admin, List<String> schemas, Map<String, String> tenantlessExceptions)
      throws SQLException {
    List<String> violations = new ArrayList<>();
    for (String schema : schemas) {
      SchemaNames.require(schema);
      // 1. Truy vấn TL-03 L742-752 mở rộng cho cả phân vùng con (relispartition).
      String rlsQuery =
          """
          SELECT n.nspname,
                 c.relname,
                 c.relispartition,
                 c.relrowsecurity,
                 c.relforcerowsecurity,
                 EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy,
                 has_table_privilege('svc_' || n.nspname, c.oid, 'SELECT') AS svc_has_select,
                 has_table_privilege('ro_' || n.nspname, c.oid, 'SELECT') AS ro_has_select,
                 has_table_privilege('cdc_' || n.nspname, c.oid, 'SELECT') AS cdc_has_select
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
           WHERE n.nspname = ?
             AND c.relkind IN ('r', 'p')
             AND c.relname <> ?
             AND NOT EXISTS (
                 SELECT 1 FROM pg_depend d
                  WHERE d.objid = c.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'e')
           ORDER BY c.relname
          """;
      try (PreparedStatement ps = admin.prepareStatement(rlsQuery)) {
        ps.setString(1, schema);
        ps.setString(2, SchemaMigrator.HISTORY_TABLE);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String rel = rs.getString(1) + "." + rs.getString(2);
            boolean isPartition = rs.getBoolean(3);
            boolean rls = rs.getBoolean(4);
            boolean forceRls = rs.getBoolean(5);
            boolean hasPolicy = rs.getBoolean(6);
            boolean svcSelect = rs.getBoolean(7);
            boolean roSelect = rs.getBoolean(8);
            boolean cdcSelect = rs.getBoolean(9);

            if (!rls) {
              violations.add(rel + ": thiếu ENABLE ROW LEVEL SECURITY (relrowsecurity = false)");
            }
            if (!forceRls) {
              violations.add(
                  rel + ": thiếu FORCE ROW LEVEL SECURITY (relforcerowsecurity = false)");
            }
            if (!hasPolicy) {
              violations.add(rel + ": không có chính sách pg_policy nào");
            }
            if (isPartition && (svcSelect || roSelect || cdcSelect)) {
              violations.add(
                  rel
                      + ": phân vùng con chưa thu hồi quyền trực tiếp của vai trò ứng dụng (svc/ro/cdc)");
            }
          }
        }
      }

      // 2. Bảng gốc không có cột tenant_id phải nằm trong danh sách ngoại lệ có lý do.
      String tenantlessQuery =
          """
          SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = ?
             AND c.relkind IN ('r', 'p')
             AND NOT c.relispartition
             AND c.relname <> ?
             AND NOT EXISTS (
                 SELECT 1 FROM pg_depend d
                  WHERE d.objid = c.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'e')
             AND NOT EXISTS (
                 SELECT 1 FROM pg_attribute a
                  WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
           ORDER BY c.relname
          """;
      try (PreparedStatement ps = admin.prepareStatement(tenantlessQuery)) {
        ps.setString(1, schema);
        ps.setString(2, SchemaMigrator.HISTORY_TABLE);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String tableName = rs.getString(1);
            if (FLOWABLE_TABLE.matcher(tableName).matches()) {
              continue;
            }
            String fullName = schema + "." + tableName;
            String reason =
                tenantlessExceptions == null ? null : tenantlessExceptions.get(fullName);
            if (reason == null || reason.isBlank()) {
              violations.add(
                  fullName
                      + ": bảng không có cột tenant_id nhưng chưa khai báo lý do trong tenantlessTableExceptions()");
            }
          }
        }
      }
    }
    return violations;
  }

  /**
   * Lớp 2: Với mỗi bảng có {@code tenant_id}, tự động sinh dòng hợp lệ tối thiểu cho hai tenant A
   * và B, rồi kiểm tra cô lập SELECT/UPDATE/DELETE/INSERT và truy vấn thiếu ngữ cảnh tenant.
   */
  public static List<String> verifyTwoTenantIsolation(
      String jdbcUrl, Connection admin, List<String> schemas, Map<String, RowFixture> fixtures)
      throws SQLException {
    List<String> violations = new ArrayList<>();
    for (String schema : schemas) {
      SchemaNames.require(schema);
      List<TableMeta> tables = discoverTenantTablesInFkOrder(admin, schema);
      String svcRole = RoleBootstrapPlan.role("svc", schema);
      try (Connection svc = DriverManager.getConnection(jdbcUrl, svcRole, TEST_PASSWORD)) {
        Map<String, Map<String, Object>> insertedRowsA = new HashMap<>();
        Map<String, Map<String, Object>> insertedRowsB = new HashMap<>();

        for (TableMeta table : tables) {
          String fullTable = schema + "." + table.name();
          Map<String, Object> rowA;
          Map<String, Object> rowB;
          try {
            rowA = synthesizeRow(table, TENANT_A, 1, insertedRowsA, fixtures);
            rowB = synthesizeRow(table, TENANT_B, 2, insertedRowsB, fixtures);
          } catch (RuntimeException e) {
            violations.add(
                fullTable
                    + ": không tự sinh được dòng mẫu ("
                    + e.getMessage()
                    + "); hãy cung cấp RowFixture trong rowFixtures()");
            continue;
          }

          try {
            inTenant(svc, TENANT_A.toString(), c -> insertRow(c, fullTable, table, rowA));
            inTenant(svc, TENANT_B.toString(), c -> insertRow(c, fullTable, table, rowB));
            insertedRowsA.put(table.name(), rowA);
            insertedRowsB.put(table.name(), rowB);
          } catch (SQLException e) {
            violations.add(
                fullTable
                    + ": chèn dòng mẫu hợp lệ cho tenant A/B thất bại ("
                    + e.getMessage()
                    + "); hãy cung cấp RowFixture trong rowFixtures()");
            continue;
          }

          // 1) Dưới ngữ cảnh A: thấy dòng A (>= 1), không thấy dòng B (= 0).
          long seenOwnInA =
              countInTenant(
                  svc,
                  TENANT_A.toString(),
                  "SELECT count(*) FROM " + fullTable + " WHERE tenant_id = '" + TENANT_A + "'");
          if (seenOwnInA < 1) {
            violations.add(fullTable + ": dưới ngữ cảnh A không đọc được dòng của chính tenant A");
          }
          long seenOtherInA =
              countInTenant(
                  svc,
                  TENANT_A.toString(),
                  "SELECT count(*) FROM " + fullTable + " WHERE tenant_id = '" + TENANT_B + "'");
          if (seenOtherInA != 0) {
            violations.add(
                fullTable
                    + ": RÒ RỈ SELECT — dưới ngữ cảnh A đọc được "
                    + seenOtherInA
                    + " dòng của tenant B");
          }

          // 2) Dưới ngữ cảnh A: UPDATE dòng B phải tác động 0 dòng (hoặc bị từ chối quyền 42501).
          long updatedInA =
              updateOrDeleteInTenant(
                  svc,
                  TENANT_A.toString(),
                  "UPDATE "
                      + fullTable
                      + " SET tenant_id = '"
                      + TENANT_A
                      + "' WHERE tenant_id = '"
                      + TENANT_B
                      + "'");
          if (updatedInA != 0) {
            violations.add(
                fullTable
                    + ": RÒ RỈ UPDATE — dưới ngữ cảnh A sửa được "
                    + updatedInA
                    + " dòng của tenant B");
          }

          // 3) Dưới ngữ cảnh A: DELETE dòng B phải tác động 0 dòng (hoặc bị từ chối quyền 42501).
          long deletedInA =
              updateOrDeleteInTenant(
                  svc,
                  TENANT_A.toString(),
                  "DELETE FROM " + fullTable + " WHERE tenant_id = '" + TENANT_B + "'");
          if (deletedInA != 0) {
            violations.add(
                fullTable
                    + ": RÒ RỈ DELETE — dưới ngữ cảnh A xoá được "
                    + deletedInA
                    + " dòng của tenant B");
          }

          // 4) Dưới ngữ cảnh A: INSERT dòng mang tenant_id của B phải bị RLS WITH CHECK chặn.
          Map<String, Object> spoofedRowB =
              synthesizeRow(table, TENANT_B, 3, insertedRowsB, fixtures);
          boolean spoofBlocked = false;
          try {
            inTenant(svc, TENANT_A.toString(), c -> insertRow(c, fullTable, table, spoofedRowB));
          } catch (SQLException expected) {
            if ("42501".equals(expected.getSQLState())) {
              spoofBlocked = true;
            } else {
              violations.add(
                  fullTable
                      + ": INSERT dòng mang tenant_id của B dưới ngữ cảnh A ném SQLState "
                      + expected.getSQLState()
                      + " thay vì 42501");
              spoofBlocked = true;
            }
          }
          if (!spoofBlocked) {
            violations.add(
                fullTable
                    + ": RÒ RỈ INSERT — dưới ngữ cảnh A chèn được dòng mang tenant_id của tenant B");
          }

          // 5) Không đặt ngữ cảnh tenant (null, chuỗi rỗng và autocommit): SELECT phải trả 0 dòng.
          long seenWithoutTenant = countInTenant(svc, null, "SELECT count(*) FROM " + fullTable);
          if (seenWithoutTenant != 0) {
            violations.add(
                fullTable
                    + ": RÒ RỈ KHI THIẾU NGỮ CẢNH — không đặt app.tenant_id nhưng SELECT trả "
                    + seenWithoutTenant
                    + " dòng");
          }
          long seenEmptyTenant = countInTenant(svc, "", "SELECT count(*) FROM " + fullTable);
          if (seenEmptyTenant != 0) {
            violations.add(
                fullTable
                    + ": RÒ RỈ KHI NGỮ CẢNH RỖNG — app.tenant_id='' nhưng SELECT trả "
                    + seenEmptyTenant
                    + " dòng");
          }

          // 6) Dữ liệu của tenant B vẫn nguyên vẹn khi đọc dưới ngữ cảnh B.
          long seenOwnInB =
              countInTenant(
                  svc,
                  TENANT_B.toString(),
                  "SELECT count(*) FROM " + fullTable + " WHERE tenant_id = '" + TENANT_B + "'");
          if (seenOwnInB < 1) {
            violations.add(fullTable + ": dòng của tenant B bị mất hoặc không đọc được dưới B");
          }
        }
      }
    }
    return violations;
  }

  /**
   * Kiểm bảo vệ phân vùng con (từ chối truy vấn trực tiếp của {@code svc_<schema>}) và kiểm 16 phân
   * vùng con băm ở chế độ {@code pool} cho các bảng phân vùng theo kỳ.
   */
  public static List<String> verifyPartitionProtection(
      String jdbcUrl, Connection admin, List<String> schemas, String tenancyModel)
      throws SQLException {
    List<String> violations = new ArrayList<>();
    for (String schema : schemas) {
      String svcRole = RoleBootstrapPlan.role("svc", schema);
      List<String> childPartitions = new ArrayList<>();
      String partSql =
          """
          SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = ?
             AND c.relispartition
             AND c.relkind IN ('r', 'p')
           ORDER BY c.relname
          """;
      try (PreparedStatement ps = admin.prepareStatement(partSql)) {
        ps.setString(1, schema);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            childPartitions.add(rs.getString(1));
          }
        }
      }

      try (Connection svc = DriverManager.getConnection(jdbcUrl, svcRole, TEST_PASSWORD)) {
        for (String part : childPartitions) {
          String fullPart = schema + "." + part;
          boolean denied = false;
          try {
            countInTenant(svc, TENANT_A.toString(), "SELECT count(*) FROM " + fullPart);
          } catch (SQLException expected) {
            if ("42501".equals(expected.getSQLState())) {
              denied = true;
            }
          }
          if (!denied) {
            violations.add(
                fullPart
                    + ": vai trò "
                    + svcRole
                    + " truy vấn trực tiếp được phân vùng con mà không bị chặn 42501");
          }
        }
      }

      if ("pool".equals(tenancyModel)) {
        for (String periodTable : periodPartitionedTables(admin, schema)) {
          String prefix = periodTable + "_p" + SAMPLE_PERIOD_BUCKET + "_h";
          String hashCountSql =
              """
              SELECT count(*)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = ?
                 AND c.relispartition
                 AND c.relkind = 'r'
                 AND c.relname LIKE ?
              """;
          try (PreparedStatement ps = admin.prepareStatement(hashCountSql)) {
            ps.setString(1, schema);
            ps.setString(2, prefix.replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              int hashPartitions = rs.getInt(1);
              if (hashPartitions != 16) {
                violations.add(
                    schema
                        + "."
                        + periodTable
                        + ": ở chế độ pool phải có 16 phân vùng băm con cho kỳ "
                        + SAMPLE_PERIOD_BUCKET
                        + ", thực tế có "
                        + hashPartitions);
              }
            }
          }
        }
      }
    }
    return violations;
  }

  /** Đảm bảo mọi bảng phân vùng LIST theo {@code period_bucket} đã mở phân vùng mẫu 20271. */
  public static void ensurePeriodPartitionsCreated(
      String jdbcUrl, Connection admin, List<String> schemas) throws SQLException {
    for (String schema : schemas) {
      List<String> periodTables = periodPartitionedTables(admin, schema);
      if (periodTables.isEmpty()) {
        continue;
      }
      String svcRole = RoleBootstrapPlan.role("svc", schema);
      try (Connection svc = DriverManager.getConnection(jdbcUrl, svcRole, TEST_PASSWORD)) {
        for (String table : periodTables) {
          try (PreparedStatement ps =
              svc.prepareStatement(
                  "SELECT "
                      + schema
                      + ".fn_create_period_partitions('"
                      + schema
                      + "."
                      + table
                      + "', ?)")) {
            ps.setInt(1, SAMPLE_PERIOD_BUCKET);
            ps.executeQuery().close();
          }
        }
      }
    }
  }

  private static List<String> periodPartitionedTables(Connection admin, String schema)
      throws SQLException {
    String sql =
        """
        SELECT c.relname
          FROM pg_partitioned_table pt
          JOIN pg_class c ON c.oid = pt.partrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
         WHERE n.nspname = ?
           AND NOT c.relispartition
           AND pt.partstrat = 'l'
           AND pt.partnatts = 1
           AND a.attname = 'period_bucket'
         ORDER BY c.relname
        """;
    List<String> result = new ArrayList<>();
    try (PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  private record ColumnMeta(
      String name, String type, boolean notNull, String defaultExpr, List<String> checkDefs) {}

  private record ForeignKeyMeta(String column, String targetTable, String targetColumn) {}

  private record TableMeta(
      String schema,
      String name,
      List<ColumnMeta> columns,
      Set<String> primaryKeyColumns,
      List<ForeignKeyMeta> foreignKeys) {}

  private static List<TableMeta> discoverTenantTablesInFkOrder(Connection admin, String schema)
      throws SQLException {
    String tableSql =
        """
        SELECT c.oid, c.relname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
         WHERE n.nspname = ?
           AND c.relkind IN ('r', 'p')
           AND NOT c.relispartition
           AND c.relname <> ?
           AND NOT EXISTS (
               SELECT 1 FROM pg_depend d
                WHERE d.objid = c.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'e')
         ORDER BY c.relname
        """;
    Map<String, Long> tableOids = new LinkedHashMap<>();
    try (PreparedStatement ps = admin.prepareStatement(tableSql)) {
      ps.setString(1, schema);
      ps.setString(2, SchemaMigrator.HISTORY_TABLE);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          tableOids.put(rs.getString(2), rs.getLong(1));
        }
      }
    }

    Map<String, TableMeta> metaByName = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : tableOids.entrySet()) {
      String table = entry.getKey();
      long oid = entry.getValue();
      List<ColumnMeta> cols = loadColumns(admin, oid);
      Set<String> pkCols = loadPrimaryKeyColumns(admin, oid);
      List<ForeignKeyMeta> fks = loadIntraSchemaForeignKeys(admin, oid, schema);
      metaByName.put(table, new TableMeta(schema, table, cols, pkCols, fks));
    }

    // Sắp xếp topo theo FK trong cùng schema.
    List<TableMeta> ordered = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    for (String table : metaByName.keySet()) {
      visitTable(table, metaByName, visited, new HashSet<>(), ordered);
    }
    return ordered;
  }

  private static void visitTable(
      String table,
      Map<String, TableMeta> metaByName,
      Set<String> visited,
      Set<String> visiting,
      List<TableMeta> ordered) {
    if (visited.contains(table) || !metaByName.containsKey(table)) {
      return;
    }
    if (!visiting.add(table)) {
      return;
    }
    TableMeta meta = metaByName.get(table);
    for (ForeignKeyMeta fk : meta.foreignKeys()) {
      if (!fk.targetTable().equals(table)) {
        visitTable(fk.targetTable(), metaByName, visited, visiting, ordered);
      }
    }
    visiting.remove(table);
    visited.add(table);
    ordered.add(meta);
  }

  private static List<ColumnMeta> loadColumns(Connection admin, long tableOid) throws SQLException {
    Map<Short, List<String>> checksByAttnum = new HashMap<>();
    String checkSql =
        """
        SELECT unnest(con.conkey) AS attnum, pg_get_constraintdef(con.oid) AS def
          FROM pg_constraint con
         WHERE con.conrelid = ? AND con.contype = 'c'
        """;
    try (PreparedStatement ps = admin.prepareStatement(checkSql)) {
      ps.setLong(1, tableOid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          checksByAttnum
              .computeIfAbsent(rs.getShort(1), k -> new ArrayList<>())
              .add(rs.getString(2));
        }
      }
    }

    String colSql =
        """
        SELECT a.attnum,
               a.attname,
               format_type(a.atttypid, a.atttypmod) AS col_type,
               a.attnotnull,
               pg_get_expr(d.adbin, d.adrelid) AS default_expr
          FROM pg_attribute a
          LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
         WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
         ORDER BY a.attnum
        """;
    List<ColumnMeta> cols = new ArrayList<>();
    try (PreparedStatement ps = admin.prepareStatement(colSql)) {
      ps.setLong(1, tableOid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          short attnum = rs.getShort(1);
          cols.add(
              new ColumnMeta(
                  rs.getString(2),
                  rs.getString(3),
                  rs.getBoolean(4),
                  rs.getString(5),
                  checksByAttnum.getOrDefault(attnum, List.of())));
        }
      }
    }
    return cols;
  }

  private static Set<String> loadPrimaryKeyColumns(Connection admin, long tableOid)
      throws SQLException {
    String sql =
        """
        SELECT a.attname
          FROM pg_constraint con
          JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY(con.conkey)
         WHERE con.conrelid = ? AND con.contype = 'p'
        """;
    Set<String> pks = new HashSet<>();
    try (PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setLong(1, tableOid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          pks.add(rs.getString(1));
        }
      }
    }
    return pks;
  }

  private static List<ForeignKeyMeta> loadIntraSchemaForeignKeys(
      Connection admin, long tableOid, String schema) throws SQLException {
    String sql =
        """
        SELECT src.attname, fc.relname, dst.attname
          FROM pg_constraint con
          JOIN pg_class fc ON fc.oid = con.confrelid
          JOIN pg_namespace fn ON fn.oid = fc.relnamespace
          JOIN pg_attribute src ON src.attrelid = con.conrelid AND src.attnum = con.conkey[1]
          JOIN pg_attribute dst ON dst.attrelid = con.confrelid AND dst.attnum = con.confkey[1]
         WHERE con.conrelid = ?
           AND con.contype = 'f'
           AND fn.nspname = ?
           AND array_length(con.conkey, 1) = 1
        """;
    List<ForeignKeyMeta> fks = new ArrayList<>();
    try (PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setLong(1, tableOid);
      ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          fks.add(new ForeignKeyMeta(rs.getString(1), rs.getString(2), rs.getString(3)));
        }
      }
    }
    return fks;
  }

  private static Map<String, Object> synthesizeRow(
      TableMeta table,
      UUID tenantId,
      int seed,
      Map<String, Map<String, Object>> parentRows,
      Map<String, RowFixture> fixtures) {
    String fullTable = table.schema() + "." + table.name();
    if (fixtures != null && fixtures.containsKey(fullTable)) {
      Map<String, Object> custom =
          new LinkedHashMap<>(fixtures.get(fullTable).synthesize(tenantId, seed));
      custom.put("tenant_id", tenantId);
      return custom;
    }

    Map<String, ForeignKeyMeta> fkByCol = new HashMap<>();
    for (ForeignKeyMeta fk : table.foreignKeys()) {
      fkByCol.put(fk.column(), fk);
    }

    Map<String, Object> row = new LinkedHashMap<>();
    for (ColumnMeta col : table.columns()) {
      String name = col.name();
      if ("tenant_id".equals(name)) {
        row.put(name, tenantId);
        continue;
      }
      if (fkByCol.containsKey(name)) {
        ForeignKeyMeta fk = fkByCol.get(name);
        Map<String, Object> parent = parentRows.get(fk.targetTable());
        if (parent != null && parent.containsKey(fk.targetColumn())) {
          row.put(name, parent.get(fk.targetColumn()));
          continue;
        }
      }
      if (!col.notNull() && !table.primaryKeyColumns().contains(name)) {
        continue;
      }
      if (col.defaultExpr() != null && !table.primaryKeyColumns().contains(name)) {
        continue;
      }
      row.put(name, synthesizeColumnValue(col, seed));
    }
    return row;
  }

  private static Object synthesizeColumnValue(ColumnMeta col, int seed) {
    String name = col.name();
    String type = col.type();
    if ("period_bucket".equals(name)) {
      return SAMPLE_PERIOD_BUCKET;
    }
    for (String checkDef : col.checkDefs()) {
      if (checkDef.contains("^[a-z]+\\.[a-z]+\\.[a-z]+\\.v[0-9]+$")) {
        return "task.task.assigned.v1";
      }
      Matcher m = SQL_STRING_LITERAL.matcher(checkDef);
      if (m.find()) {
        return m.group(1);
      }
    }

    if ("uuid".equals(type)) {
      return UUID.randomUUID();
    }
    if (type.startsWith("character varying(") || "text".equals(type)) {
      int maxLen = 32;
      if (type.startsWith("character varying(")) {
        maxLen = Integer.parseInt(type.substring("character varying(".length(), type.length() - 1));
      }
      String candidate = "mau_" + seed + "_" + UUID.randomUUID().toString().substring(0, 8);
      return candidate.length() <= maxLen ? candidate : candidate.substring(0, maxLen);
    }
    if ("integer".equals(type)) {
      return seed;
    }
    if ("bigint".equals(type)) {
      return (long) seed;
    }
    if ("smallint".equals(type)) {
      return (short) seed;
    }
    if (type.startsWith("numeric")) {
      return BigDecimal.ONE;
    }
    if ("boolean".equals(type)) {
      return Boolean.FALSE;
    }
    if ("timestamp with time zone".equals(type)) {
      return Timestamp.from(Instant.now().plusSeconds(seed));
    }
    if ("date".equals(type)) {
      return Date.valueOf(
          LocalDate.now(ZoneOffset.UTC).plusDays("created_day".equals(name) ? 0 : seed - 1));
    }
    if ("bytea".equals(type)) {
      if (name.endsWith("_hash")) {
        return new byte[32];
      }
      if (name.endsWith("_bidx")) {
        return new byte[16];
      }
      return new byte[] {1, 2, 3, (byte) seed};
    }
    if ("jsonb".equals(type)) {
      return pgObject("jsonb", "{}");
    }
    if ("daterange".equals(type)) {
      int year = 2027 + seed;
      return pgObject("daterange", "[" + year + "-01-01," + year + "-12-31)");
    }
    if ("tstzrange".equals(type)) {
      int year = 2027 + seed;
      return pgObject("tstzrange", "[" + year + "-01-01T00:00:00Z," + year + "-12-31T00:00:00Z)");
    }
    throw new IllegalStateException("Chưa hỗ trợ tự sinh giá trị cho kiểu cột " + type);
  }

  private static PGobject pgObject(String type, String value) {
    try {
      PGobject obj = new PGobject();
      obj.setType(type);
      obj.setValue(value);
      return obj;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertRow(
      Connection c, String fullTable, TableMeta table, Map<String, Object> row)
      throws SQLException {
    List<String> cols = new ArrayList<>(row.keySet());
    String colList = String.join(", ", cols);
    String placeholders = String.join(", ", cols.stream().map(k -> "?").toList());
    String sql = "INSERT INTO " + fullTable + " (" + colList + ") VALUES (" + placeholders + ")";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < cols.size(); i++) {
        ps.setObject(i + 1, row.get(cols.get(i)));
      }
      ps.executeUpdate();
    }
  }

  @FunctionalInterface
  private interface SqlConsumer {
    void run(Connection c) throws SQLException;
  }

  private static void inTenant(Connection c, String tenant, SqlConsumer work) throws SQLException {
    c.setAutoCommit(false);
    try {
      try (PreparedStatement ps =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, tenant == null ? "" : tenant);
        ps.execute();
      }
      work.run(c);
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static long countInTenant(Connection c, String tenant, String sql) throws SQLException {
    long[] out = new long[1];
    inTenant(
        c,
        tenant,
        conn -> {
          try (Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            out[0] = rs.getLong(1);
          }
        });
    return out[0];
  }

  private static long updateOrDeleteInTenant(Connection c, String tenant, String sql)
      throws SQLException {
    long[] out = new long[1];
    try {
      inTenant(
          c,
          tenant,
          conn -> {
            try (Statement st = conn.createStatement()) {
              out[0] = st.executeUpdate(sql);
            }
          });
    } catch (SQLException e) {
      if ("42501".equals(e.getSQLState())) {
        return 0;
      }
      throw e;
    }
    return out[0];
  }
}
