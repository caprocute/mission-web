package vn.viettel.okrkpi.okr.infrastructure.migration;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code okr} của MOD-01 (CORE-01-01). */
class OkrMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("okr");
  }
}
