package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 báo người được đánh giá đã nộp và ký thành công (MSG-09-04). */
public interface SelfAssessmentNotices {

  void submitted(TenantId tenant, UUID profileId, UUID personId, Instant signedAt, long version);
}
