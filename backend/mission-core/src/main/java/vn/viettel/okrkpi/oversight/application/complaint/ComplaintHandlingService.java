package vn.viettel.okrkpi.oversight.application.complaint;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository.DeadlineAfter;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository.Handled;
import vn.viettel.okrkpi.oversight.application.port.ComplaintNotices;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.CloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandlerRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandling;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintWorkflow;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintWorkflow.Step;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tiếp nhận khiếu nại (CORE-07-03, QT-12 bước 3, 4, SCR-1202): danh sách và chi tiết trong phạm vi
 * của người xử lý, người ký; tiếp nhận, không thụ lý, rút. Người tiếp nhận phải là ứng viên hợp lệ
 * của {@link ComplaintHandlerRules} (BR-12-04); không thụ lý và rút tự đóng sau khi ghi EVT-21 và
 * đưa hồ sơ đánh giá về {@code DA_KY_SO} qua cổng MOD-04 (chạy trước, lặp lại an toàn).
 */
public class ComplaintHandlingService {

  static final String TX = "oversightTransactionManager";
  static final String OBJECT_TYPE = "complaint";
  static final String ACTION_VIEW = "XEM_KHIEU_NAI";
  static final String ACTION_ACCEPT = "TIEP_NHAN_KHIEU_NAI";
  static final String ACTION_REJECT = "KHONG_THU_LY_KHIEU_NAI";
  static final String ACTION_WITHDRAW = "RUT_KHIEU_NAI";
  static final String ACTION_CLOSE = "DONG_KHIEU_NAI";
  static final String ROLE_MEMBER = "member";
  static final String COLUMN_CONTENT = ComplaintService.COLUMN_CONTENT;
  static final String COLUMN_REQUEST = ComplaintService.COLUMN_REQUEST;
  static final String COLUMN_CONCLUSION = "oversight.complaint.conclusion_enc";
  static final ZoneId ZONE = ComplaintService.ZONE;
  static final String INVALID_STATE = ComplaintService.INVALID_STATE;
  static final String VERSION_CONFLICT = ComplaintService.VERSION_CONFLICT;

  private final ComplaintHandlingRepository complaints;
  private final ComplaintAccess access;
  private final ComplaintProfilesApi profiles;
  private final PeriodApi periods;
  private final WorkingDays days;
  private final ComplaintSealer sealer;
  private final ComplaintNotices notices;
  private final OversightAuditLog audit;
  private final Clock clock;

