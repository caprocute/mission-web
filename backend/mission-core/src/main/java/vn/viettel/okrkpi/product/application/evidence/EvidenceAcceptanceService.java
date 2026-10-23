package vn.viettel.okrkpi.product.application.evidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.NewAcceptance;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.SealedComment;
import vn.viettel.okrkpi.product.application.evidence.port.CommentSealer;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.domain.evidence.AcceptanceRules;
import vn.viettel.okrkpi.product.domain.evidence.CriterionResult;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Quyết định nghiệm thu của QT-07 (SCR-0703, CORE-03-07): xác nhận với mức chất lượng năm mức, yêu
 * cầu bổ sung, từ chối. Ghi {@code product.acceptance} (nhận xét C2 mã hoá phong bì, không ghi rõ
 * vào nhật ký hay sự kiện), đổi trạng thái phiên bản và liên động nhiệm vụ qua {@link
 * EvidenceLifecycleService}, ghi EVT-08 hoặc EVT-21 và sổ cái kiểm toán trong cùng giao dịch.
 *
 * <p>Thứ tự kiểm: người nghiệm thu độc lập (BR-07-06), trạng thái {@code DANG_NGHIEM_THU}, {@code
 * row_version}, AAL2 trong 15 phút với xác nhận (BR-07-11), toàn vẹn tệp với xác nhận (MSG-07-13),
 * rồi các trường của quyết định.
 */
public class EvidenceAcceptanceService {

  static final String TX = EvidenceLifecycleService.TX;

