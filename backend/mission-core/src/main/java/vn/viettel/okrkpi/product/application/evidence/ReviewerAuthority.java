package vn.viettel.okrkpi.product.application.evidence;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.DelegationApi;
import vn.viettel.okrkpi.config.api.DelegationApi.DelegationGrant;
import vn.viettel.okrkpi.product.domain.evidence.ReviewRules;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Ai được mở và quyết định nghiệm thu một phiên bản (BR-07-06, CORE-03-08): người nghiệm thu được
 * chỉ định, hoặc người nhận uỷ quyền {@code NGHIEM_THU_THAY} đang hiệu lực của người đó (cổng uỷ
 * quyền của MOD-08, CORE-08-16). Người làm thay vẫn phải độc lập: không là người nộp, người chủ trì
 * hay người phối hợp của nhiệm vụ. Đây là lớp phòng thủ thứ hai sau OPA (BR-01-18).
 */
public class ReviewerAuthority {

  /** Người quyết định và, khi nghiệm thu thay, người được thay cùng uỷ quyền đã dùng. */
  public record Acting(UUID reviewerId, UUID onBehalfOf, UUID delegationId) {
    public boolean substitute() {
      return onBehalfOf != null;
    }
  }

  private final TaskApi tasks;
  private final DelegationApi delegations;
  private final Clock clock;

  public ReviewerAuthority(TaskApi tasks, DelegationApi delegations, Clock clock) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.delegations = delegations;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Không có cổng uỷ quyền: chỉ người nghiệm thu được chỉ định. */
  public static ReviewerAuthority withoutDelegations(TaskApi tasks) {
    return new ReviewerAuthority(tasks, null, Clock.systemUTC());
  }

  /**
   * Kiểm người gọi; ném {@link vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation}
   * {@code PRODUCT-403-SELF-REVIEW} khi không đủ tư cách.
   */
  public Acting resolve(TenantId tenant, TaskSummary task, UUID me, UUID submitter) {
    UUID designated = EvidenceSubmissionService.designatedReviewer(task);
    if (designated != null && !me.equals(designated) && delegations != null) {
      Optional<DelegationGrant> grant =
          delegations.activeFor(
              tenant,
              DelegationApi.Kind.NGHIEM_THU_THAY,
              designated,
              me,
              null,
              null,
              clock.instant());
      if (grant.isPresent()) {
        ReviewRules.requireIndependentReviewer(
            me,
            me,
            submitter,
            task.leadAssigneeId(),
            EvidenceReviewService.coordinates(tasks, tenant, task, me));
        return new Acting(me, designated, grant.get().id());
      }
    }
    ReviewRules.requireIndependentReviewer(
        me,
        designated,
        submitter,
        task.leadAssigneeId(),
        me.equals(designated) && EvidenceReviewService.coordinates(tasks, tenant, task, me));
    return new Acting(me, null, null);
  }
}
