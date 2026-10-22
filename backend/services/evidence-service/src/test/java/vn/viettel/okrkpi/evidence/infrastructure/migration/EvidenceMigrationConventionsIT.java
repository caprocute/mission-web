package vn.viettel.okrkpi.evidence.infrastructure.migration;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code evidence} (DV-08-01). */
class EvidenceMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("evidence");
  }
}
