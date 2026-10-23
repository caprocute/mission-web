package vn.viettel.okrkpi.oversight.application.complaint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi.Profile;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSigner;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gửi khiếu nại (CORE-07-02, QT-12 bước 1, 2, SCR-1201): danh sách kết quả còn khiếu nại được, lưu
 * nháp mã hoá C2, ký số và gửi. Gửi chỉ mở yêu cầu ký ở SVC-10; khi SVC-10 báo ký xong (EVT-18)
 * {@link #onSigned} chuyển hồ sơ đánh giá sang {@code KHIEU_NAI} qua cổng của MOD-04 rồi đặt khiếu
 * nại {@code MOI} và ghi EVT-25, EVT-21 cùng giao dịch. Hồ sơ và khiếu nại ở hai nguồn dữ liệu nên
 * lệnh của MOD-04 chạy trước và lặp lại an toàn; EVT-18 gửi lại được khử trùng lặp ở inbox.
 */
public class ComplaintService {

  static final String TX = "oversightTransactionManager";
  static final String OBJECT_TYPE = "complaint";
  static final String ACTION_CREATE = "LUU_NHAP_KHIEU_NAI";
  static final String ACTION_UPDATE = "SUA_NHAP_KHIEU_NAI";
  static final String ACTION_SUBMIT = "KY_GUI_KHIEU_NAI";
  static final String ROLE_MEMBER = "member";
  static final String COLUMN_CONTENT = "oversight.complaint.content_enc";
  static final String COLUMN_REQUEST = "oversight.complaint.request_text_enc";
  static final String PROFILE_SIGNED = "DA_KY_SO";
  static final long STEP_UP_MAX_AGE_SECONDS = 900;
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  static final String DEADLINE_PASSED = "OVERSIGHT-422-DEADLINE-PASSED";
  static final String NO_TARGET = "OVERSIGHT-422-NO-TARGET";
  static final String INVALID_STATE = "OVERSIGHT-409-INVALID-STATE";
  static final String VERSION_CONFLICT = "OVERSIGHT-409-VERSION-CONFLICT";

  private final ComplaintRepository complaints;
  private final ComplaintProfilesApi profiles;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final ComplaintSealer sealer;
  private final ComplaintSigner signer;
  private final ComplaintEvents events;
  private final OversightAuditLog audit;
  private final TransactionOperations committed;
  private final Clock clock;

  public ComplaintService(
      ComplaintRepository complaints,
      ComplaintProfilesApi profiles,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      ComplaintSealer sealer,
      ComplaintSigner signer,
      ComplaintEvents events,
      OversightAuditLog audit,
      TransactionOperations committed,
      Clock clock) {
    this.complaints = Objects.requireNonNull(complaints, "complaints");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.periods = periods;
    this.calendar = calendar;
    this.sealer = sealer;
    this.signer = signer;
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.committed = Objects.requireNonNull(committed, "committed");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Nội dung khiếu nại theo {@code ComplaintInput}; lý do và đề nghị là bản rõ chỉ trong bộ nhớ.
   */
  public record Draft(
      UUID profileId,
      ComplaintKind kind,
      ComplaintStage targetStage,
      List<ComplaintTarget> targets,
      String subjectLine,
      String content,
      String requestText,
      List<UUID> attachmentIds,
      boolean reserveOpinion) {
    public Draft {
      targets = targets == null ? List.of() : List.copyOf(targets);
      attachmentIds =
          attachmentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(attachmentIds));
    }
  }

  /** Kết quả còn khiếu nại được (SCR-1201 trường 1..3). */
  public record Eligible(
      Profile profile,
      PeriodSnapshot period,
      LocalDate deadline,
      int workingDaysLeft,
      List<ComplaintKind> allowedKinds) {}

  /** Khiếu nại kèm kỳ và nội dung đã mở (chỉ ở {@link #get}, {@link #create}, {@link #update}). */
  public record View(
      Complaint complaint,
      PeriodSnapshot period,
      String content,
      String requestText,
      Integer workingDaysLeft) {}

  /** Tác vụ ký của {@code submitComplaint}. */
  public record SignJob(UUID complaintId, UUID complainantId, boolean filed, Instant filedAt) {}

  /** Tài liệu cần ký: người ký, băm SHA-256 của nội dung khiếu nại, mã hiển thị. */
  public record SignSource(UUID signer, byte[] sha256, String ref) {}

  // ---------------------------------------------------------------- đọc

  /** Hồ sơ {@code DA_KY_SO} của người gọi kèm hạn khiếu nại và hình thức còn được phép. */
  public List<Eligible> eligible(TenantId tenant, Actor actor) {
    List<Eligible> out = new ArrayList<>();
    for (Profile p : profiles.signedProfilesOf(tenant, actor.userId())) {
      out.add(eligibility(tenant, p));
    }
    return out;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<Complaint> list(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      ComplaintRepository.After after,
      int limit) {
    return complaints.ofComplainant(
        tenant, actor.userId(), periodId, statuses, statuses.isEmpty(), kind, after, limit);
  }

  /** Chi tiết cho chủ khiếu nại; nội dung C2 được mở (mỗi lần mở ghi sổ cái, BR-12-10). */
  @Transactional(transactionManager = TX, readOnly = true)
  public View get(TenantId tenant, Actor actor, UUID id) {
    Complaint c = complaints.find(tenant, id).orElseThrow(NotFoundException::new);
    requireOwner(actor, c);
    return view(tenant, c, true);
  }

  /** Số ngày làm việc còn lại tới hạn trả lời, âm khi quá hạn; nháp trả {@code null}. */
  public Integer responseDaysLeft(TenantId tenant, Complaint c) {
    return c.responseDeadline() == null ? null : daysLeft(tenant, c.responseDeadline());
  }

  // ---------------------------------------------------------------- nháp

  @Transactional(TX)
  public View create(TenantId tenant, Actor actor, Draft d) {
    requireDraftShape(d);
    Profile p = ownProfile(tenant, actor, d.profileId());
    Eligible e = eligibility(tenant, p);
    requireKindAllowed(e, d.kind());
    Instant now = clock.instant();
    int year = now.atZone(ZONE).getYear();
    complaints.lockCodes(tenant, year);
    int next = 1;
    for (String code : complaints.codesOfYear(tenant, year)) {
      next = Math.max(next, ComplaintRules.sequenceOf(code, year) + 1);
    }
    UUID id = UUID.randomUUID();
    Sealed s = seal(tenant, id, d);
    Complaint c =
        new Complaint(
            id,
            ComplaintRules.code(year, next),
            p.periodId(),
            p.periodBucket(),
            actor.userId(),
            p.id(),
            d.kind(),
            d.targetStage(),
            subject(d, e),
            d.targets(),
            s.content(),
            s.request(),
            s.dekId(),
            d.attachmentIds(),
            d.reserveOpinion(),
            null,
            null,
            null,
            null,
            null,
            now,
            0,
            null);
    complaints.insert(tenant, c, actor.userId());
    audit.record(
        tenant, actor.userId(), ACTION_CREATE, OBJECT_TYPE, id.toString(), null, ROLE_MEMBER);
    return new View(c, e.period(), blankToNull(d.content()), blankToNull(d.requestText()), null);
  }

  /** Ghi đè nháp; yêu cầu ký đang mở (nếu có) bị bỏ vì nội dung đã đổi. */
  @Transactional(TX)
  public View update(TenantId tenant, Actor actor, UUID id, String ifMatch, Draft d) {
    Complaint c = complaints.lock(tenant, id).orElseThrow(NotFoundException::new);
    requireOwner(actor, c);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (!c.draft()) {
      throw new BusinessException(INVALID_STATE, "Khiếu nại đã gửi, không sửa được nữa.");
    }
    requireDraftShape(d);
    if (!c.profileId().equals(d.profileId())) {
      throw invalid("profileId", "Không đổi được kết quả bị khiếu nại của bản nháp.");
    }
    Profile p = ownProfile(tenant, actor, d.profileId());
    Eligible e = eligibility(tenant, p);
    requireKindAllowed(e, d.kind());
    Sealed s = seal(tenant, id, d);
    Complaint changed =
        new Complaint(
            c.id(),
            c.code(),
            c.periodId(),
            c.periodBucket(),
            c.complainantId(),
            c.profileId(),
            d.kind(),
            d.targetStage(),
            subject(d, e),
            d.targets(),
            s.content(),
            s.request(),
            s.dekId(),
            d.attachmentIds(),
            d.reserveOpinion(),
            null,
            null,
            null,
            null,
            null,
            c.createdAt(),
            c.rowVersion() + 1,
            c.handling());
    save(tenant, changed, c.rowVersion(), actor.userId());
    audit.record(
        tenant, actor.userId(), ACTION_UPDATE, OBJECT_TYPE, id.toString(), null, ROLE_MEMBER);
    return new View(
        changed, e.period(), blankToNull(d.content()), blankToNull(d.requestText()), null);
  }

  // ---------------------------------------------------------------- gửi

  /**
   * Kiểm đủ trường, hình thức, thời hạn rồi mở yêu cầu ký ở SVC-10 (bước lên AAL2). Trả mã yêu cầu
   * ký; khiếu nại chỉ thành {@code MOI} khi nhận EVT-18.
   *
   * <p>Mẫu "chốt trước, gọi sau" (wiki trang 14): đánh dấu nháp đang mở yêu cầu ký ({@code
   * sign_pending_at}) và commit trong giao dịch riêng, vì SVC-10 hỏi lại tài liệu cần ký ({@link
   * #signSource}) bằng kết nối khác ngay trong lời gọi; rồi gọi SVC-10 ngoài mọi giao dịch ghi và
   * gắn mã yêu cầu ký. SVC-10 lỗi thì gỡ dấu, nháp giữ nguyên để gửi lại.
   */
  public UUID submit(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      String idempotencyKey,
      String authorization) {
    Complaint c = committed.execute(st -> reserveSubmit(tenant, actor, id, ifMatch));
    UUID sign;
    try {
      sign = signer.request(tenant, id, idempotencyKey, authorization);
    } catch (RuntimeException e) {
      committed.executeWithoutResult(st -> complaints.markSignPending(tenant, c, null));
      throw e;
    }
    committed.executeWithoutResult(
        st -> {
          save(tenant, c.withSignRequest(sign), c.rowVersion(), actor.userId());
          audit.record(
              tenant, actor.userId(), ACTION_SUBMIT, OBJECT_TYPE, id.toString(), null, ROLE_MEMBER);
        });
    return sign;
  }

  private Complaint reserveSubmit(TenantId tenant, Actor actor, UUID id, String ifMatch) {
    Complaint c = complaints.lock(tenant, id).orElseThrow(NotFoundException::new);
    requireOwner(actor, c);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (!c.draft()) {
      throw new BusinessException(INVALID_STATE, "Khiếu nại đã được gửi.");
    }
    requireStepUp(actor);
    Profile p = profiles.find(tenant, c.profileId()).orElseThrow(NotFoundException::new);
    if (!PROFILE_SIGNED.equals(p.status())) {
      throw new BusinessException(
          INVALID_STATE, "Kết quả đánh giá không còn ở trạng thái được khiếu nại.");
    }
    requireComplete(tenant, c, p);
    Eligible e = eligibility(tenant, p);
    if (!e.allowedKinds().contains(c.kind())) {
      throw deadlinePassed();
    }
    if (signer == null) {
      throw new SigningUnavailableException();
    }
    if (!complaints.markSignPending(tenant, c, clock.instant())) {
      throw new BusinessException(VERSION_CONFLICT, "Khiếu nại đã được cập nhật ở nơi khác.");
    }
    return c;
  }

  /**
   * EVT-18 của nội dung khiếu nại. Chạy trong giao dịch inbox của schema oversight.
   *
   * @return {@code true} khi khiếu nại vừa chuyển {@code MOI}
   */
  @Transactional(TX)
  public boolean onSigned(TenantId tenant, UUID complaintId, UUID signRequestId, Instant signedAt) {
    Optional<Complaint> found = complaints.lock(tenant, complaintId);
    if (found.isEmpty()) {
      return false;
    }
    Complaint c = found.get();
    if (!c.draft() || c.signRequestId() == null || !c.signRequestId().equals(signRequestId)) {
      // Đã gửi, hoặc chữ ký của một bản nội dung cũ (nháp đã sửa sau khi mở yêu cầu ký).
      return false;
    }
    Profile p = profiles.find(tenant, c.profileId()).orElse(null);
    if (c.kind().reopensProfile()) {
      try {
        if (p == null) {
          throw new NoSuchElementException();
        }
        profiles.fileComplaint(tenant, c.profileId(), c.complainantId());
      } catch (BusinessException | NoSuchElementException e) {
        // Hồ sơ đã khoá hoặc đổi trạng thái trong lúc chờ ký: giữ nháp, bỏ yêu cầu ký, báo người
        // gửi.
        Complaint back = c.withSignRequest(null);
        save(tenant, back, c.rowVersion(), c.complainantId());
        events.notFiled(tenant, back);
        return false;
      }
    }
    Instant at = signedAt == null ? clock.instant() : signedAt;
    LocalDate day = at.atZone(ZONE).toLocalDate();
    LocalDate deadline = addWorkingDays(tenant, day, ComplaintRules.RESPONSE_WORKING_DAYS);
    LocalDate acceptBy = addWorkingDays(tenant, day, ComplaintRules.ACCEPT_WORKING_DAYS);
    Complaint filed = c.filed(at, deadline, p == null ? null : p.orgUnitId(), acceptBy);
    save(tenant, filed, c.rowVersion(), c.complainantId());
    events.filed(tenant, filed);
    return true;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignJob> signJob(TenantId tenant, UUID signRequestId) {
    return complaints
        .bySignRequest(tenant, signRequestId)
        .map(c -> new SignJob(c.id(), c.complainantId(), !c.draft(), c.filedAt()));
  }

  /**
   * Tài liệu cho SVC-10: băm các trường khiếu nại và bản mã nội dung (không mở C2). Sẵn sàng khi
   * nháp đã có yêu cầu ký hoặc đang mở yêu cầu ký.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID id) {
    return complaints
        .find(tenant, id)
        .filter(c -> c.awaitingSignature() || (c.draft() && complaints.signPending(tenant, id)))
        .map(c -> new SignSource(c.complainantId(), digest(c), c.code()));
  }

  // ---------------------------------------------------------------- kiểm tra

  private Eligible eligibility(TenantId tenant, Profile p) {
    Instant published = p.publishedAt() == null ? clock.instant() : p.publishedAt();
    LocalDate deadline =
        addWorkingDays(tenant, published.atZone(ZONE).toLocalDate(), p.deadlineWorkingDays());
    int left = daysLeft(tenant, deadline);
    List<ComplaintKind> kinds =
        left >= 0 ? List.of(ComplaintKind.values()) : List.of(ComplaintKind.BAO_LUU);
    PeriodSnapshot period =
        periods == null ? null : periods.findById(tenant, PeriodId.of(p.periodId())).orElse(null);
    return new Eligible(p, period, deadline, left, kinds);
  }

  private Profile ownProfile(TenantId tenant, Actor actor, UUID profileId) {
    Profile p =
        profiles
            .find(tenant, profileId)
            .filter(x -> x.personId().equals(actor.userId()))
            .orElseThrow(
                () -> invalid("profileId", "Không tìm thấy kết quả đánh giá của đồng chí."));
    if (!PROFILE_SIGNED.equals(p.status())) {
      throw new BusinessException(
          INVALID_STATE, "Chỉ khiếu nại được kết quả đánh giá đã ký số và công bố.");
    }
    return p;
  }

  private static void requireKindAllowed(Eligible e, ComplaintKind kind) {
    if (!e.allowedKinds().contains(kind)) {
      throw deadlinePassed();
    }
  }

  /** Độ dài tối đa áp cả khi lưu nháp; trường bắt buộc chỉ kiểm khi gửi. */
  private static void requireDraftShape(Draft d) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (d.profileId() == null) {
      errors.add(field("profileId", "Vui lòng chọn kết quả bị khiếu nại."));
    }
    if (d.kind() == null) {
      errors.add(field("kind", "Vui lòng chọn hình thức."));
    }
    if (d.targetStage() == null) {
      errors.add(field("targetStage", "Vui lòng chọn bước bị khiếu nại."));
    }
    for (ComplaintTarget t : d.targets()) {
      if (t.key().isBlank()
          || t.key().length() > ComplaintRules.TARGET_KEY_MAX
          || (t.label() != null && t.label().length() > ComplaintRules.TARGET_LABEL_MAX)) {
        errors.add(field("targets", "Cấu phần bị khiếu nại không hợp lệ."));
        break;
      }
    }
    if (d.subjectLine() != null && d.subjectLine().length() > ComplaintRules.SUBJECT_MAX) {
      errors.add(field("subjectLine", "Tiêu đề tối đa 255 ký tự."));
    }
    if (ComplaintRules.length(d.content()) > ComplaintRules.CONTENT_MAX) {
      errors.add(field("content", "Lý do, căn cứ tối đa 8.000 ký tự."));
    }
    if (ComplaintRules.length(d.requestText()) > ComplaintRules.REQUEST_MAX) {
      errors.add(field("requestText", "Đề nghị cụ thể tối đa 500 ký tự."));
    }
    if (d.attachmentIds().size() > ComplaintRules.ATTACHMENTS_MAX) {
      errors.add(field("attachmentObjectIds", "Tối đa 20 tệp kèm theo."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Nội dung khiếu nại chưa hợp lệ.", List.copyOf(errors));
    }
  }

  /** BR-12-02 khi gửi: ít nhất một cấu phần của ảnh chụp, lý do 50..8.000 ký tự, có đề nghị. */
  private void requireComplete(TenantId tenant, Complaint c, Profile p) {
    if (c.targets().isEmpty()) {
      String m = "Vui lòng chọn ít nhất một cấu phần bị khiếu nại.";
      throw new BusinessException(
          NO_TARGET, m, List.of(new ProblemFieldError("targets", m, "BR-12-02")));
    }
    Set<String> known = new LinkedHashSet<>();
    p.targets().forEach(t -> known.add(t.key()));
    for (ComplaintTarget t : c.targets()) {
      if (!known.contains(t.key())) {
        throw invalid("targets", "Cấu phần bị khiếu nại không thuộc kết quả đánh giá đã chọn.");
      }
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    String content = open(tenant, COLUMN_CONTENT, c.id(), c.contentEnc());
    int n = ComplaintRules.length(content);
    if (n < ComplaintRules.CONTENT_MIN || n > ComplaintRules.CONTENT_MAX) {
      errors.add(
          new ProblemFieldError("content", "Lý do, căn cứ cần từ 50 đến 8.000 ký tự.", "BR-12-02"));
    }
    String request = open(tenant, COLUMN_REQUEST, c.id(), c.requestTextEnc());
    int r = ComplaintRules.length(request);
    if (r == 0 || r > ComplaintRules.REQUEST_MAX) {
      errors.add(
          new ProblemFieldError(
              "requestText", "Vui lòng nêu đề nghị cụ thể (tối đa 500 ký tự).", "BR-12-02"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Nội dung khiếu nại chưa đủ để gửi.", List.copyOf(errors));
    }
  }

  private void requireStepUp(Actor actor) {
    boolean aal2 =
        StepUpRequiredException.REQUIRED_ACR.equals(AuthzSubjectResolver.normalizeAcr(actor.acr()));
    boolean fresh =
        actor.authTime() == null
            || clock.instant().getEpochSecond() - actor.authTime() <= STEP_UP_MAX_AGE_SECONDS;
    if (!aal2 || !fresh) {
      throw new StepUpRequiredException(
          "submitComplaint", STEP_UP_MAX_AGE_SECONDS, "Ký số khiếu nại cần xác thực nhiều lớp.");
    }
  }

  private static void requireOwner(Actor actor, Complaint c) {
    if (!c.complainantId().equals(actor.userId())) {
      // Người xử lý, người ký đọc khiếu nại từ CORE-07-03; ở đây chỉ chủ khiếu nại.
      throw new NotFoundException();
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private record Sealed(byte[] content, byte[] request, UUID dekId) {}

  private Sealed seal(TenantId tenant, UUID id, Draft d) {
    String content = blankToNull(d.content());
    String request = blankToNull(d.requestText());
    if (content == null && request == null) {
      return new Sealed(null, null, null);
    }
    if (sealer == null) {
      throw new SealingUnavailableException();
    }
    ComplaintSealer.Sealed a =
        content == null ? null : sealer.seal(tenant, COLUMN_CONTENT, id, content);
    ComplaintSealer.Sealed b =
        request == null ? null : sealer.seal(tenant, COLUMN_REQUEST, id, request);
    if (a != null && b != null && !a.dekId().equals(b.dekId())) {
      // DEK vừa xoay giữa hai lần mã hoá: mã lại nội dung bằng DEK của đề nghị để dòng có một
      // dek_id.
      a = sealer.seal(tenant, COLUMN_CONTENT, id, content);
    }
    UUID dek = a != null ? a.dekId() : b.dekId();
    return new Sealed(a == null ? null : a.cipher(), b == null ? null : b.cipher(), dek);
  }

  private String open(TenantId tenant, String column, UUID id, byte[] cipher) {
    if (cipher == null) {
      return null;
    }
    if (sealer == null) {
      throw new SealingUnavailableException();
    }
    return sealer.open(tenant, column, id, cipher);
  }

  View view(TenantId tenant, Complaint c, boolean withContent) {
    PeriodSnapshot period =
        periods == null ? null : periods.findById(tenant, PeriodId.of(c.periodId())).orElse(null);
    return new View(
        c,
        period,
        withContent ? open(tenant, COLUMN_CONTENT, c.id(), c.contentEnc()) : null,
        withContent ? open(tenant, COLUMN_REQUEST, c.id(), c.requestTextEnc()) : null,
        responseDaysLeft(tenant, c));
  }

  private static String subject(Draft d, Eligible e) {
    if (d.subjectLine() != null && !d.subjectLine().isBlank()) {
      return d.subjectLine().strip();
    }
    String what =
        switch (d.kind()) {
          case KHIEU_NAI -> "Khiếu nại kết quả xếp loại";
          case GIAI_TRINH -> "Giải trình bổ sung về kết quả xếp loại";
          case BAO_LUU -> "Bảo lưu ý kiến về kết quả xếp loại";
        };
    return e.period() == null ? what : what + " " + e.period().name();
  }

  private void save(TenantId tenant, Complaint changed, int expected, UUID actor) {
    if (!complaints.update(tenant, changed, expected, actor)) {
      throw new BusinessException(VERSION_CONFLICT, "Khiếu nại đã được cập nhật ở nơi khác.");
    }
  }

  private int daysLeft(TenantId tenant, LocalDate deadline) {
    LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
    if (!today.isAfter(deadline)) {
      return (int) countWorkingDays(tenant, today, deadline);
    }
    return (int) -countWorkingDays(tenant, deadline, today);
  }

  private LocalDate addWorkingDays(TenantId tenant, LocalDate from, int n) {
    if (calendar != null) {
      return calendar.addWorkingDays(tenant, from, n);
    }
    LocalDate d = from;
    int left = n;
    while (left > 0) {
      d = d.plusDays(1);
      if (weekday(d)) {
        left--;
      }
    }
    return d;
  }

  private long countWorkingDays(TenantId tenant, LocalDate fromExclusive, LocalDate toInclusive) {
    if (calendar != null) {
      return calendar.countWorkingDays(tenant, fromExclusive, toInclusive);
    }
    long n = 0;
    for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
      if (weekday(d)) {
        n++;
      }
    }
    return n;
  }

  private static boolean weekday(LocalDate d) {
    return !EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(d.getDayOfWeek());
  }

  private static byte[] digest(Complaint c) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      StringBuilder b =
          new StringBuilder()
              .append(c.id())
              .append('|')
              .append(c.code())
              .append('|')
              .append(c.profileId())
              .append('|')
              .append(c.kind())
              .append('|')
              .append(c.targetStage())
              .append('|')
              .append(c.reserveOpinion());
      c.targets().forEach(t -> b.append('|').append(t.stage()).append(':').append(t.key()));
      c.attachmentIds().forEach(a -> b.append('|').append(a));
      HexFormat hex = HexFormat.of();
      b.append('|').append(c.contentEnc() == null ? "" : hex.formatHex(md.digest(c.contentEnc())));
      b.append('|')
          .append(c.requestTextEnc() == null ? "" : hex.formatHex(md.digest(c.requestTextEnc())));
      return md.digest(b.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }

  private static BusinessException deadlinePassed() {
    String m = "Đã hết thời hạn khiếu nại; đồng chí vẫn có thể gửi ý kiến bảo lưu.";
    return new BusinessException(
        DEADLINE_PASSED, m, List.of(new ProblemFieldError("kind", m, "BR-12-01")));
  }

  /** Khiếu nại không tồn tại hoặc người gọi không được xem (lớp REST trả 404). */
  public static final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException() {
      super("Không tìm thấy khiếu nại");
    }
  }

  /** Cell chưa cấu hình SVC-10; lớp REST trả 503. */
  public static final class SigningUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SigningUnavailableException() {
      super("Chưa ký số được, vui lòng thử lại sau.");
    }
  }

  /** Cell chưa có {@code FieldEncryptor}: không bao giờ lưu C2 dạng rõ; lớp REST trả 503. */
  public static final class SealingUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SealingUnavailableException() {
      super("Chưa lưu được nội dung khiếu nại, vui lòng thử lại sau.");
    }
  }
}
