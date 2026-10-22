package vn.viettel.okrkpi.appraisal.application.review.port;

import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Người đã nghiệm thu minh chứng của người được đánh giá trong kỳ (MOD-03, BR-09-14). */
public interface EvidenceAcceptors {

  Set<UUID> of(TenantId tenant, UUID periodId, UUID personId);
}
