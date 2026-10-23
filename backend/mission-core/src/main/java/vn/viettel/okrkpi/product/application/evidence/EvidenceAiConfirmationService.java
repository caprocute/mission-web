package vn.viettel.okrkpi.product.application.evidence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.AiDecisionPlan;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.SubmitOutcome;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions.Decision;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nộp minh chứng có ô do AI điền (CORE-03-05, BR-07-09): trước giao dịch nộp, ghi quyết định của
 * người nộp với gợi ý OCR ở SVC-14 ({@code CHAP_NHAN} khi giữ nguyên mọi ô, {@code SUA} khi sửa ít
 * nhất một ô), rồi mới nộp. SVC-14 quá thời hạn hay lỗi thì trả 503 có thể thử lại và phiên bản vẫn
 * ở {@code NHAP}; lần nộp lại dùng cùng {@code Idempotency-Key} nên không ghi hai quyết định. Lời
 * gọi HTTP nằm ngoài giao dịch CSDL để không giữ kết nối trong lúc chờ SVC-14.
 */
public class EvidenceAiConfirmationService {

  private static final Logger LOG = LoggerFactory.getLogger(EvidenceAiConfirmationService.class);

  /** Tiền tố bản ghi nhận gợi ý ({@code ai.suggestion_log.target_ref}). */
  static final String TARGET_PREFIX = "product.evidence_version:";

  private final EvidenceSubmissionService submissions;
  private final AiSuggestionDecisions decisions;

  /**
   * @param decisions {@code null} khi chưa cấu hình SVC-14 (môi trường thử): bỏ qua ghi quyết định,
   *     xác nhận vẫn được ghi sổ cái
   */
  public EvidenceAiConfirmationService(
      EvidenceSubmissionService submissions, AiSuggestionDecisions decisions) {
    this.submissions = Objects.requireNonNull(submissions, "submissions");
    this.decisions = decisions;
  }

  public SubmitOutcome submit(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      boolean attested,
      boolean aiFieldsConfirmed,
      EvidenceActor actor,
      String authorization) {
    Optional<AiDecisionPlan> plan =
        submissions.aiDecisionPlan(tenant, evidenceId, versionNo, aiFieldsConfirmed);
    plan.ifPresent(p -> decide(tenant, p, authorization));
    return submissions.submit(
        tenant, evidenceId, versionNo, expectedRowVersion, attested, aiFieldsConfirmed, actor);
  }

  private void decide(TenantId tenant, AiDecisionPlan p, String authorization) {
    if (decisions == null) {
      LOG.warn("Chưa cấu hình SVC-14: không ghi quyết định gợi ý {}", p.aiSuggestionId());
      return;
    }
    try {
      decisions.decide(
          tenant,
          p.aiSuggestionId(),
          p.edited().isEmpty() ? Decision.CHAP_NHAN : Decision.SUA,
          TARGET_PREFIX + p.versionId(),
          authorization,
          "xac-nhan-o-ai-" + p.versionId());
    } catch (AiSuggestionDecisions.SuggestionNotFoundException e) {
      throw new BusinessException(
          EvidenceRuleViolation.AI_NOT_CONFIRMED,
          "Không xác nhận được thông tin do hệ thống gợi ý; vui lòng đọc lại văn bản hoặc tự nhập");
    }
  }
}
