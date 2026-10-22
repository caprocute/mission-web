package vn.viettel.okrkpi.config.schema;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema cấu hình {@code config} của MOD-08 (CORE-08-01). */
class ConfigMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("config");
  }
}
