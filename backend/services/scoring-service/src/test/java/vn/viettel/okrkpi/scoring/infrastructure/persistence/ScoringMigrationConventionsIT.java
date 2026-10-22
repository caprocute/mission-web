package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code scoring} (DV-07-01, NEN-030). */
class ScoringMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("scoring");
  }
}
