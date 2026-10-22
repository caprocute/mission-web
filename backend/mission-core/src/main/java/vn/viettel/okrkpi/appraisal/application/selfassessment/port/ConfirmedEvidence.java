package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Minh chứng đang ở trạng thái {@code DA_XAC_NHAN} (MOD-03), dùng cho điều kiện HTXSNV. */
public interface ConfirmedEvidence {

  /** Tập con của {@code evidenceIds} đã được xác nhận. */
  Set<UUID> confirmed(TenantId tenant, UUID personId, Collection<UUID> evidenceIds);
}
