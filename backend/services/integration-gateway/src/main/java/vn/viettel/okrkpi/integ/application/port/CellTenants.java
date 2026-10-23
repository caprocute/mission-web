package vn.viettel.okrkpi.integ.application.port;

import java.util.UUID;

/** Tenant có thuộc cell này không (bản sao danh bạ tenant toàn cục). */
@FunctionalInterface
public interface CellTenants {

  boolean hosts(UUID tenantId);
}
