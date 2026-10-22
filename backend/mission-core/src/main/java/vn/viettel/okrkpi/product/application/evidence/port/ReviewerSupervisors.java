package vn.viettel.okrkpi.product.application.evidence.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cấp trên của một cán bộ theo cây tổ chức của bản sao {@code org_replica} (BR-07-12). */
@FunctionalInterface
public interface ReviewerSupervisors {

  /** Trưởng đơn vị công tác chính; người đó là trưởng thì lấy trưởng đơn vị cấp trên. */
  List<UUID> supervisorsOf(TenantId tenant, UUID personId, LocalDate on);
}
