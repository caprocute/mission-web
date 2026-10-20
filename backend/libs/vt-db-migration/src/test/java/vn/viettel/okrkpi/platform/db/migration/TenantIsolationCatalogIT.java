package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tích hợp cho {@link TenantIsolationCatalogTest} (NEN-034 mục 3, 6):
 *
 * <ul>
 *   <li>Chạy xanh trên các schema mẫu chuẩn {@code keyring}, {@code modulith}, {@code task}, {@code
 *       appraisal}.
 *   <li>Chạy xanh trên bảng phân vùng theo kỳ {@code rls_demo.sample_task} ở chế độ {@code pool}
 *       (16 phân vùng băm con + chặn truy vấn trực tiếp phân vùng con).
 *   <li>Kiểm thử âm lớp 1: quên {@code FORCE ROW LEVEL SECURITY} và bảng thiếu {@code tenant_id}
 *       không nằm trong danh sách ngoại lệ.
 *   <li>Kiểm thử âm lớp 2: chính sách RLS viết sai {@code USING (true) WITH CHECK (true)} bị phát
 *       hiện rò rỉ.
 * </ul>
 */
class TenantIsolationCatalogIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    return List.of("keyring", "modulith", "task", "appraisal");
  }

  /** Kiểm thêm bảng phân vùng theo kỳ {@code rls_demo.sample_task} ở chế độ {@code pool}. */
  @Nested
  class RlsDemoPoolIT extends TenantIsolationCatalogTest {

    @Override
    protected List<String> schemas() {
      return List.of("rls_demo");
    }

    @Override
    protected MigrationOptions options() {
      return new MigrationOptions("classpath:db/common", "classpath:db/test-rls/", false);
    }
  }

  @Test
  void kiemThuAm_quenForceRlsLamLop1ThatBai() throws SQLException {
    String db = createIsolatedDatabase("td_neg_no_force");
    String url = POSTGRES.getJdbcUrl().replace("/td_rls_isolation", "/" + db);
    MigrationOptions badOpts =
        new MigrationOptions("classpath:db/common", "classpath:db/test-rls-bad/", false);
    prepareDatabase(url, List.of("bad_no_force"), badOpts, "pool", getClass().getClassLoader());

    try (Connection admin =
        DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      List<String> violations = verifyCatalog(admin, List.of("bad_no_force"), Map.of());
      assertThat(violations)
          .anyMatch(
              v ->
                  v.contains("bad_no_force.sample_record")
                      && v.contains("FORCE ROW LEVEL SECURITY"));
    }
  }

  @Test
  void kiemThuAm_chinhSachUsingTrueQuaLop1NhungLamLop2ThatBai() throws SQLException {
    String db = createIsolatedDatabase("td_neg_using_true");
    String url = POSTGRES.getJdbcUrl().replace("/td_rls_isolation", "/" + db);
    MigrationOptions badOpts =
        new MigrationOptions("classpath:db/common", "classpath:db/test-rls-bad/", false);
    prepareDatabase(url, List.of("bad_using_true"), badOpts, "pool", getClass().getClassLoader());

    try (Connection admin =
        DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      // Lớp 1 chỉ kiểm sự tồn tại của chính sách nên đi qua; lớp 2 phát hiện chính sách USING
      // (true).
      assertThat(verifyCatalog(admin, List.of("bad_using_true"), Map.of())).isEmpty();

      List<String> layer2Violations =
          verifyTwoTenantIsolation(url, admin, List.of("bad_using_true"), Map.of());
      assertThat(layer2Violations)
          .anyMatch(v -> v.contains("RÒ RỈ SELECT"))
          .anyMatch(v -> v.contains("RÒ RỈ INSERT"))
          .anyMatch(v -> v.contains("RÒ RỈ KHI THIẾU NGỮ CẢNH"));
    }
  }

  @Test
  void kiemThuAm_bangKhongCoTenantIdPhaiKhaiBaoNgoaiLe() throws SQLException {
    String db = createIsolatedDatabase("td_neg_no_tenant");
    String url = POSTGRES.getJdbcUrl().replace("/td_rls_isolation", "/" + db);
    MigrationOptions badOpts =
        new MigrationOptions("classpath:db/common", "classpath:db/test-rls-bad/", false);
    prepareDatabase(url, List.of("bad_no_tenant"), badOpts, "pool", getClass().getClassLoader());

    try (Connection admin =
        DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      List<String> violationsWithoutException =
          verifyCatalog(admin, List.of("bad_no_tenant"), Map.of());
      assertThat(violationsWithoutException)
          .anyMatch(
              v ->
                  v.contains("bad_no_tenant.global_dict")
                      && v.contains("tenantlessTableExceptions()"));

      List<String> violationsWithBlankReason =
          verifyCatalog(
              admin, List.of("bad_no_tenant"), Map.of("bad_no_tenant.global_dict", "   "));
      assertThat(violationsWithBlankReason).isNotEmpty();

      List<String> violationsWithValidReason =
          verifyCatalog(
              admin,
              List.of("bad_no_tenant"),
              Map.of("bad_no_tenant.global_dict", "Danh mục dùng chung không thuộc tenant"));
      assertThat(violationsWithValidReason).isEmpty();
    }
  }

  private static String createIsolatedDatabase(String dbName) throws SQLException {
    try (Connection root =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = root.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + dbName);
      st.execute("CREATE DATABASE " + dbName);
    }
    return dbName;
  }
}
