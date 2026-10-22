package vn.viettel.okrkpi.policy.application;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovers;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyTransitionException;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trình và phê duyệt phát hành Policy Pack (CORE-05-09, QT-15, SCR-1503): {@code
 * submitPolicyPackVersion}, {@code approvePolicyPackVersion}, {@code rejectPolicyPackVersion}. Gửi
 * chỉ từ {@code DAT_KIEM_THU} với lần chạy gần nhất khớp hai engine (BR-15-07) và kỳ hiệu lực ở
 * tương lai chưa khoá (BR-15-10); phê duyệt cần người khác người soạn (BR-15-09), phiên AAL2 trong
 * 15 phút, ký trên {@code content_hash}, ghi sổ cái qua outbox (BR-15-14) rồi khởi chạy saga phát
 * hành (CORE-05-10).
 */
public class PolicyApprovalService {

  static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);
  static final Set<String> FLOWS = Set.of("DANG", "NHA_NUOC", "CA_HAI");
  private static final int TEXT_MAX = 2000;
  private static final int REASON_MIN = 10;

  /** Người gọi kèm thông tin phiên xác thực để kiểm bước lên. */
  public record Approver(UUID userId, String acr, Long authTime) {
    public Approver {
      Objects.requireNonNull(userId, "userId");
    }
  }

  /** Lệnh gửi phê duyệt theo {@code PolicyPackSubmit}. */
  public record Submit(UUID effectivePeriodId, List<Scope> scopes, String note) {}

  /** Kết quả phê duyệt: phiên bản đã ký và tác vụ phát hành. */
  public record Approved(PolicyPackVersion version, String jobId) {}

  private final PolicyPackRepository packs;
  private final PolicyPublishRepository publish;
  private final PolicyTestRunRepository runs;
  private final PeriodApi periods;
  private final PolicyPackSigner signer;
  private final PolicyPublishLauncher launcher;
  private final PolicyApprovalNotices notices;
  private final PolicyApprovers approvers;
  private final PolicyAudit audit;
  private final Clock clock;

  public PolicyApprovalService(
      PolicyPackRepository packs,
      PolicyPublishRepository publish,
      PolicyTestRunRepository runs,
      PeriodApi periods,
      PolicyPackSigner signer,
      PolicyPublishLauncher launcher,
      PolicyApprovalNotices notices,
      PolicyApprovers approvers,
      PolicyAudit audit,
      Clock clock) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.publish = Objects.requireNonNull(publish, "publish");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.approvers = Objects.requireNonNull(approvers, "approvers");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Gửi phê duyệt phát hành: {@code DAT_KIEM_THU} → {@code CHO_PHE_DUYET}, phát EVT-21. */
  @Transactional(PolicyPackService.TX)
  public PolicyPackVersion submit(
      TenantId tenant, UUID actor, UUID packId, String version, long expected, Submit cmd) {
    Objects.requireNonNull(cmd, "cmd");
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current = current(tenant, packId, version, expected);
    requireTested(tenant, current);
    String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note().strip();
    if (note != null && note.length() > TEXT_MAX) {
      throw invalid("note", "Ghi chú tối đa 2000 ký tự");
    }
    List<Scope> scopes = scopes(cmd.scopes());
    if (cmd.effectivePeriodId() == null) {
      throw invalid("effectivePeriodId", "Cần chọn kỳ hiệu lực");
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(cmd.effectivePeriodId()))
            .orElseThrow(() -> invalid("effectivePeriodId", "Không tìm thấy kỳ hiệu lực"));
    LocalDate today = clock.businessDate();
    if (period.isLocked() || !period.startDate().isAfter(today)) {
      throw new BusinessException(
          "POLICY-409-PERIOD-LOCKED",
          "Kỳ hiệu lực phải bắt đầu sau hôm nay và chưa khoá",
          List.of(
              new ProblemFieldError(
                  "effectivePeriodId", "Kỳ đã khoá hoặc đã bắt đầu", "BR-15-10")));
    }
    PolicyPackVersion submitted =
        transition(() -> current.submit(period.startDate(), actor, clock.instant()));
    if (!publish.storeSubmitted(tenant, submitted, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
    publish.insertRequest(
        tenant,
        PolicyPublishRequest.pending(
            Ids.newUuidV7(),
            submitted.id(),
            cmd.effectivePeriodId(),
            scopes,
            note,
            actor,
            clock.instant()));
    audit.changed(
        tenant,
        actor,
        "GUI_PHE_DUYET_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        key(pack, submitted),
        submitted.id(),
        submitted.rowVersion());
    List<UUID> to =
        approvers.approvers(tenant).stream()
            .filter(u -> !u.equals(submitted.createdBy()) && !u.equals(actor))
            .distinct()
            .toList();
    notices.submitted(tenant, pack.name(), submitted, to);
    return submitted;
  }

  /**
   * Ký số phê duyệt rồi khởi chạy saga phát hành; ký lỗi thì giữ {@code CHO_PHE_DUYET} ({@code
   * POLICY-409-NOT-SIGNED}). Trạng thái chuyển {@code DA_PHAT_HANH} do saga (CORE-05-10).
   */
  @Transactional(PolicyPackService.TX)
  public Approved approve(
      TenantId tenant,
      Approver approver,
      UUID packId,
      String version,
      long expected,
      UUID esignSessionId,
      String comment) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current = current(tenant, packId, version, expected);
    transition(
        () -> {
          current.requireApprovableBy(approver.userId());
          return current;
        });
    PolicyPublishRequest request =
        publish
            .findPending(tenant, current.id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-409-NOT-PENDING", "Phiên bản không có yêu cầu phát hành đang chờ"));
    if (request.submittedBy().equals(approver.userId())) {
      throw new BusinessException(
          "POLICY-403-SAME-AUTHOR", "Người phê duyệt phải khác người gửi phê duyệt");
    }
    String note = text(comment, "comment");
    requireStepUp(approver);
    if (current.contentHash() == null) {
      throw new BusinessException("POLICY-409-NOT-SIGNED", "Phiên bản chưa có băm nội dung để ký");
    }
    SignedHash signed;
    try {
      signed =
          signer.sign(
              tenant,
              approver.userId(),
              current.id(),
              HexFormat.of().parseHex(current.contentHash()),
              esignSessionId);
    } catch (PolicyPackSigner.SigningFailedException e) {
      throw new BusinessException(
          "POLICY-409-NOT-SIGNED", "Chưa ký số được phiên bản, vui lòng thử lại");
    }
    PolicyPackVersion approved =
        transition(() -> current.approve(approver.userId(), signed.signedAt(), clock.instant()));
    if (!publish.storeSignature(tenant, approved, signed, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
    PolicyPublishRequest decided =
        request.decide(
            PolicyPublishRequest.Status.DA_DUYET, approver.userId(), note, clock.instant());
    if (!publish.storeDecision(tenant, decided, request.rowVersion())) {
      throw new VersionConflictException("POLICY", request.rowVersion(), null);
    }
    audit.changed(
        tenant,
        approver.userId(),
        "KY_PHE_DUYET_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        key(pack, approved),
        approved.id(),
        approved.rowVersion());
    String jobId = launcher.launch(tenant, approver.userId(), approved, decided);
    return new Approved(approved, jobId);
  }

  /** Từ chối có ý kiến: {@code CHO_PHE_DUYET} → {@code NHAP}, báo người soạn qua EVT-21. */
  @Transactional(PolicyPackService.TX)
  public PolicyPackVersion reject(
      TenantId tenant, UUID actor, UUID packId, String version, long expected, String reason) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current = current(tenant, packId, version, expected);
    String why = reason == null ? "" : reason.strip();
    if (why.length() < REASON_MIN || why.length() > TEXT_MAX) {
      throw invalid("reason", "Ý kiến từ chối cần từ 10 đến 2000 ký tự");
    }
    PolicyPackVersion rejected = transition(() -> current.reject(actor, clock.instant()));
    if (!packs.storeStatus(tenant, rejected, expected)) {
      throw new VersionConflictException("POLICY", expected, null);
    }
    PolicyPublishRequest pending = publish.findPending(tenant, current.id()).orElse(null);
    if (pending != null) {
      PolicyPublishRequest decided =
          pending.decide(PolicyPublishRequest.Status.TU_CHOI, actor, why, clock.instant());
      if (!publish.storeDecision(tenant, decided, pending.rowVersion())) {
        throw new VersionConflictException("POLICY", pending.rowVersion(), null);
      }
    }
    audit.changed(
        tenant,
        actor,
        "TU_CHOI_POLICY_PACK",
        PolicyPackService.OBJECT_TYPE,
        key(pack, rejected),
        rejected.id(),
        rejected.rowVersion());
    List<UUID> to =
        pending == null || pending.submittedBy().equals(current.createdBy())
            ? List.of(current.createdBy())
            : List.of(current.createdBy(), pending.submittedBy());
    notices.rejected(tenant, pack.name(), rejected, why, to);
    return rejected;
  }

  private PolicyPackVersion current(TenantId tenant, UUID packId, String version, long expected) {
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.rowVersion() != expected) {
      throw new VersionConflictException("POLICY", expected, current.rowVersion());
    }
    return current;
  }

  /** Lần chạy gần nhất phải đạt và khớp hai engine (BR-15-07, MSG-15-06). */
  private void requireTested(TenantId tenant, PolicyPackVersion v) {
    boolean matched =
        v.lastTestRunId() != null
            && runs.findRun(tenant, v.id(), v.lastTestRunId())
                .map(n -> n.run().engineMatch())
                .orElse(false);
    if (!matched) {
      throw new BusinessException(
          "POLICY-409-NOT-TESTED", "Phiên bản chưa đạt kiểm thử trên cả hai bộ máy tính");
    }
  }

  private void requireStepUp(Approver approver) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(approver.acr()))) {
      throw new StepUpRequiredException(
          "approvePolicyPackVersion",
          maxAge,
          "Ký phê duyệt cần xác thực nhiều lớp trong 15 phút gần nhất.");
    }
    long age =
        clock.instant().getEpochSecond() - (approver.authTime() == null ? 0 : approver.authTime());
    if (approver.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          "approvePolicyPackVersion",
          maxAge,
          "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  private static List<Scope> scopes(List<Scope> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      throw invalid("scopes", "Cần chọn ít nhất một phạm vi áp dụng");
    }
    for (Scope s : scopes) {
      if (s == null || !FLOWS.contains(s.flow())) {
        throw invalid("scopes", "Luồng đánh giá của phạm vi không hợp lệ");
      }
    }
    if (scopes.stream().distinct().count() != scopes.size()) {
      throw invalid("scopes", "Phạm vi áp dụng bị trùng");
    }
    return List.copyOf(scopes);
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String v = value.strip();
    if (v.length() > TEXT_MAX) {
      throw invalid(field, "Ý kiến tối đa 2000 ký tự");
    }
    return v;
  }

  private static PolicyPackVersion transition(java.util.function.Supplier<PolicyPackVersion> move) {
    try {
      return move.get();
    } catch (PolicyTransitionException e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static String key(PolicyPack pack, PolicyPackVersion v) {
    return pack.code() + "@" + v.version();
  }
}
