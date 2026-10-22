package vn.viettel.okrkpi.dossier.infrastructure.migration;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema {@code dossier} (CORE-06-01). */
class DossierMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("dossier");
  }
}
