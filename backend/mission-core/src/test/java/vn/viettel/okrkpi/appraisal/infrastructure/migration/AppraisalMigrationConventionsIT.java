package vn.viettel.okrkpi.appraisal.infrastructure.migration;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code appraisal} (CORE-04-01). */
class AppraisalMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("appraisal");
  }
}
