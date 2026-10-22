package vn.viettel.okrkpi.task.infrastructure.migration;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code task} (CORE-02-01). */
class TaskMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("task");
  }
}
