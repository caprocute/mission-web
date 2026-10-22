package vn.viettel.okrkpi.appraisal.application.review.port;

import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điều kiện bỏ qua cấp đề xuất của mẫu luồng ({@code flowable.wf_binding}, DV-06): đúng thì hồ sơ
 * đã thẩm định chuyển thẳng {@code CHO_QUYET_DINH}.
 */
@FunctionalInterface
public interface ProposalStep {

  boolean skipped(TenantId tenant, ProfileRow profile);
}
