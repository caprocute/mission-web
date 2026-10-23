package vn.viettel.okrkpi.oversight.application.reopen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.port.ApprovalSigner;
import vn.viettel.okrkpi.oversight.application.port.ApproverDirectory;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.port.ReopenEvents;
import vn.viettel.okrkpi.oversight.application.port.ReopenNotices;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Approval;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.View;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRules;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenWorkflow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bốn mắt của yêu cầu mở lại kỳ (CORE-07-08, QT-13 bước 5–8, BR-13-02..04): chọn người duyệt lượt 1
 * ({@code decider}) và lượt 2 ({@code second_approver}) khác người lập và người đã thẩm định, quyết
 * định hồ sơ trong phạm vi, nâng lên đơn vị cấp trên khi đơn vị không có người; duyệt, từ chối qua
 * bước lên và ký số; đủ hai phê duyệt thì ghi EVT-22 cùng giao dịch. Mọi lệnh trên một yêu cầu tuần
 * tự hoá bằng {@code SELECT … FOR UPDATE}.
 *
 * <p>Quyết định chờ ký được chốt trong giao dịch riêng ({@code committed}, {@code REQUIRES_NEW})
 * trước khi gọi SVC-10, vì SVC-10 hỏi lại tài liệu cần ký ({@link #signSource}) bằng kết nối khác
 * ngay trong lời gọi tạo yêu cầu ký, trong khi API còn giữ giao dịch khoá lặp (wiki trang 14, mẫu
 * "chốt trước, gọi sau").
 */
public class ReopenApprovalService {

  static final String TX = ReopenRequestService.TX;
  static final String OPERATION_APPROVE = "approveReopenRequest";
  static final String OPERATION_REJECT = "rejectReopenRequest";
  static final long STEP_UP_MAX_AGE_SECONDS = 900;
  static final String DONG_Y = "DONG_Y";
  static final String TU_CHOI = "TU_CHOI";
  static final String FOUR_EYES = "OVERSIGHT-403-FOUR-EYES";

  private final ReopenRequestRepository requests;
  private final ApproverDirectory directory;
  private final ApprovalSigner signer;
  private final ReopenNotices notices;
  private final ReopenEvents events;
  private final OversightAuditLog audit;
  private final PeriodApi periods;
  private final TransactionOperations committed;
  private final Clock clock;

  public ReopenApprovalService(
      ReopenRequestRepository requests,
      ApproverDirectory directory,
      ApprovalSigner signer,
      ReopenNotices notices,
      ReopenEvents events,
      OversightAuditLog audit,
      PeriodApi periods,
      TransactionOperations committed,
      Clock clock) {
    this.requests = Objects.requireNonNull(requests, "requests");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.signer = signer;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.committed = Objects.requireNonNull(committed, "committed");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Chọn người cho hai lượt duyệt khi lập yêu cầu và báo người duyệt lượt 1. Lượt không chọn được
   * người để trống; hệ thống không hạ xuống một người duyệt.
   */
  public ReopenRequest assign(TenantId tenant, ReopenRequest r) {
    Set<UUID> excluded = new HashSet<>(excludedFor(tenant, r));
    UUID first = pick(tenant, ReopenWorkflow.DECIDER, base(r), excluded);
    if (first != null) {
      excluded.add(first);
    }
    UUID second = pick(tenant, ReopenWorkflow.SECOND_APPROVER, base(r), excluded);
    ReopenWorkflow wf = r.workflow().assign(1, first).assign(2, second);
    ReopenRequest assigned =
        r.with(r.status(), r.executedAt(), r.cancelReason(), wf, r.rowVersion());
    if (first != null) {
      notices.approvalRequested(tenant, first, assigned, 1);
    }
    return assigned;
  }

  /**
   * Người được chỉ định gửi quyết định duyệt ({@code approve}) hoặc từ chối: kiểm bước lên, đúng
   * lượt, đúng người và bốn mắt, rồi mở yêu cầu ký số; {@code reopen_approval} chỉ được ghi khi
   * EVT-18 báo ký xong ({@link #onSigned}). Gửi lại khi quyết định của chính mình đang chờ ký thì
   * trả trạng thái hiện tại.
   *
   * <p>Ba bước: chốt quyết định chờ ký (chưa có mã yêu cầu ký) và commit; gọi SVC-10 ngoài mọi giao
   * dịch ghi; gắn mã yêu cầu ký. SVC-10 lỗi thì gỡ quyết định vừa chốt để người duyệt gửi lại.
   */
  public View decide(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      boolean approve,
      String comment,
      String idempotencyKey,
      String authorization) {
    requireStepUp(actor, approve ? OPERATION_APPROVE : OPERATION_REJECT);
    Reserved reserved =
        committed.execute(st -> reserve(tenant, actor, id, ifMatch, approve, comment));
    if (reserved.current() != null) {
      return reserved.current();
    }
    UUID signRequestId;
    try {
      signRequestId = signer.request(tenant, id, idempotencyKey, authorization);
    } catch (RuntimeException e) {
      committed.executeWithoutResult(st -> release(tenant, reserved.request()));
      throw e;
    }
    return committed.execute(st -> attach(tenant, reserved.request(), signRequestId));
  }

  /** Kết quả bước chốt: quyết định vừa chốt, hoặc trạng thái hiện tại khi đã chờ ký từ trước. */
  private record Reserved(ReopenRequest request, View current) {}

  private Reserved reserve(
      TenantId tenant, Actor actor, UUID id, String ifMatch, boolean approve, String comment) {
    ReopenRequest r = requests.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (!involved(actor, r)) {
      throw new NotFoundException();
    }
    ETags.requireMatch(ifMatch, r.rowVersion(), "OVERSIGHT");
    if (!ReopenRules.cancelReasonValid(comment)) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Ý kiến người duyệt cần từ 10 đến 2.000 ký tự.",
          List.of(new ProblemFieldError("comment", "Ý kiến cần từ 10 đến 2.000 ký tự.", null)));
    }
    if (r.status() != ReopenStatus.CHO_DUYET_1 && r.status() != ReopenStatus.CHO_DUYET_2) {
      throw new BusinessException(
          "OVERSIGHT-409-INVALID-STATE", "Yêu cầu không còn ở bước chờ duyệt.");
    }
    int round = r.status() == ReopenStatus.CHO_DUYET_1 ? 1 : 2;
    String role = ReopenWorkflow.roleOf(round);
    ReopenWorkflow.Pending pending = r.workflow().pending();
    if (pending != null) {
      boolean mine = pending.approverId().equals(actor.userId()) && pending.round() == round;
      if (!mine) {
        throw new BusinessException(
            "OVERSIGHT-409-INVALID-STATE", "Lượt duyệt này đang chờ hoàn tất chữ ký số.");
      }
      if (pending.signRequestId() != null) {
        return new Reserved(r, view(tenant, r));
      }
      // Lần gửi trước dừng giữa chừng (chưa gắn mã yêu cầu ký): chốt lại theo quyết định mới.
    }
    ReopenWorkflow wf = requireEligible(tenant, actor, r, round, role);
    if (signer == null) {
      throw new SigningUnavailableException();
    }
    ReopenWorkflow.Pending p =
        new ReopenWorkflow.Pending(
            round,
            actor.userId(),
            role,
            approve ? DONG_Y : TU_CHOI,
            comment.strip(),
            AuthzSubjectResolver.normalizeAcr(actor.acr()),
            null,
            clock.instant());
    ReopenRequest changed =
        r.with(r.status(), r.executedAt(), r.cancelReason(), wf.withPending(p), r.rowVersion() + 1);
    save(tenant, changed, r.rowVersion(), actor.userId());
    return new Reserved(changed, null);
  }

  private View attach(TenantId tenant, ReopenRequest reserved, UUID signRequestId) {
    ReopenRequest r = requests.lock(tenant, reserved.id()).orElseThrow(NotFoundException::new);
    ReopenWorkflow.Pending p = r.workflow().pending();
    if (r.rowVersion() != reserved.rowVersion() || p == null || p.signRequestId() != null) {
      throw new BusinessException(
          "OVERSIGHT-409-VERSION-CONFLICT", "Yêu cầu đã được người khác cập nhật.");
    }
    ReopenWorkflow.Pending signed =
        new ReopenWorkflow.Pending(
            p.round(),
            p.approverId(),
            p.role(),
            p.decision(),
            p.comment(),
            p.acr(),
            signRequestId,
            p.requestedAt());
    ReopenRequest changed =
        r.with(
            r.status(),
            r.executedAt(),
            r.cancelReason(),
            r.workflow().withPending(signed),
            r.rowVersion() + 1);
    save(tenant, changed, r.rowVersion(), p.approverId());
    return view(tenant, changed);
  }

  /** Gỡ quyết định vừa chốt khi SVC-10 không mở được yêu cầu ký; bỏ qua nếu đã đổi. */
  private void release(TenantId tenant, ReopenRequest reserved) {
    requests
        .lock(tenant, reserved.id())
        .filter(r -> r.rowVersion() == reserved.rowVersion())
        .filter(r -> r.workflow().pending() != null)
        .filter(r -> r.workflow().pending().signRequestId() == null)
        .ifPresent(
            r ->
                save(
                    tenant,
                    r.with(
                        r.status(),
                        r.executedAt(),
                        r.cancelReason(),
                        r.workflow().withPending(null),
                        r.rowVersion() + 1),
                    r.rowVersion(),
                    r.workflow().pending().approverId()));
  }

  /** Tài liệu cần ký cho SVC-10: quyết định đang chờ, người ký duy nhất là người duyệt. */
  @Transactional(value = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID id) {
    return requests
        .find(tenant, id)
        .filter(r -> r.workflow().pending() != null)
        .map(
            r -> {
              ReopenWorkflow.Pending p = r.workflow().pending();
              String statement =
                  String.join(
                      "\n",
                      r.code(),
                      r.periodId().toString(),
                      Integer.toString(p.round()),
                      p.role(),
                      p.decision(),
                      p.approverId().toString(),
                      p.comment());
              return new SignSource(p.approverId(), sha256(statement), r.code());
            });
  }

  /**
   * EVT-18 báo chữ ký của quyết định đang chờ đã hoàn tất: ghi {@code reopen_approval}, chuyển lượt
   * hoặc kết thúc; đủ phê duyệt và có {@code second_approver} thì {@code DA_DUYET} và ghi EVT-22.
   *
   * @return {@code false} khi không có quyết định nào chờ chữ ký này (trùng hoặc đã xử lý)
   */
  @Transactional(TX)
  public boolean onSigned(TenantId tenant, UUID requestId, UUID signRequestId, Instant signedAt) {
    Optional<ReopenRequest> found = requests.lock(tenant, requestId);
    if (found.isEmpty()) {
      return false;
    }
    ReopenRequest r = found.get();
    ReopenWorkflow.Pending p = r.workflow().pending();
    if (p == null
        || !signRequestId.equals(p.signRequestId())
        || (r.status() != ReopenStatus.CHO_DUYET_1 && r.status() != ReopenStatus.CHO_DUYET_2)) {
      return false;
    }
    Instant at = signedAt == null ? clock.instant() : signedAt;
    requests.insertApproval(
        tenant,
        r,
        new Approval(
            Ids.newUuidV7(),
            p.approverId(),
            p.role(),
            p.decision(),
            p.acr(),
            at,
            signRequestId,
            p.comment()));
    ReopenWorkflow wf = r.workflow().withPending(null);
    ReopenStatus next;
    boolean reopened = false;
    if (TU_CHOI.equals(p.decision())) {
      next = ReopenStatus.TU_CHOI;
    } else if (p.round() == 1) {
      next = ReopenStatus.CHO_DUYET_2;
    } else {
      List<Approval> all = requests.approvals(tenant, r.periodBucket(), r.id());
      Set<UUID> agreed = new LinkedHashSet<>();
      boolean second = false;
      for (Approval a : all) {
        if (DONG_Y.equals(a.decision())) {
          agreed.add(a.approverId());
          second |= ReopenWorkflow.SECOND_APPROVER.equals(a.approverRole());
        }
      }
      reopened = agreed.size() >= r.approvalsRequired() && second;
      next = reopened ? ReopenStatus.DA_DUYET : ReopenStatus.CHO_DUYET_2;
      if (reopened) {
        ReopenRequest approved =
            r.with(next, r.executedAt(), r.cancelReason(), wf, r.rowVersion() + 1);
        events.reopened(tenant, approved, List.copyOf(agreed), at);
      }
    }
    ReopenRequest changed = r.with(next, r.executedAt(), r.cancelReason(), wf, r.rowVersion() + 1);
    save(tenant, changed, r.rowVersion(), p.approverId());
    audit.record(
        tenant,
        p.approverId(),
        TU_CHOI.equals(p.decision()) ? "TU_CHOI_MO_LAI_KY" : "PHE_DUYET_MO_LAI_KY",
        ReopenRequestService.OBJECT_TYPE,
        r.id().toString(),
        null,
        p.role());
    if (next == ReopenStatus.CHO_DUYET_2 && p.round() == 1) {
      UUID secondAssignee = wf.assignee(2);
      if (secondAssignee != null) {
        notices.approvalRequested(tenant, secondAssignee, changed, 2);
      }
    } else if (next == ReopenStatus.TU_CHOI || reopened) {
      notices.decided(tenant, changed);
    }
    return true;
  }

  /**
   * Yêu cầu {@code DA_DUYET} có kỳ đã sang {@code MO_LAI_HAU_KIEM} (MOD-08 đã nhận EVT-22) chuyển
   * {@code DA_THUC_HIEN} và ghi {@code executed_at}.
   *
   * @return số yêu cầu vừa chuyển
   */
  @Transactional(TX)
  public int confirmExecuted(TenantId tenant) {
    int done = 0;
    for (ReopenRequest r : requests.approvedNotExecuted(tenant, 100)) {
      Optional<PeriodSnapshot> period = periods.findById(tenant, PeriodId.of(r.periodId()));
      if (period.isPresent() && period.get().status() == PeriodStatus.MO_LAI_HAU_KIEM) {
        ReopenRequest executed =
            r.with(
                ReopenStatus.DA_THUC_HIEN,
                clock.instant(),
                r.cancelReason(),
                r.workflow(),
                r.rowVersion() + 1);
        if (requests.update(tenant, executed, r.rowVersion(), r.requestedBy())) {
          done++;
        }
      }
    }
    return done;
  }

  /** Bốn mắt (BR-13-02, BR-13-03): đúng người được chỉ định, khác người lập và lượt trước. */
  private ReopenWorkflow requireEligible(
      TenantId tenant, Actor actor, ReopenRequest r, int round, String role) {
    UUID me = actor.userId();
    if (me.equals(r.requestedBy())) {
      throw fourEyes("Người lập yêu cầu không được tự duyệt.");
    }
    for (Approval a : requests.approvals(tenant, r.periodBucket(), r.id())) {
      if (me.equals(a.approverId())) {
        throw fourEyes("Mỗi người chỉ duyệt một lượt.");
      }
    }
    Set<UUID> excluded = excludedFor(tenant, r);
    if (excluded.contains(me)) {
      throw fourEyes("Người đã thẩm định hoặc quyết định hồ sơ không được duyệt mở lại.");
    }
    ReopenWorkflow wf = r.workflow();
    UUID assignee = wf.assignee(round);
    if (assignee == null) {
      // Chọn lại khi lúc lập chưa có người phù hợp (ví dụ vai trò mới được gán).
      Set<UUID> others = new HashSet<>(excluded);
      UUID other = wf.assignee(round == 1 ? 2 : 1);
      if (other != null) {
        others.add(other);
      }
      assignee = pick(tenant, role, base(r), others);
      wf = wf.assign(round, assignee);
    }
    if (!me.equals(assignee) || !holdsFor(tenant, role, base(r), me)) {
      throw fourEyes("Bạn không phải người được chỉ định duyệt lượt này.");
    }
    return wf;
  }

  private Set<UUID> excludedFor(TenantId tenant, ReopenRequest r) {
    Set<UUID> profiles = new LinkedHashSet<>();
    if (r.scope() == ReopenScope.HO_SO) {
      profiles.addAll(r.scopeIds());
    }
    for (ReopenScopeItem i : r.items()) {
      if (i.profileId() != null) {
        profiles.add(i.profileId());
      }
    }
    List<UUID> units = r.scope() == ReopenScope.DON_VI ? r.scopeIds() : List.of();
    Set<UUID> out = new HashSet<>(directory.participants(tenant, r.periodId(), profiles, units));
    out.add(r.requestedBy());
    return out;
  }

  /** Đơn vị gốc để tìm người duyệt: đơn vị đầu tiên của phạm vi, nếu không có thì của người lập. */
  private static UUID base(ReopenRequest r) {
    return r.units().isEmpty() ? r.workflow().requesterUnit() : r.units().get(0);
  }

  private List<UUID> chain(TenantId tenant, UUID base) {
    List<UUID> out = new ArrayList<>();
    out.add(base);
    out.addAll(directory.ancestorsOf(tenant, base));
    return out;
  }

  /** Người đầu tiên giữ vai trò ở đơn vị gốc, rồi lần lượt các đơn vị cấp trên (ngoại lệ QT-13). */
  private UUID pick(TenantId tenant, String role, UUID base, Set<UUID> excluded) {
    if (base == null) {
      return null;
    }
    Instant now = clock.instant();
    for (UUID unit : chain(tenant, base)) {
      for (UUID h : directory.holders(tenant, role, unit, now)) {
        if (!excluded.contains(h)) {
          return h;
        }
      }
    }
    return null;
  }

  private boolean holdsFor(TenantId tenant, String role, UUID base, UUID person) {
    if (base == null) {
      return false;
    }
    Instant now = clock.instant();
    for (UUID unit : chain(tenant, base)) {
      if (directory.holders(tenant, role, unit, now).contains(person)) {
        return true;
      }
    }
    return false;
  }

  private static boolean involved(Actor actor, ReopenRequest r) {
    UUID me = actor.userId();
    return actor.roles().contains(ReopenRequestService.ROLE_INSPECTOR)
        || me.equals(r.requestedBy())
        || r.workflow().slots().stream().anyMatch(s -> me.equals(s.assignee()))
        || actor.roles().contains(ReopenWorkflow.DECIDER)
        || actor.roles().contains(ReopenWorkflow.SECOND_APPROVER);
  }

  private void requireStepUp(Actor actor, String operation) {
    boolean aal2 =
        StepUpRequiredException.REQUIRED_ACR.equals(AuthzSubjectResolver.normalizeAcr(actor.acr()));
    boolean fresh =
        actor.authTime() == null
            || clock.instant().getEpochSecond() - actor.authTime() <= STEP_UP_MAX_AGE_SECONDS;
    if (!aal2 || !fresh) {
      throw new StepUpRequiredException(
          operation, STEP_UP_MAX_AGE_SECONDS, "Duyệt yêu cầu mở lại kỳ cần xác thực nhiều lớp.");
    }
  }

  private void save(TenantId tenant, ReopenRequest changed, int expected, UUID actor) {
    if (!requests.update(tenant, changed, expected, actor)) {
      throw new BusinessException(
          "OVERSIGHT-409-VERSION-CONFLICT", "Yêu cầu đã được người khác cập nhật.");
    }
  }

  private View view(TenantId tenant, ReopenRequest r) {
    return new View(
        r,
        periods.findById(tenant, PeriodId.of(r.periodId())).orElse(null),
        requests.approvals(tenant, r.periodBucket(), r.id()));
  }

  private static BusinessException fourEyes(String detail) {
    return new BusinessException(FOUR_EYES, detail);
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Tài liệu cần ký: người ký, băm SHA-256 của nội dung quyết định, mã hiển thị. */
  public record SignSource(UUID signer, byte[] sha256, String ref) {}

  /** Cell chưa cấu hình SVC-10; lớp REST trả 503. */
  public static final class SigningUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SigningUnavailableException() {
      super("Chưa ký số được, vui lòng thử lại sau.");
    }
  }
}
