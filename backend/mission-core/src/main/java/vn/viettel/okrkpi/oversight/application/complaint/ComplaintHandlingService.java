package vn.viettel.okrkpi.oversight.application.complaint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService.View;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintCloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandlerPolicy;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRules;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTransitions;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Xử lý khiếu nại (CORE-07-03, QT-12 bước 3, 4, SCR-1202): danh sách và chi tiết cho người xử lý,
 * tiếp nhận ({@code MOI} → {@code DA_TIEP_NHAN}), không thụ lý ({@code KHONG_THU_LY} rồi {@code
 * DONG}) và người khiếu nại rút ({@code RUT} rồi {@code DONG}). Người xử lý phải là ứng viên của
 * {@link ComplaintHandlerPolicy} (giữ vai trò {@code appraiser} ở đơn vị xử lý hoặc cấp trên khi
 * đơn vị không còn ai, chưa tham gia hồ sơ; BR-12-04). Không thụ lý và rút trả hồ sơ đánh giá về
 * {@code DA_KY_SO} qua cổng của MOD-04 trước khi ghi khiếu nại, lệnh đó lặp lại an toàn.
 */
public class ComplaintHandlingService {

  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_DECIDER = "decider";
  static final String ACTION_VIEW = "XEM_NOI_DUNG_KHIEU_NAI";
  static final String ACTION_ACCEPT = "TIEP_NHAN_KHIEU_NAI";
  static final String ACTION_REJECT = "KHONG_THU_LY_KHIEU_NAI";
  static final String ACTION_WITHDRAW = "RUT_KHIEU_NAI";
  static final String PURPOSE_HANDLE = "XU_LY_KHIEU_NAI";
  static final String TEMPLATE_ACCEPTED = "KHIEU_NAI_DA_TIEP_NHAN";
  static final String TEMPLATE_REJECTED = "KHIEU_NAI_KHONG_THU_LY";
  static final String TEMPLATE_WITHDRAWN = "KHIEU_NAI_DA_RUT";
  static final String SEPARATION_OF_DUTIES = "OVERSIGHT-403-SEPARATION-OF-DUTIES";
  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";

  /** Nhãn vai trò trong MSG-12-05 theo nhóm của ràng buộc 21.1. */
  private static final Map<String, String> ROLE_LABELS =
      Map.of(
          "acceptor", "nghiệm thu",
          "appraiser", "thẩm định",
          "proposer", "đề xuất",
          "decider", "quyết định");

  private final ComplaintRepository complaints;
  private final ComplaintService filing;
  private final ComplaintProfilesApi profiles;
  private final ComplaintHandlerDirectory directory;
  private final ComplaintEvents events;
  private final OversightAuditLog audit;
  private final Clock clock;

