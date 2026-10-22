package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;

/** Bảng chuyển trạng thái áp cho nhiệm vụ của một kỳ (TL-01 {@code TransitionTableProvider}). */
@FunctionalInterface
public interface TransitionTableProvider {

  TransitionTable tableFor(TenantId tenant, UUID periodId);
}
