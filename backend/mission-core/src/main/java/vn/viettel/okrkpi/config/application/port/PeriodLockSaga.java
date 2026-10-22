package vn.viettel.okrkpi.config.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khởi động saga {@code CHOT_KY} ở SVC-06 ({@code startPeriodCloseSaga} của {@code platform.yaml}),
 * idempotent theo lệnh khoá kỳ và khoá {@code CK-…}.
 */
public interface PeriodLockSaga {

  /** Saga đã có cho kỳ ({@code WORKFLOW-409-SAGA-EXISTS}). */
  final class SagaExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SagaExistsException(Throwable cause) {
      super("Kỳ đã có saga chốt kỳ", cause);
    }
  }

  /** Mã saga ({@code flowable.saga_instance.id}), cũng là mã job ở {@code /api/v1/jobs/{id}}. */
  UUID start(TenantId tenantId, PeriodId periodId, UUID lockOrderId, UUID requestedBy, String key);
}