  public ComplaintHandlingService(
      ComplaintHandlingRepository complaints,
      ComplaintDirectory directory,
      ComplaintProfilesApi profiles,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      ComplaintSealer sealer,
      ComplaintNotices notices,
      OversightAuditLog audit,
      Clock clock) {
    this.complaints = Objects.requireNonNull(complaints, "complaints");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.access = new ComplaintAccess(directory, clock);
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.periods = periods;
    this.days = new WorkingDays(calendar);
    this.sealer = sealer;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /**
   * Khiếu nại kèm phần xử lý, kỳ và nội dung đã mở cho người có quyền đọc C2 ({@code null} khi
   * không được đọc).
   */
  public record View(
      Complaint complaint,
      ComplaintHandling handling,
      PeriodSnapshot period,
      String content,
      String requestText,
      String conclusionText,
      Integer workingDaysLeft) {

    /** Kỳ đã khoá: điều chỉnh phải qua mở lại kỳ (MSG-12-07). */
    public boolean periodLocked() {
      return period != null && period.status() == PeriodStatus.DA_KHOA;
    }
  }

  // ---------------------------------------------------------------- đọc

  /**
   * Khiếu nại đã gửi trong phạm vi {@code Gn} của người xử lý, người ký, sắp theo hạn trả lời.
   * {@code assignedToMe} chỉ lấy khiếu nại người gọi đã tiếp nhận; không có cờ đó thì kèm cả khiếu
   * nại của chính người gọi (người xử lý cũng có thể là người khiếu nại, SCR-1201).
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<Complaint> list(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      boolean assignedToMe,
      DeadlineAfter after,
      int limit) {
    if (!ComplaintAccess.staff(actor) || actor.unitId() == null) {
      throw ComplaintAccess.outOfScope();
    }
    return complaints.inScope(
        tenant,
        access.scopeOf(tenant, actor.unitId()),
        assignedToMe ? actor.userId() : null,
        assignedToMe ? null : actor.userId(),
        periodId,
        statuses,
        kind,
        after,
        limit);
  }

  /**
   * Chi tiết cho chủ khiếu nại, hoặc người xử lý, người ký trong phạm vi. Nội dung C2 chỉ mở cho
   * chủ khiếu nại, người đã tiếp nhận, ứng viên tiếp nhận khi còn {@code MOI} và người ký từ bước
   * chờ kết luận; người đã tham gia hồ sơ chỉ thấy thông tin chung (BR-12-04, BR-12-10).
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public View get(TenantId tenant, Actor actor, UUID id) {
    Handled h = complaints.find(tenant, id).orElseThrow(ComplaintService.NotFoundException::new);
    Complaint c = h.complaint();
    boolean owner = c.complainantId().equals(actor.userId());
    if (!owner
        && (c.draft() || !ComplaintAccess.staff(actor) || !access.inScope(tenant, actor, c))) {
      throw new ComplaintService.NotFoundException();
    }
    boolean read = owner || mayRead(tenant, actor, h);
    if (read && !owner) {
      audit.record(
          tenant,
          actor.userId(),
          ACTION_VIEW,
          OBJECT_TYPE,
          id.toString(),
          null,
          actor.roles().contains(ComplaintAccess.ROLE_APPRAISER)
              ? ComplaintAccess.ROLE_APPRAISER
              : ComplaintAccess.ROLE_DECIDER);
    }
    return view(tenant, h, read);
  }

  /** Số ngày làm việc còn tới hạn trả lời, âm khi quá hạn; nháp trả {@code null}. */
  public Integer responseDaysLeft(TenantId tenant, Complaint c) {
    return c.responseDeadline() == null ? null : days.left(tenant, today(), c.responseDeadline());
  }

  /** Ứng viên tiếp nhận hiện hành (SVC-06 giao task theo cùng quy tắc). */
  @Transactional(transactionManager = TX, readOnly = true)
  public ComplaintHandlerRules.Candidates candidates(TenantId tenant, UUID id) {
    Handled h = complaints.find(tenant, id).orElseThrow(ComplaintService.NotFoundException::new);
    return access.candidates(tenant, h.complaint());
  }

  // ---------------------------------------------------------------- ghi

  /**
   * {@code acceptComplaint}: {@code MOI} sang {@code DA_TIEP_NHAN}, người gọi thành người xử lý.
   */
  @Transactional(TX)
  public View accept(TenantId tenant, Actor actor, UUID id, String ifMatch, String note) {
    if (note != null && note.length() > ComplaintHandlerRules.REASON_MAX) {
      throw invalid("note", "Ghi chú tiếp nhận tối đa 2.000 ký tự.");
    }
    Handled h = lock(tenant, id);
    Complaint c = h.complaint();
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    ComplaintStatus next = next(c.status(), Step.ACCEPT);
    access.requireCandidate(tenant, actor, c);
    Complaint changed = c.withStatus(next);
    ComplaintHandling handling = h.handling().accepted(actor.userId(), clock.instant());
    save(tenant, changed, handling, c.rowVersion(), actor.userId());
    audit.record(
        tenant,
        actor.userId(),
        ACTION_ACCEPT,
        OBJECT_TYPE,
        id.toString(),
        null,
        ComplaintAccess.ROLE_APPRAISER);
    notices.notify(
        tenant,
        changed,
        List.of(c.complainantId()),
        ComplaintNotices.ACCEPTED,
        Map.of("ma", c.code()));
    return view(tenant, new Handled(changed, handling), true);
  }

  /**
   * {@code rejectComplaint}: không thụ lý có lý do (quá hạn, sai thẩm quyền, trùng nội dung đã trả
   * lời BR-12-11), báo người khiếu nại rồi tự đóng; hồ sơ đánh giá về {@code DA_KY_SO}.
   */
  @Transactional(TX)
  public View reject(
      TenantId tenant, Actor actor, UUID id, String ifMatch, CloseReason reason, String note) {
    if (reason == null || reason == CloseReason.RUT) {
      throw invalid("reasonCode", "Vui lòng chọn lý do không thụ lý.");
    }
    String text = reason(note, ComplaintHandlerRules.REJECT_REASON_MIN, "reason");
    Handled h = lock(tenant, id);
    Complaint c = h.complaint();
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    ComplaintStatus next = next(c.status(), Step.REJECT);
    access.requireCandidate(tenant, actor, c);
    return close(
        tenant,
        h,
        c.withStatus(next),
        h.handling().accepted(actor.userId(), null).closed(reason, text, clock.instant()),
        actor.userId(),
        ComplaintAccess.ROLE_APPRAISER,
        ACTION_REJECT,
        ComplaintNotices.REJECTED,
        "Không thụ lý khiếu nại: " + text);
  }

  /**
   * {@code withdrawComplaint}: người khiếu nại rút trước khi có kết luận, có lý do; {@code RUT} rồi
   * {@code DONG}, hồ sơ đánh giá về {@code DA_KY_SO}, nội dung vẫn được lưu.
   */
  @Transactional(TX)
  public View withdraw(TenantId tenant, Actor actor, UUID id, String ifMatch, String note) {
    String text = reason(note, ComplaintHandlerRules.WITHDRAW_REASON_MIN, "reason");
    Handled h = lock(tenant, id);
    Complaint c = h.complaint();
    if (!c.complainantId().equals(actor.userId())) {
      throw new ComplaintService.NotFoundException();
    }
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    ComplaintStatus next = next(c.status(), Step.WITHDRAW);
    if (h.handling().conclusion() != null) {
      throw new BusinessException(INVALID_STATE, "Khiếu nại đã có kết luận, không rút được nữa.");
    }
    return close(
        tenant,
        h,
        c.withStatus(next),
        h.handling().closed(CloseReason.RUT, text, clock.instant()),
        actor.userId(),
        ROLE_MEMBER,
        ACTION_WITHDRAW,
        ComplaintNotices.WITHDRAWN,
        "Rút khiếu nại: " + text);
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Ghi bước đóng ({@code KHONG_THU_LY} hoặc {@code RUT}), EVT-21, rồi tự {@code DONG}. */
  private View close(
      TenantId tenant,
      Handled before,
      Complaint stepped,
      ComplaintHandling handling,
      UUID actor,
      String role,
      String action,
      String template,
      String profileReason) {
    Complaint c = before.complaint();
    if (c.kind().reopensProfile()) {
      // Hồ sơ ở nguồn dữ liệu khác: lệnh của MOD-04 chạy trước, lặp lại an toàn.
      profiles.concludeComplaint(tenant, c.profileId(), actor, false, profileReason);
    }
    audit.record(tenant, actor, action, OBJECT_TYPE, c.id().toString(), null, role);
    notices.notify(tenant, stepped, List.of(c.complainantId()), template, Map.of("ma", c.code()));
    Complaint closed = stepped.withStatus(ComplaintWorkflow.next(stepped.status(), Step.CLOSE));
    save(tenant, closed, handling, c.rowVersion(), actor);
    audit.record(tenant, actor, ACTION_CLOSE, OBJECT_TYPE, c.id().toString(), null, role);
    return view(tenant, new Handled(closed, handling), true);
  }

  private boolean mayRead(TenantId tenant, Actor actor, Handled h) {
    Complaint c = h.complaint();
    if (access.conflicted(tenant, c, actor.userId())) {
      return false;
    }
    UUID handler = h.handling().handlerPersonId();
    if (handler != null && handler.equals(actor.userId())) {
      return true;
    }
    if (c.status() == ComplaintStatus.MOI
        && actor.roles().contains(ComplaintAccess.ROLE_APPRAISER)) {
      return access.candidates(tenant, c).people().contains(actor.userId());
    }
    return actor.roles().contains(ComplaintAccess.ROLE_DECIDER)
        && Set.of(
                ComplaintStatus.CHO_KET_LUAN,
                ComplaintStatus.DA_TRA_LOI,
                ComplaintStatus.DANG_THI_HANH,
                ComplaintStatus.DONG)
            .contains(c.status());
  }

  View view(TenantId tenant, Handled h, boolean withContent) {
    Complaint c = h.complaint();
    PeriodSnapshot period =
        periods == null ? null : periods.findById(tenant, PeriodId.of(c.periodId())).orElse(null);
    String conclusion =
        withContent && h.handling().conclusion() != null
            ? open(tenant, COLUMN_CONCLUSION, c.id(), h.handling().conclusion().textEnc())
            : null;
    return new View(
        c,
        h.handling(),
        period,
        withContent ? open(tenant, COLUMN_CONTENT, c.id(), c.contentEnc()) : null,
        withContent ? open(tenant, COLUMN_REQUEST, c.id(), c.requestTextEnc()) : null,
        conclusion,
        responseDaysLeft(tenant, c));
  }

  private String open(TenantId tenant, String column, UUID id, byte[] cipher) {
    if (cipher == null) {
      return null;
    }
    if (sealer == null) {
      throw new ComplaintService.SealingUnavailableException();
    }
    return sealer.open(tenant, column, id, cipher);
  }

  private Handled lock(TenantId tenant, UUID id) {
    return complaints.lock(tenant, id).orElseThrow(ComplaintService.NotFoundException::new);
  }

  private void save(
      TenantId tenant, Complaint changed, ComplaintHandling handling, int expected, UUID actor) {
    if (!complaints.update(tenant, changed, handling, expected, actor)) {
      throw new BusinessException(VERSION_CONFLICT, "Khiếu nại đã được cập nhật ở nơi khác.");
    }
  }

  static ComplaintStatus next(ComplaintStatus from, Step step) {
    try {
      return ComplaintWorkflow.next(from, step);
    } catch (ComplaintWorkflow.InvalidState e) {
      throw new BusinessException(INVALID_STATE, e.getMessage());
    }
  }

  private LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }

  static String reason(String raw, int min, String field) {
    int n = ComplaintRules.length(raw);
    if (n < min || n > ComplaintHandlerRules.REASON_MAX) {
      throw invalid(field, "Lý do cần từ " + min + " đến 2.000 ký tự.");
    }
    return raw.strip();
  }

  static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
