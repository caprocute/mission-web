package vn.viettel.okrkpi.platform.db.migration;

import java.util.List;

/**
 * Migration dùng chung của thư viện ({@code db/common}: hàm RLS, hàm phân vùng) cùng bảng mẫu theo
 * kỳ đạt mọi luật lint, khi chạy bằng {@code mig_<schema>} trên schema đã bootstrap.
 */
class CommonMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("rls_demo");
  }

  @Override
  protected MigrationOptions options() {
    return new MigrationOptions("classpath:db/common", "classpath:db/test-rls/", false);
  }
}
