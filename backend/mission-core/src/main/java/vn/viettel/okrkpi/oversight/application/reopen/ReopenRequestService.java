package vn.viettel.okrkpi.oversight.application.reopen;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
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
 * Yêu cầu mở lại kỳ đã khoá (CORE-07-07, QT-13 bước 4, SCR-1301): lập, xem, tính thử ảnh hưởng và
 * huỷ. {@link #createInternal} là cổng nội bộ cho kết luận khiếu nại (CORE-07-04) và kết luận vi
 * phạm (CORE-07-11): cùng kiểm tra nghiệp vụ, bỏ kiểm phạm vi {@code Cd} vì nơi gọi đã kiểm quyền
 * của chính nó. Khi lập, {@link ReopenApprovalService#assign} chọn hai người duyệt (CORE-07-08).
 */
public class ReopenRequestService {

  static final String TX = "oversightTransactionManager";
  static final String OBJECT_TYPE = "reopen_request";
  static final String ACTION_CREATE = "TAO_YEU_CAU_MO_LAI_KY";
  static final String ACTION_CANCEL = "HUY_YEU_CAU_MO_LAI_KY";
  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_INSPECTOR = "inspector";
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final Set<String> AUDIT_KINDS = Set.of("KE_HOACH", "DOT_XUAT", "PHAN_ANH");

  private final ReopenRequestRepository requests;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final AccessDirectory directory;
  private final OversightAuditLog audit;
  private final ReopenImpactSimulator simulator;
  private final Clock clock;
  private final ReopenApprovalService approval;

  public ReopenRequestService(
      ReopenRequestRepository requests,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      AccessDirectory directory,
      OversightAuditLog audit,
      ReopenImpactSimulator simulator,
      Clock clock) {
    this(requests, periods, calendar, directory, audit, simulator, clock, null);
  }

  public ReopenRequestService(
      ReopenRequestRepository requests,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      AccessDirectory directory,
      OversightAuditLog audit,
      ReopenImpactSimulator simulator,
      Clock clock,
      ReopenApprovalService approval) {
    this.requests = Objects.requireNonNull(requests, "requests");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = calendar;
    this.directory = directory;
    this.audit = Objects.requireNonNull(audit, "audit");
    this.simulator = simulator;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.approval = approval;
  }

  /** Người gọi: mã người, vai trò đang chọn, đơn vị của phân công đang chọn, mức xác thực. */
  public record Actor(UUID userId, List<String> roles, UUID unitId, String acr, Long authTime) {
    public Actor(UUID userId, List<String> roles, UUID unitId) {
      this(userId, roles, unitId, null, null);
    }

    public Actor(UUID userId, List<String> roles, UUID unitId, String acr) {
      this(userId, roles, unitId, acr, null);
    }

    public Actor {
      Objects.requireNonNull(userId, "userId");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }

    boolean inspector() {
      return roles.contains(ROLE_INSPECTOR);
    }

    boolean appraiser() {
      return roles.contains(ROLE_APPRAISER);
    }
  }

  /** Nội dung yêu cầu theo {@code ReopenRequestInput}. */
  public record Draft(
      UUID periodId,
      String auditKind,
      ReopenScope scope,
      List<UUID> scopeIds,
      List<ReopenScopeItem> items,
      Integer openWorkingDays,
      String reason,
      ReopenBasis basis) {
    public Draft {
      scopeIds = scopeIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(scopeIds));
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /** Yêu cầu kèm kỳ (nhãn, trạng thái hiện hành) và các phê duyệt đã ghi. */
  public record View(
      ReopenRequest request,
      PeriodSnapshot period,
      List<ReopenRequestRepository.Approval> approvals) {}

  /**
   * Lập yêu cầu từ SCR-1301 (vai trò {@code appraiser} trong phạm vi {@code Cd}, {@code inspector}
   * toàn tenant).
   */
  @Transactional(TX)
  public View create(TenantId tenant, Actor actor, Draft draft) {
    if (!actor.inspector() && !actor.appraiser()) {
      throw outOfScope();
    }
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(actor.acr()))) {
      throw new StepUpRequiredException(
          "createReopenRequest", 900, "Lập yêu cầu mở lại kỳ cần xác thực nhiều lớp.");
    }
    return doCreate(tenant, actor.userId(), draft, actor.inspector() ? null : actor);
  }

  /**
   * Cổng nội bộ cho CORE-07-04 và CORE-07-11: lập yêu cầu thay mặt {@code requesterId} với cùng
   * kiểm tra kỳ, phạm vi, thời hạn, lý do, căn cứ và trùng phạm vi.
   */
  @Transactional(TX)
  public View createInternal(TenantId tenant, UUID requesterId, Draft draft) {
    return doCreate(tenant, Objects.requireNonNull(requesterId, "requesterId"), draft, null);
  }

  @Transactional(value = TX, readOnly = true)
  public View get(TenantId tenant, Actor actor, UUID id) {
    ReopenRequest r = visible(tenant, actor, id);
    return view(tenant, r);
  }

  @Transactional(value = TX, readOnly = true)
  public List<View> list(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Collection<ReopenStatus> statuses,
      boolean awaitingMyApproval,
      ReopenRequestRepository.After after,
      int fetchSize) {
    ReopenRequestRepository.Filter filter =
        new ReopenRequestRepository.Filter(
            periodId,
            statuses == null ? List.of() : List.copyOf(statuses),
            actor.inspector(),
            actor.userId(),
            actor.appraiser() ? unitsOf(tenant, actor.unitId()) : List.of(),
            awaitingMyApproval ? actor.userId() : null);
    List<View> out = new ArrayList<>();
    for (ReopenRequest r : requests.page(tenant, filter, after, fetchSize)) {
      out.add(view(tenant, r));
    }
    return out;
  }

  /**
   * Tính thử, không ghi gì (BR-13-06). Điều chỉnh bỏ trống thì lấy từ kết luận của khiếu nại căn
   * cứ.
   *
   * @throws SimulationUnavailableException khi cell chưa có hiện thực mô phỏng của SVC-07
   */
  @Transactional(value = TX, readOnly = true)
  public ReopenImpactSimulator.Impact simulate(
      TenantId tenant, Actor actor, UUID id, List<Adjustment> adjustments) {
    ReopenRequest r = visible(tenant, actor, id);
    List<Adjustment> used = adjustments;
    if ((used == null || used.isEmpty())
        && r.basis() != null
        && r.basis().kind() == ReopenBasis.Kind.KHIEU_NAI
        && r.basis().id() != null) {
      used = requests.complaintAdjustments(tenant, r.basis().id());
    }
    if (simulator == null) {
      throw new SimulationUnavailableException();
    }
    List<UUID> profiles = new ArrayList<>();
    if (r.scope() == ReopenScope.HO_SO) {
      profiles.addAll(r.scopeIds());
    }
    for (ReopenScopeItem item : r.items()) {
      if (item.profileId() != null && !profiles.contains(item.profileId())) {
        profiles.add(item.profileId());
      }
    }
    return simulator.simulate(
        tenant,
        new ReopenImpactSimulator.Request(
            r.periodId(), r.scope(), r.scopeIds(), profiles, used == null ? List.of() : used));
  }

  /** Người lập huỷ yêu cầu trước khi đủ hai phê duyệt (SCR-1301 "Huỷ yêu cầu"). */
  @Transactional(TX)
  public View cancel(TenantId tenant, Actor actor, UUID id, String ifMatch, String reason) {
    ReopenRequest r = requests.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (!r.requestedBy().equals(actor.userId())) {
      if (!canSee(tenant, actor, r)) {
        throw new NotFoundException();
      }
      throw outOfScope();
    }
    ETags.requireMatch(ifMatch, r.rowVersion(), "OVERSIGHT");
    if (!r.status().cancellable()) {
      throw new BusinessException(
          "OVERSIGHT-409-INVALID-STATE", "Yêu cầu đã được duyệt hoặc đã kết thúc, không thể huỷ.");
    }
    if (!ReopenRules.cancelReasonValid(reason)) {
      throw invalid("reason", "Lý do huỷ cần từ 10 đến 2.000 ký tự.");
    }
    ReopenRequest cancelled = r.cancelled(reason.strip(), r.rowVersion() + 1);
    if (!requests.update(tenant, cancelled, r.rowVersion(), actor.userId())) {
      throw new BusinessException(
          "OVERSIGHT-409-VERSION-CONFLICT", "Yêu cầu đã được người khác cập nhật.");
    }
    audit.record(
        tenant,
        actor.userId(),
        ACTION_CANCEL,
        OBJECT_TYPE,
        r.id().toString(),
        null,
        primaryRole(actor));
    return view(tenant, cancelled);
  }

  private View doCreate(TenantId tenant, UUID requester, Draft d, Actor scopedTo) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (d.periodId() == null) {
      errors.add(field("periodId", "Chọn kỳ đánh giá."));
    }
    if (d.auditKind() == null || !AUDIT_KINDS.contains(d.auditKind())) {
      errors.add(field("auditKind", "Chọn loại hậu kiểm."));
    }
    if (d.scope() == null) {
      errors.add(field("scope", "Chọn phạm vi mở lại."));
    }
    int days = d.openWorkingDays() == null ? 3 : d.openWorkingDays();
    if (!ReopenRules.openDaysValid(days)) {
      errors.add(field("openWorkingDays", "Thời hạn mở từ 1 đến 10 ngày làm việc."));
    }
    if (!ReopenRules.reasonValid(d.reason())) {
      errors.add(field("reason", "Lý do mở lại cần từ 50 đến 4.000 ký tự."));
    }
    if (d.basis() == null || !d.basis().complete()) {
      errors.add(field("basis", "Chọn căn cứ mở lại."));
    }
    if (d.items().isEmpty() || !d.items().stream().allMatch(ReopenScopeItem::wellFormed)) {
      errors.add(field("scopeDetail", "Nêu ít nhất một đối tượng và dữ liệu được phép sửa."));
    }
    if (d.scope() != null && d.scope() != ReopenScope.TOAN_KY && d.scopeIds().isEmpty()) {
      errors.add(field("scopeIds", "Chọn ít nhất một đơn vị hoặc hồ sơ."));
    }
    if (d.scope() == ReopenScope.TOAN_KY && !ReopenRules.wholePeriodAllowed(d.basis())) {
      errors.add(field("scope", "Chỉ mở lại toàn kỳ khi có quyết định hậu kiểm toàn diện."));
    }
    if (d.scope() == ReopenScope.HO_SO) {
      for (ReopenScopeItem item : d.items()) {
        if (item.profileId() != null && !d.scopeIds().contains(item.profileId())) {
          errors.add(field("scopeDetail", "Đối tượng phải thuộc danh sách hồ sơ đã chọn."));
          break;
        }
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Yêu cầu mở lại kỳ chưa hợp lệ.", List.copyOf(errors));
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(d.periodId()))
            .orElseThrow(() -> invalid("periodId", "Không tìm thấy kỳ đánh giá."));
    if (period.status() != PeriodStatus.DA_KHOA) {
      throw new BusinessException(
          "OVERSIGHT-409-PERIOD-NOT-LOCKED", "Chỉ lập yêu cầu mở lại cho kỳ đã khoá.");
    }
    if (d.basis().kind() != ReopenBasis.Kind.QUYET_DINH
        && !requests.basisExists(tenant, d.periodId(), d.basis())) {
      throw invalid("basis", "Không tìm thấy căn cứ trong kỳ đã chọn.");
    }
    List<UUID> units = unitsOfDraft(d);
    if (scopedTo != null) {
      requireWithin(tenant, scopedTo, d, units);
    }
    for (ReopenRequest other : requests.ofPeriod(tenant, d.periodId())) {
      if (other.active() && other.overlaps(d.scope(), d.scopeIds())) {
        throw new BusinessException(
            "OVERSIGHT-409-INVALID-STATE",
            "Đã có yêu cầu mở lại đang xử lý cho cùng phạm vi của kỳ này.");
      }
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    int year = today.getYear();
    requests.lockCodes(tenant, year);
    int next = 1;
    for (String c : requests.codesOfYear(tenant, year)) {
      next = Math.max(next, ReopenRules.sequenceOf(c, year) + 1);
    }
    LocalDate lastDay = addWorkingDays(tenant, today, days);
    ReopenRequest r =
        new ReopenRequest(
            Ids.newUuidV7(),
            d.periodId(),
            bucket(period),
            ReopenRules.code(year, next),
            d.scope(),
            d.scope() == ReopenScope.TOAN_KY ? List.of() : d.scopeIds(),
            d.items(),
            units,
            d.basis(),
            d.auditKind(),
            d.reason().strip(),
            requester,
            ReopenRules.APPROVALS_REQUIRED,
            ReopenStatus.CHO_DUYET_1,
            days,
            lastDay.plusDays(1).atStartOfDay(ZONE).toInstant().minusMillis(1),
            null,
            null,
            null,
            ReopenWorkflow.empty(scopedTo == null ? null : scopedTo.unitId()),
            clock.instant(),
            0);
    if (approval != null) {
      r = approval.assign(tenant, r);
    }
    requests.insert(tenant, r, requester);
    audit.record(
        tenant,
        requester,
        ACTION_CREATE,
        OBJECT_TYPE,
        r.id().toString(),
        null,
        scopedTo == null ? null : ROLE_APPRAISER);
    return new View(r, period, List.of());
  }

  /** Đơn vị của phạm vi: mã đơn vị khi {@code DON_VI} và mọi {@code unitId} trong đối tượng. */
  private static List<UUID> unitsOfDraft(Draft d) {
    Set<UUID> out = new LinkedHashSet<>();
    if (d.scope() == ReopenScope.DON_VI) {
      out.addAll(d.scopeIds());
    }
    for (ReopenScopeItem item : d.items()) {
      if (item.unitId() != null) {
        out.add(item.unitId());
      }
    }
    return List.copyOf(out);
  }

  /** Phạm vi {@code Cd} của người lập: không toàn kỳ, mọi đơn vị thuộc cây đơn vị của người lập. */
  private void requireWithin(TenantId tenant, Actor actor, Draft d, List<UUID> units) {
    if (d.scope() == ReopenScope.TOAN_KY || actor.unitId() == null) {
      throw outOfScope();
    }
    if (d.scope() == ReopenScope.HO_SO && d.items().stream().anyMatch(i -> i.unitId() == null)) {
      throw outOfScope();
    }
    Set<UUID> allowed = new HashSet<>(unitsOf(tenant, actor.unitId()));
    if (units.isEmpty() || !allowed.containsAll(units)) {
      throw outOfScope();
    }
  }

  private List<UUID> unitsOf(TenantId tenant, UUID unit) {
    if (unit == null) {
      return List.of();
    }
    List<UUID> out = new ArrayList<>();
    out.add(unit);
    if (directory != null) {
      out.addAll(directory.subunitsOf(tenant, unit));
    }
    return out;
  }

  private ReopenRequest visible(TenantId tenant, Actor actor, UUID id) {
    ReopenRequest r = requests.find(tenant, id).orElseThrow(NotFoundException::new);
    if (!canSee(tenant, actor, r)) {
      throw new NotFoundException();
    }
    return r;
  }

  private boolean canSee(TenantId tenant, Actor actor, ReopenRequest r) {
    if (actor.inspector() || r.requestedBy().equals(actor.userId())) {
      return true;
    }
    if (actor.appraiser() && !r.units().isEmpty()) {
      Set<UUID> allowed = new HashSet<>(unitsOf(tenant, actor.unitId()));
      if (r.units().stream().anyMatch(allowed::contains)) {
        return true;
      }
    }
    if (r.workflow().slots().stream().anyMatch(s -> actor.userId().equals(s.assignee()))) {
      return true;
    }
    return requests.approvals(tenant, r.periodBucket(), r.id()).stream()
        .anyMatch(a -> actor.userId().equals(a.approverId()));
  }

  private View view(TenantId tenant, ReopenRequest r) {
    Optional<PeriodSnapshot> period = periods.findById(tenant, PeriodId.of(r.periodId()));
    return new View(r, period.orElse(null), requests.approvals(tenant, r.periodBucket(), r.id()));
  }

  private LocalDate addWorkingDays(TenantId tenant, LocalDate from, int n) {
    if (calendar != null) {
      return calendar.addWorkingDays(tenant, from, n);
    }
    LocalDate d = from;
    int left = n;
    while (left > 0) {
      d = d.plusDays(1);
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        left--;
      }
    }
    return d;
  }

  /**
   * {@code period_bucket} như {@code Period.bucketOf}: kỳ năm, năm học (dài hơn bốn tháng) lấy quý
   * 0, còn lại lấy quý của ngày bắt đầu (cùng cách {@code OkrComposeService.bucket}).
   */
  static int bucket(PeriodSnapshot p) {
    LocalDate start = p.startDate();
    boolean yearly = java.time.temporal.ChronoUnit.DAYS.between(start, p.endDate()) > 120;
    return start.getYear() * 10 + (yearly ? 0 : (start.getMonthValue() - 1) / 3 + 1);
  }

  private static String primaryRole(Actor actor) {
    return actor.inspector() ? ROLE_INSPECTOR : actor.appraiser() ? ROLE_APPRAISER : null;
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }

  private static BusinessException outOfScope() {
    return new BusinessException(
        "COMMON-403-OUT-OF-SCOPE", "Phạm vi mở lại nằm ngoài đơn vị bạn phụ trách.");
  }

  /** Yêu cầu không tồn tại hoặc người gọi không được xem (lớp REST trả 404). */
  public static final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException() {
      super("Không tìm thấy yêu cầu mở lại kỳ");
    }
  }

  /** Cell chưa có hiện thực mô phỏng của SVC-07 (DV-07-08); lớp REST trả 503. */
  public static final class SimulationUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SimulationUnavailableException() {
      super("Chưa tính thử được ảnh hưởng, vui lòng thử lại sau.");
    }
  }
}
