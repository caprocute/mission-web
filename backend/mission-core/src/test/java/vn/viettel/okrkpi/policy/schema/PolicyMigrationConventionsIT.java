package vn.viettel.okrkpi.policy.schema;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code policy} của MOD-05 (CORE-05-01). */
class PolicyMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("policy");
  }
}
