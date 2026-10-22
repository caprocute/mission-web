package vn.viettel.okrkpi.org.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.support.PostgresSupport;

/**
 * DL-07: migration lặp nạp 9 khối đối tượng và danh mục chức danh quốc gia cho tenant hệ thống (QT-01, BR-01-05,
 * PL 2A, PL 2B). Chạy lại không tạo dòng trùng, không tăng {@code row_version} khi dữ liệu không đổi, và trả lại
 * FORCE RLS sau khi nạp.
 */
class NationalCatalogSeedIT {

  private static final String TENANT_HE_THONG = "00000000-0000-0000-0000-000000000000";
  private static final Path MIGRATION = Path.of("src/main/resources/db/migration/org/R__org_danh_muc_quoc_gia.sql");

  @Test
  void napLaiNhieuLanVanDungSoDongVaKhongDoiPhienBan() throws Exception {
    PostgresSupport.start(); // Flyway đã chạy R__ một lần
    long khoi = dongDuLieu("subject_group.csv");
    long chucDanh = dongDuLieu("job_title.csv");
    String sql = Files.readString(MIGRATION);
    chayBangMigOrg(sql);
    chayBangMigOrg(sql);
    try (Connection c = PostgresSupport.admin(); Statement s = c.createStatement()) {
      assertThat(dem(s, "SELECT count(*) FROM org.subject_group WHERE tenant_id = '" + TENANT_HE_THONG + "'"))
          .isEqualTo(khoi).isEqualTo(9);
      assertThat(dem(s, "SELECT count(*) FROM org.job_title WHERE tenant_id = '" + TENANT_HE_THONG + "'"))
          .isEqualTo(chucDanh);
      assertThat(dem(s, "SELECT max(row_version) FROM org.subject_group WHERE tenant_id = '" + TENANT_HE_THONG + "'"))
          .isZero();
      assertThat(dem(s, "SELECT count(*) FROM org.job_title j JOIN org.subject_group g ON g.id = j.subject_group_id "
          + "WHERE j.tenant_id = '" + TENANT_HE_THONG + "' AND g.tenant_id = j.tenant_id"))
          .isEqualTo(dem(s, "SELECT count(*) FROM org.job_title WHERE tenant_id = '" + TENANT_HE_THONG
              + "' AND subject_group_id IS NOT NULL"));
      assertThat(dem(s, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
          + "WHERE n.nspname = 'org' AND c.relname IN ('subject_group','job_title') AND c.relforcerowsecurity"))
          .isEqualTo(2);
    }
  }

  @Test
  void svcOrgChiDocDuocDanhMucKhiNguCanhLaTenantHeThong() throws Exception {
    PostgresSupport.start();
    chayBangMigOrg(Files.readString(MIGRATION)); // kiểm thử khác có thể đã dọn bảng
    try (Connection c = PostgresSupport.as("svc_org", PostgresSupport.SVC_PASSWORD);
        Statement s = c.createStatement()) {
      s.execute("SET app.tenant_id = '" + TENANT_HE_THONG + "'");
      assertThat(dem(s, "SELECT count(*) FROM org.subject_group")).isEqualTo(9);
      s.execute("SET app.tenant_id = '00000000-0000-7000-8000-0000000000ff'");
      assertThat(dem(s, "SELECT count(*) FROM org.subject_group WHERE tenant_id = '" + TENANT_HE_THONG + "'"))
          .isZero();
    }
  }

  private static void chayBangMigOrg(String sql) throws SQLException {
    try (Connection c = PostgresSupport.as("mig_org", PostgresSupport.MIG_PASSWORD)) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute(sql);
      }
      c.commit();
    }
  }

  private static long dem(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long dongDuLieu(String tep) throws IOException {
    Path p = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(p.resolve("data/seed/org"))) {
      p = p.getParent();
    }
    List<String> dong = Files.readAllLines(p.resolve("data/seed/org").resolve(tep));
    return dong.stream().skip(1).filter(l -> !l.isBlank()).count();
  }
}