  /** Cửa sổ AAL2 của xác nhận nghiệm thu (BR-07-11). */
  static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);

  static final String OPERATION_ACCEPT = "acceptEvidenceVersion";

  /** Lý do gửi MOD-02 khi trả nhiệm vụ; không dùng nhận xét C2 để không lộ vào nhật ký nhiệm vụ. */
  static final String REASON_REVISION = "Minh chứng được yêu cầu bổ sung";

  static final String REASON_REJECT = "Minh chứng bị từ chối";

  private final EvidenceSubmissionRepository evidences;
  private final AcceptanceRepository acceptances;
  private final EvidenceLifecycleService lifecycle;
  private final EvidenceReviewService reviews;
  private final TaskApi tasks;
  private final EvidenceEvents events;
  private final CommentSealer sealer;
  private final AuditLogger audit;
  private final Clock clock;
  private ReviewerAuthority authority;

  public EvidenceAcceptanceService(
      EvidenceSubmissionRepository evidences,
      AcceptanceRepository acceptances,
      EvidenceLifecycleService lifecycle,
      EvidenceReviewService reviews,
      TaskApi tasks,
      EvidenceEvents events,
      CommentSealer sealer,
      AuditLogger audit,
      Clock clock) {
    this.evidences = Objects.requireNonNull(evidences, "evidences");
    this.acceptances = Objects.requireNonNull(acceptances, "acceptances");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.events = Objects.requireNonNull(events, "events");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.authority = ReviewerAuthority.withoutDelegations(tasks);
  }

  /** Cho phép người nhận uỷ quyền nghiệm thu thay quyết định (CORE-03-08). */
  public void setAuthority(ReviewerAuthority authority) {
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  /** Nội dung xác nhận nghiệm thu. */
  public record AcceptInput(
      BigDecimal qualityLevel,
      List<CriterionResult> criteria,
      String revisionCause,
      Boolean objectiveRevision,
      String comment) {}

  /** Bản ghi nghiệm thu vừa ghi (nhận xét bản rõ chỉ trả lại cho chính người nghiệm thu). */
  public record AcceptanceView(
      UUID id,
      UUID versionId,
      String decision,
      BigDecimal qualityLevel,
      List<CriterionResult> criteria,
      String revisionCause,
      boolean objectiveRevision,
      int revisionCount,
      String comment,
      UUID reviewerId,
      Instant decidedAt,
      UUID onBehalfOf,
      UUID delegationId) {}

  /** Kết quả quyết định: bản ghi, trạng thái phiên bản, trạng thái nhiệm vụ mới (nếu đổi). */
  public record Decision(
      AcceptanceView acceptance, String versionStatus, String taskStatusChanged, long rowVersion) {}

  private record Context(
      EvidenceView evidence,
      VersionView version,
      TaskSummary task,
      ReviewerAuthority.Acting acting) {}

  @Transactional(TX)
  public Decision acceptVersion(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      AcceptInput input,
      EvidenceActor actor) {
    Context c = load(tenant, evidenceId, versionNo, expectedRowVersion, actor);
    requireStepUp(actor);
    reviews.requireIntact(tenant, c.evidence(), c.version(), c.task(), actor.personId());
    int revisions =
        acceptances.priorRevisions(tenant, c.version().periodBucket(), evidenceId, versionNo);
    Set<String> required =
        acceptances.priorCriteria(tenant, c.version().periodBucket(), evidenceId, versionNo);
    try {
      AcceptanceRules.checkAccept(
          input.qualityLevel(),
          input.criteria(),
          required,
          revisions > 0,
          input.revisionCause(),
          input.objectiveRevision(),
          input.comment());
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    EvidenceLifecycleService.Result result =
        lifecycle.acceptVersion(tenant, c.version().id(), expectedRowVersion, actor);
    Instant now = clock.instant();
    AcceptanceView view =
        write(
            tenant,
            c,
            EvidenceStatus.DA_XAC_NHAN.name(),
            input.qualityLevel(),
            input.criteria(),
            revisions > 0 ? input.revisionCause() : null,
            revisions > 0 && Boolean.TRUE.equals(input.objectiveRevision()),
            revisions,
            input.comment(),
            actor,
            now);
    events.accepted(
        new EvidenceEvents.Accepted(
            tenant,
            evidenceId,
            c.version().id(),
            c.version().taskId(),
            input.qualityLevel(),
            actor.personId(),
            now,
            result.rowVersion()));
    return decision(view, result);
  }

  @Transactional(TX)
  public Decision requestRevision(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      List<CriterionResult> criteria,
      String comment,
      EvidenceActor actor) {
    return giveBack(
        tenant, evidenceId, versionNo, expectedRowVersion, criteria, comment, actor, false);
  }

  @Transactional(TX)
  public Decision rejectVersion(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      List<CriterionResult> criteria,
      String comment,
      EvidenceActor actor) {
    return giveBack(
        tenant, evidenceId, versionNo, expectedRowVersion, criteria, comment, actor, true);
  }

  private Decision giveBack(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      List<CriterionResult> criteria,
      String comment,
      EvidenceActor actor,
      boolean reject) {
    Context c = load(tenant, evidenceId, versionNo, expectedRowVersion, actor);
    List<CriterionResult> given = criteria == null ? List.of() : criteria;
    try {
      AcceptanceRules.checkReturn(given, comment);
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    int revisions =
        acceptances.priorRevisions(tenant, c.version().periodBucket(), evidenceId, versionNo);
    EvidenceLifecycleService.Result result =
        reject
            ? lifecycle.rejectVersion(
                tenant, c.version().id(), expectedRowVersion, REASON_REJECT, actor)
            : lifecycle.requestRevision(
                tenant, c.version().id(), expectedRowVersion, REASON_REVISION, actor);
    AcceptanceView view =
        write(
            tenant,
            c,
            reject ? EvidenceStatus.TU_CHOI.name() : EvidenceStatus.YEU_CAU_BO_SUNG.name(),
            null,
            given,
            null,
            false,
            reject ? revisions : revisions + 1,
            comment,
            actor,
            clock.instant());
    if (c.version().submittedBy() != null) {
      events.returned(
          tenant,
          c.version().submittedBy(),
          reject,
          c.task().code(),
          evidenceId,
          versionNo,
          actor.personId(),
          result.rowVersion());
    }
    return decision(view, result);
  }

  private Context load(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      EvidenceActor actor) {
    EvidenceView e =
        evidences.findEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    VersionView v =
        evidences
            .findVersion(tenant, e.periodBucket(), evidenceId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    TaskSummary task = tasks.find(tenant, e.taskId()).orElseThrow(NoSuchElementException::new);
    ReviewerAuthority.Acting acting;
    try {
      acting = authority.resolve(tenant, task, actor.personId(), v.submittedBy());
      if (EvidenceStatus.valueOf(v.status()) != EvidenceStatus.DANG_NGHIEM_THU) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.INVALID_STATE,
            "Phiên bản minh chứng chưa được mở nghiệm thu hoặc đã có quyết định");
      }
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    if (v.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("PRODUCT", expectedRowVersion, v.rowVersion());
    }
    return new Context(e, v, task, acting);
  }

  /** AAL2 trong 15 phút gần nhất (BR-07-11). */
  private void requireStepUp(EvidenceActor actor) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(actor.acr()))) {
      throw new StepUpRequiredException(
          OPERATION_ACCEPT, maxAge, "Xác nhận nghiệm thu cần xác thực nhiều lớp.");
    }
    long age = clock.instant().getEpochSecond() - (actor.authTime() == null ? 0 : actor.authTime());
    if (actor.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          OPERATION_ACCEPT, maxAge, "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  private AcceptanceView write(
      TenantId tenant,
      Context c,
      String decision,
      BigDecimal quality,
      List<CriterionResult> criteria,
      String revisionCause,
      boolean objective,
      int revisionCount,
      String comment,
      EvidenceActor actor,
      Instant at) {
    UUID id = UUID.randomUUID();
    SealedComment sealed = sealer.seal(tenant, id, comment);
    VersionView v = c.version();
    acceptances.insertAcceptance(
        tenant,
        new NewAcceptance(
            id,
            v.periodId(),
            v.periodBucket(),
            v.id(),
            v.taskId(),
            actor.personId(),
            decision,
            quality,
            revisionCause,
            objective,
            revisionCount,
            criteria,
            sealed,
            at,
            c.acting().substitute() ? c.acting().onBehalfOf() : null,
            c.acting().substitute() ? c.acting().delegationId() : null));
    audit.record(
        AuditEvent.builder(
                AuditEventType.LEGAL_MILESTONE,
                ActorId.of(actor.personId()),
                "EVIDENCE_" + decision)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object("product_acceptance", id.toString())
            .attribute("evidenceVersionId", v.id().toString())
            .attribute("qualityLevel", quality == null ? "" : quality.toPlainString())
            .attribute("nghiemThuThay", Boolean.toString(c.acting().substitute()))
            .attribute(
                "onBehalfOf", c.acting().substitute() ? c.acting().onBehalfOf().toString() : "")
            .attribute(
                "delegationId", c.acting().substitute() ? c.acting().delegationId().toString() : "")
            .build());
    return new AcceptanceView(
        id,
        v.id(),
        decision,
        quality,
        criteria,
        revisionCause,
        objective,
        revisionCount,
        comment == null || comment.isBlank() ? null : comment,
        actor.personId(),
        at,
        c.acting().substitute() ? c.acting().onBehalfOf() : null,
        c.acting().substitute() ? c.acting().delegationId() : null);
  }

  private static Decision decision(AcceptanceView view, EvidenceLifecycleService.Result result) {
    String taskStatus =
        result
            .taskOutcome()
            .filter(o -> o.outcome() == Outcome.APPLIED)
            .map(o -> o.task().status())
            .orElse(null);
    return new Decision(view, result.version().status().name(), taskStatus, result.rowVersion());
  }
}
