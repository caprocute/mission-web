package vn.viettel.okrkpi.task.application.plan;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalEvents;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovalRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.SignedTarget;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures;
import vn.viettel.okrkpi.task.domain.plan.PlanCommand;
import vn.viettel.okrkpi.task.domain.plan.PlanTransition;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStateMachine;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * Duyệt kế hoạch quý có ký số (QT-04 bước 7a, SCR-0403, CORE-02-09). Bước duyệt kiểm bốn mắt
 * (BR-04-08), xác thực bước lên AAL2 trong 15 phút, điểm của việc ngoài danh mục (BR-04-05) và băm
 * nội dung đã khoá, rồi tạo yêu cầu ký trên SVC-10; phiên bản vẫn ở {@code CHO_DUYET}. Chỉ khi nhận
 * EVT-18 với băm khớp, phiên bản mới chuyển {@code DA_DUYET}, thành phiên bản hiệu lực và phát
 * EVT-03 trong cùng giao dịch; ký lỗi hoặc hết hạn thì giữ {@code CHO_DUYET} để duyệt và ký lại.
 */
public class PlanApprovalService {

  public static final String SAME_AUTHOR = "TASK-403-SAME-AUTHOR";
  public static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);
  static final String OPERATION = "approvePlanVersion";
  static final Set<String> SIGN_METHODS = Set.of("MYSIGN", "USB_TOKEN", "CHUNG_THU_TO_CHUC");

  private static final Logger LOG = LoggerFactory.getLogger(PlanApprovalService.class);

  private final PlanRepository plans;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final PlanSignatures signatures;
  private final PlanApprovalEvents events;
  private final PlanNotices notices;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  public PlanApprovalService(
      PlanRepository plans,
      PeriodApi periods,
      OrgDirectoryApi org,
      PlanSignatures signatures,
      PlanApprovalEvents events,
      PlanNotices notices,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = org;
    this.signatures = Objects.requireNonNull(signatures, "signatures");
    this.events = Objects.requireNonNull(events, "events");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Người duyệt kèm mức xác thực ({@code acr}) và thời điểm xác thực ({@code auth_time}). */
  public record Approver(UUID personId, String acr, Long authTime) {
    public Approver {
      Objects.requireNonNull(personId, "personId");
    }
  }

  /** Lệnh duyệt của hợp đồng {@code approvePlanVersion}. */
  public record ApproveCommand(
      Approver approver,
      String signMethod,
      String comment,
      String idempotencyKey,
      String callerToken) {}

  /** Yêu cầu ký đã tạo; phiên bản vẫn {@code CHO_DUYET} tới khi có EVT-18. */
  public record Started(UUID signRequestId, long rowVersion) {}

  /**
   * Duyệt và mở ký số. Kiểm theo thứ tự: kỳ còn ghi được, khoá lạc quan, trạng thái {@code
   * CHO_DUYET}, bốn mắt, người duyệt có thẩm quyền, xác thực bước lên, BR-04-05, băm nội dung.
   */
  @Transactional(PlanService.TX)
  public Started approve(
      TenantId tenant, UUID planId, int versionNo, long expectedVersion, ApproveCommand cmd) {
    if (cmd.signMethod() == null || !SIGN_METHODS.contains(cmd.signMethod())) {
      throw new BusinessException(
          PlanService.VALIDATION,
          "Vui lòng chọn phương thức ký số",
          List.of(new ProblemFieldError("signMethod", "Phương thức ký không hợp lệ", null)));
    }
    PlanRow plan = plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version =
        plans
            .lockVersion(tenant, plan.periodBucket(), planId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    if (version.rowVersion() != expectedVersion) {
      throw new VersionConflictException("TASK", expectedVersion, version.rowVersion());
    }
    PlanTransition result =
        PlanVersionStateMachine.apply(version.status(), PlanCommand.APPROVE, true);
    if (result instanceof PlanTransition.Rejected r) {
      throw new BusinessException(PlanService.INVALID_TRANSITION, r.message());
    }
    UUID actor = cmd.approver().personId();
    ApprovalRow approval =
        plans
            .approvalRow(tenant, plan.periodBucket(), version.id())
            .orElseThrow(NoSuchElementException::new);
    if (actor.equals(approval.createdBy())) {
      throw new BusinessException(SAME_AUTHOR, "Người lập kế hoạch không được tự duyệt");
    }
    if (org != null && !org.headsOf(tenant, plan.ownerUnitId(), today()).contains(actor)) {
      throw new BusinessException(
          PlanReviewService.OUT_OF_SCOPE, "Chỉ người duyệt kế hoạch được duyệt");
    }
    requireStepUp(cmd.approver());
    List<ItemRow> items = plans.items(tenant, plan.periodBucket(), version.id());
    List<ProblemFieldError> unscored = unscored(items);
    if (!unscored.isEmpty()) {
      throw new BusinessException(
          PlanService.VALIDATION,
          "Còn " + unscored.size() + " công việc ngoài danh mục chưa được định điểm",
          unscored);
    }
    byte[] hash = PlanContentHash.of(plan, versionNo, items);
    if (version.contentHash() == null || !Arrays.equals(hash, version.contentHash())) {
      throw new BusinessException(
          PlanService.INVALID_TRANSITION, "Nội dung kế hoạch đã thay đổi sau khi gửi duyệt");
    }
    UUID signRequestId =
        signatures.request(
            new PlanSignatures.Request(
                tenant,
                planId,
                version.id(),
                versionNo,
                cmd.signMethod(),
                actor,
                cmd.idempotencyKey(),
                cmd.callerToken()));
    long rowVersion = plans.attachSignRequest(tenant, plan, version, signRequestId, actor);
    return new Started(signRequestId, rowVersion);
  }

  /** Chữ ký EVT-18 cho một phiên bản kế hoạch. */
  public record Signed(
      UUID signRequestId,
      UUID versionId,
      List<byte[]> documentSha256,
      UUID signer,
      Instant signedAt) {}

  /** Kết quả áp EVT-18. */
  public enum Outcome {
    APPROVED,
    ALREADY_APPROVED,
    IGNORED
  }

  /**
   * Áp EVT-18: băm khớp {@code content_hash} và yêu cầu ký khớp phiên bản thì chuyển {@code
   * DA_DUYET}, đặt phiên bản hiệu lực, phát EVT-03 và EVT-21 MSG-04-13 trong cùng giao dịch. Sự
   * kiện lặp hoặc lệch chỉ ghi cảnh báo, không đổi trạng thái.
   */
  @Transactional(PlanService.TX)
  public Outcome onSigned(TenantId tenant, Signed signed) {
    Optional<SignedTarget> found = plans.versionById(tenant, signed.versionId(), true);
    if (found.isEmpty()) {
      LOG.warn("EVT-18 {} trỏ phiên bản kế hoạch không có", signed.signRequestId());
      return Outcome.IGNORED;
    }
    SignedTarget target = found.get();
    VersionRow version = target.version();
    if (version.status() == PlanVersionStatus.DA_DUYET) {
      return Outcome.ALREADY_APPROVED;
    }
    if (version.status() != PlanVersionStatus.CHO_DUYET
        || !signed.signRequestId().equals(target.approval().signRequestId())) {
      LOG.warn(
          "EVT-18 {} không khớp yêu cầu ký đang chờ của phiên bản {} ({})",
          signed.signRequestId(),
          version.id(),
          version.status());
      return Outcome.IGNORED;
    }
    boolean matches =
        version.contentHash() != null
            && signed.documentSha256().size() == 1
            && Arrays.equals(version.contentHash(), signed.documentSha256().get(0));
    if (!matches) {
      LOG.warn(
          "EVT-18 {} có băm lệch nội dung đã khoá của phiên bản {}: không duyệt",
          signed.signRequestId(),
          version.id());
      return Outcome.IGNORED;
    }
    PlanRow plan = target.plan();
    PlanRepository.Approved approved =
        plans.markApproved(tenant, plan, version, signed.signer(), signed.signedAt());
    PlanVersionApprovedEvent event =
        new PlanVersionApprovedEvent(
            tenant,
            plan.id(),
            version.id(),
            version.versionNo(),
            plan.periodId(),
            plan.planType(),
            plan.ownerPersonId() != null && "CA_NHAN".equals(plan.planType())
                ? plan.ownerPersonId()
                : plan.ownerUnitId(),
            version.itemCount(),
            signed.signer(),
            signed.signRequestId(),
            approved.supersededVersionId(),
            approved.rowVersion(),
            clock.instant());
    events.approved(event);
    String periodName =
        periods.findById(tenant, PeriodId.of(plan.periodId())).map(PeriodSnapshot::name).orElse("");
    notices.approved(
        tenant,
        new PendingPlan(
            plan.id(),
            plan.ownerUnitId(),
            version.id(),
            target.approval().createdBy(),
            approved.rowVersion()),
        periodName,
        signed.signer());
    publisher.publishEvent(event);
    return Outcome.APPROVED;
  }

  /** Cổng nguồn ký của SVC-10: phiên bản sẵn sàng ký, người được ký và băm đã khoá. */
  public record SignSource(boolean ready, Set<UUID> signers, byte[] contentHash) {}

  @Transactional(transactionManager = PlanService.TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID versionId) {
    return plans
        .versionById(tenant, versionId, false)
        .map(
            t -> {
              Set<UUID> signers = new LinkedHashSet<>();
              if (org != null) {
                signers.addAll(org.headsOf(tenant, t.plan().ownerUnitId(), today()));
              }
              signers.remove(t.approval().createdBy());
              boolean ready =
                  t.version().status() == PlanVersionStatus.CHO_DUYET
                      && t.version().contentHash() != null;
              return new SignSource(ready, signers, t.version().contentHash());
            });
  }

  /** Tác vụ {@code approvePlanVersion}: người duyệt đã mở yêu cầu ký này, nếu có. */
  @Transactional(transactionManager = PlanService.TX, readOnly = true)
  public Optional<UUID> signRequestInitiator(TenantId tenant, UUID signRequestId) {
    return plans.signRequestInitiator(tenant, signRequestId);
  }

  /** Mã yêu cầu ký đang gắn của phiên bản (để trả {@code PlanVersion.signRequestId}). */
  @Transactional(transactionManager = PlanService.TX, readOnly = true)
  public Optional<UUID> signRequestOf(TenantId tenant, int periodBucket, UUID versionId) {
    return plans.approvalRow(tenant, periodBucket, versionId).map(ApprovalRow::signRequestId);
  }

  private void requireStepUp(Approver approver) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(approver.acr()))) {
      throw new StepUpRequiredException(
          OPERATION, maxAge, "Duyệt kế hoạch cần xác thực nhiều lớp trong 15 phút gần nhất.");
    }
    long age =
        clock.instant().getEpochSecond() - (approver.authTime() == null ? 0 : approver.authTime());
    if (approver.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          OPERATION, maxAge, "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  /** BR-04-05: dòng ngoài danh mục phải có độ khó và điểm chuẩn trước khi duyệt. */
  static List<ProblemFieldError> unscored(List<ItemRow> items) {
    return items.stream()
        .filter(i -> i.catalogItemId() == null)
        .filter(i -> i.difficulty() == null || i.standardScore() == null)
        .map(
            i ->
                new ProblemFieldError(
                    "items[" + (i.itemNo() - 1) + "]",
                    "Công việc ngoài danh mục chưa được định độ khó và điểm chuẩn",
                    "BR-04-05"))
        .toList();
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(PlanDeadlines.ZONE));
  }
}
