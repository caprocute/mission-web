package vn.viettel.okrkpi.replica;

import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/** Kiểm thử rò rỉ tenant (RLS) cho sáu bảng của schema {@code org_replica} (CORE-00-03). */
class OrgReplicaTenantIsolationIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    return List.of("org_replica");
  }
}