  public ComplaintHandlingService(
      ComplaintRepository complaints,
      ComplaintService filing,
      ComplaintProfilesApi profiles,
      ComplaintHandlerDirectory directory,
      ComplaintEvents events,
      OversightAuditLog audit,
      Clock clock) {
    this.complaints = Objects.requireNonNull(complaints, "complaints");
    this.filing = Objects.requireNonNull(filing, "filing");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Tài nguyên cho OPA ở lệnh ghi của người xử lý: đơn vị được chọn, ứng viên ({@code
   * designated_ids}) và người đã tham gia hồ sơ (ràng buộc 21.1).
   */
  public record ComplaintAuthz(
      TenantId tenant,
      UUID complaintId,
      UUID unitId,
      List<UUID> candidates,
      Map<String, Set<UUID>> participants)
      implements AuthzResourceProvider {

    public static final String TYPE = "complaint";

    public ComplaintAuthz {
      candidates = List.copyOf(candidates);
      participants = Map.copyOf(participants);
    }

    @Override
    public AuthzResource toAuthzResource() {
      Map<String, List<String>> groups = new LinkedHashMap<>();
      for (String g : ComplaintHandlerPolicy.GROUPS) {
        groups.put(g, ids(participants.getOrDefault(g, Set.of())));
      }
      return AuthzResource.builder()
          .type(TYPE)
          .id(complaintId.toString())
          .tenantId(tenant.value().toString())
          .ownerUnitId(unitId == null ? null : unitId.toString())
          .designatedIds(ids(candidates))
          .participants(groups)
          .build();
    }

    private static List<String> ids(java.util.Collection<UUID> people) {
      List<String> out = new ArrayList<>();
      people.forEach(p -> out.add(p.toString()));
      return out;
    }
  }

  // ---------------------------------------------------------------- đọc

  /** Người gọi đang chọn vai trò người xử lý hoặc người ký (phạm vi {@code Gn} của hợp đồng). */
  public static boolean handlerRole(Actor actor) {
    return actor.roles().contains(ROLE_APPRAISER) || actor.roles().contains(ROLE_DECIDER);
  }

  /**
   * Khiếu nại đã gửi thuộc đơn vị của phân công đang chọn và cấp dưới, cộng khiếu nại người gọi đã
   * tiếp nhận (suy luận: chưa có task của SVC-06, xem wiki trang 14).
   */
  @Transactional(transactionManager = ComplaintService.TX, readOnly = true)
  public List<Complaint> list(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      boolean assignedToMe,
      ComplaintRepository.HandlerAfter after,
      int limit) {
    return complaints.forHandler(
        tenant,
        scopeUnits(tenant, actor),
        actor.userId(),
        assignedToMe,
        periodId,
        statuses,
        kind,
        after,
        limit);
  }

  /** Chi tiết cho người xử lý trong phạm vi; mở nội dung C2 thì ghi nhật ký giải mã (BR-12-10). */
  @Transactional(ComplaintService.TX)
  public View get(TenantId tenant, Actor actor, UUID id) {
    Complaint c = complaints.find(tenant, id).orElseThrow(NotFoundException::new);
    if (c.draft() || !inScope(tenant, actor, c)) {
      throw new NotFoundException();
    }
    View v = filing.view(tenant, c, true);
    audit.record(
        tenant,
        actor.userId(),
        ACTION_VIEW,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        PURPOSE_HANDLE,
        primaryRole(actor));
    return v;
  }

  /** Ứng viên và người tham gia để hỏi OPA trước lệnh ghi; khiếu nại nháp thì không có. */
  @Transactional(transactionManager = ComplaintService.TX, readOnly = true)
  public ComplaintAuthz authz(TenantId tenant, UUID id) {
    Complaint c = complaints.find(tenant, id).orElseThrow(NotFoundException::new);
    if (c.draft()) {
      throw new NotFoundException();
    }
    Map<String, Set<UUID>> parts = directory.participants(tenant, c);
    ComplaintHandlerPolicy.Choice choice = choose(tenant, c, parts);
    return new ComplaintAuthz(
        tenant,
        c.id(),
        choice.unit() == null ? c.handlerUnitId() : choice.unit(),
        choice.candidates(),
        parts);
  }

  // ---------------------------------------------------------------- ghi

  /** {@code MOI} → {@code DA_TIEP_NHAN} bởi một ứng viên hợp lệ. */
  @Transactional(ComplaintService.TX)
  public View accept(TenantId tenant, Actor actor, UUID id, String ifMatch, String note) {
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    requireMove(c, ComplaintStatus.DA_TIEP_NHAN, "Chỉ tiếp nhận được khiếu nại mới gửi.");
    requireHandler(tenant, actor, c);
    if (note != null && note.length() > ComplaintRules.NOTE_MAX) {
      throw invalid("note", "Ghi chú tiếp nhận tối đa 2.000 ký tự.");
    }
    Complaint accepted = c.accepted(actor.userId(), clock.instant());
    save(tenant, accepted, c.rowVersion(), actor.userId());
    events.stateChanged(tenant, accepted, TEMPLATE_ACCEPTED, List.of(c.complainantId()));
    audit.record(
        tenant,
        actor.userId(),
        ACTION_ACCEPT,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ROLE_APPRAISER);
    return filing.view(tenant, accepted, false);
  }

  /** {@code MOI} → {@code KHONG_THU_LY} → {@code DONG}, bắt buộc nhóm lý do và lý do. */
  @Transactional(ComplaintService.TX)
  public View reject(
      TenantId tenant,
      Actor actor,
      UUID id,
      String ifMatch,
      ComplaintCloseReason code,
      String reason) {
    Complaint c = lockFiled(tenant, id);
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    requireMove(c, ComplaintStatus.KHONG_THU_LY, "Chỉ không thụ lý được khiếu nại mới gửi.");
    requireHandler(tenant, actor, c);
    List<ProblemFieldError> errors = new ArrayList<>();
    if (code == null || !code.rejection()) {
      errors.add(field("reasonCode", "Vui lòng chọn lý do không thụ lý."));
    }
    String text = strip(reason);
    if (ComplaintRules.length(text) < ComplaintRules.REJECT_REASON_MIN
        || ComplaintRules.length(text) > ComplaintRules.REASON_MAX) {
      errors.add(field("reason", "Lý do không thụ lý cần từ 20 đến 2.000 ký tự."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Chưa đủ thông tin để không thụ lý.", List.copyOf(errors));
    }
    Instant now = clock.instant();
    returnProfile(tenant, c, actor.userId(), text);
    Complaint rejected = c.notAccepted(actor.userId(), code, text, now);
    events.stateChanged(tenant, rejected, TEMPLATE_REJECTED, List.of(c.complainantId()));
    // Thông báo không thụ lý đã ghi outbox cùng giao dịch nên đóng ngay (bảng trạng thái QT-12).
    Complaint closed = rejected.closed(now);
    save(tenant, closed, c.rowVersion(), actor.userId());
    audit.record(
        tenant,
        actor.userId(),
        ACTION_REJECT,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ROLE_APPRAISER);
    return filing.view(tenant, closed, false);
  }

  /** Người khiếu nại rút trước khi có văn bản trả lời: {@code RUT} → {@code DONG}. */
  @Transactional(ComplaintService.TX)
  public View withdraw(TenantId tenant, Actor actor, UUID id, String ifMatch, String reason) {
    Complaint c = complaints.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (!c.complainantId().equals(actor.userId())) {
      throw new NotFoundException();
    }
    ETags.requireMatch(ifMatch, c.rowVersion(), "OVERSIGHT");
    if (c.draft()) {
      throw new BusinessException(
          ComplaintService.INVALID_STATE, "Khiếu nại chưa gửi, không cần rút.");
    }
    requireMove(
        c,
        ComplaintStatus.RUT,
        "Khiếu nại đã có văn bản trả lời hoặc đã đóng, không rút được nữa.");
    String text = strip(reason);
    if (ComplaintRules.length(text) < ComplaintRules.WITHDRAW_REASON_MIN
        || ComplaintRules.length(text) > ComplaintRules.REASON_MAX) {
      throw invalid("reason", "Lý do rút khiếu nại cần từ 10 đến 2.000 ký tự.");
    }
    Instant now = clock.instant();
    returnProfile(tenant, c, actor.userId(), text);
    Complaint withdrawn = c.withdrawn(text, now);
    Set<UUID> notify = new LinkedHashSet<>();
    if (c.handling().handlerId() != null) {
      notify.add(c.handling().handlerId());
    }
    events.stateChanged(tenant, withdrawn, TEMPLATE_WITHDRAWN, notify);
    Complaint closed = withdrawn.closed(now);
    save(tenant, closed, c.rowVersion(), actor.userId());
    audit.record(
        tenant,
        actor.userId(),
        ACTION_WITHDRAW,
        ComplaintService.OBJECT_TYPE,
        id.toString(),
        null,
        ComplaintService.ROLE_MEMBER);
    return filing.view(tenant, closed, false);
  }

  // ---------------------------------------------------------------- kiểm tra

  /**
   * BR-12-04 ở máy chủ, không phụ thuộc OPA: vai trò {@code appraiser} đang chọn, chưa tham gia hồ
   * sơ (403 tách vai trò, MSG-12-05) và là ứng viên của đơn vị được chọn (403 ngoài phạm vi).
   */
  private void requireHandler(TenantId tenant, Actor actor, Complaint c) {
    if (!actor.roles().contains(ROLE_APPRAISER)) {
      throw outOfScope();
    }
    Map<String, Set<UUID>> parts = directory.participants(tenant, c);
    List<String> conflicts = ComplaintHandlerPolicy.conflicts(actor.userId(), parts);
    if (!conflicts.isEmpty()) {
      throw new BusinessException(
          SEPARATION_OF_DUTIES,
          "Đồng chí không thể xử lý khiếu nại này vì đã tham gia "
              + ROLE_LABELS.get(conflicts.get(0))
              + " hồ sơ liên quan (PL 4 V.1).");
    }
    if (!choose(tenant, c, parts).candidates().contains(actor.userId())) {
      throw outOfScope();
    }
  }

  private ComplaintHandlerPolicy.Choice choose(
      TenantId tenant, Complaint c, Map<String, Set<UUID>> parts) {
    if (c.handlerUnitId() == null) {
      return ComplaintHandlerPolicy.choose(List.of(), Map.of(), parts);
    }
    Instant at = clock.instant();
    List<UUID> units = new ArrayList<>();
    units.add(c.handlerUnitId());
    units.addAll(directory.ancestorsOf(tenant, c.handlerUnitId()));
    Map<UUID, List<UUID>> appraisers = new LinkedHashMap<>();
    for (UUID u : units) {
      appraisers.put(u, directory.appraisersAt(tenant, u, at));
    }
    return ComplaintHandlerPolicy.choose(units, appraisers, parts);
  }

  private boolean inScope(TenantId tenant, Actor actor, Complaint c) {
    if (c.complainantId().equals(actor.userId())
        || actor.userId().equals(c.handling().handlerId())) {
      return true;
    }
    return handlerRole(actor) && scopeUnits(tenant, actor).contains(c.handlerUnitId());
  }

  private Set<UUID> scopeUnits(TenantId tenant, Actor actor) {
    Set<UUID> units = new LinkedHashSet<>();
    if (actor.unitId() != null && handlerRole(actor)) {
      units.add(actor.unitId());
      units.addAll(directory.subunitsOf(tenant, actor.unitId()));
    }
    return units;
  }

  /** Hồ sơ về {@code DA_KY_SO} khi không còn khiếu nại nào khác giữ nhánh {@code KHIEU_NAI}. */
  private void returnProfile(TenantId tenant, Complaint c, UUID actorId, String reason) {
    if (!c.kind().reopensProfile()
        || complaints.otherOpenReopening(tenant, c.profileId(), c.id())) {
      return;
    }
    profiles.returnToSigned(tenant, c.profileId(), actorId, reason);
  }

  private Complaint lockFiled(TenantId tenant, UUID id) {
    Complaint c = complaints.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (c.draft()) {
      throw new NotFoundException();
    }
    return c;
  }

  private static void requireMove(Complaint c, ComplaintStatus to, String message) {
    if (!ComplaintTransitions.allowed(c.status(), to)) {
      throw new BusinessException(ComplaintService.INVALID_STATE, message);
    }
  }

  private void save(TenantId tenant, Complaint changed, int expected, UUID actor) {
    if (!complaints.update(tenant, changed, expected, actor)) {
      throw new BusinessException(
          ComplaintService.VERSION_CONFLICT, "Khiếu nại đã được cập nhật ở nơi khác.");
    }
  }

  private static String primaryRole(Actor actor) {
    if (actor.roles().contains(ROLE_APPRAISER)) {
      return ROLE_APPRAISER;
    }
    if (actor.roles().contains(ROLE_DECIDER)) {
      return ROLE_DECIDER;
    }
    return ComplaintService.ROLE_MEMBER;
  }

  private static String strip(String s) {
    return s == null ? null : s.strip();
  }

  private static BusinessException outOfScope() {
    return new BusinessException(OUT_OF_SCOPE, "Khiếu nại này không được giao cho đồng chí xử lý.");
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }
}
