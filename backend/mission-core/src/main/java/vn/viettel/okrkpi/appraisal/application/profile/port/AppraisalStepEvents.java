package vn.viettel.okrkpi.appraisal.application.profile.port;

import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng phát EVT-11 {@code HoSoDanhGiaChuyenBuoc} vào outbox trong giao dịch của bên gọi. */
public interface AppraisalStepEvents {

  void stepCompleted(TenantId tenant, UUID profileId, StepCompleted step, long aggregateVersion);
}
