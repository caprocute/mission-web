package vn.viettel.okrkpi.replica;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;

/** Lint quy ước migration cho schema bản sao tổ chức {@code org_replica} (CORE-00-03). */
class OrgReplicaMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("org_replica");
  }
}
