package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskProductCounts;

/** Cung cấp số sản phẩm của nhiệm vụ cho chi tiết nhiệm vụ của MOD-02 (SCR-0601). */
@Component
public class ProductCountsAdapter implements TaskProductCounts {

  private final EvidenceSubmissionService submissions;

  public ProductCountsAdapter(EvidenceSubmissionService submissions) {
    this.submissions = Objects.requireNonNull(submissions, "submissions");
  }

  @Override
  public Counts of(TenantId tenant, UUID taskId) {
    int[] c = submissions.productCounts(tenant, taskId);
    return new Counts(c[0], c[1], c[2]);
  }
}
