package vn.viettel.okrkpi.oversight.application.alert;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.port.AlertMetrics;
import vn.viettel.okrkpi.oversight.application.port.AlertNotices;
import vn.viettel.okrkpi.oversight.application.port.AlertSubjects;
import vn.viettel.okrkpi.oversight.application.port.AlertSubjects.Subject;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository.Action;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository.After;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository.Filter;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository.Tally;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AlertWorkflow;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trung tâm cảnh báo (CORE-07-11, QT-14, SCR-1401): danh sách trong phạm vi cây tổ chức, chi tiết
 * (lần mở đầu của người xử lý chuyển {@code MOI} sang {@code DANG_XEM}), và hành động xử lý. Người
 * là đối tượng không thấy cảnh báo (BR-14-03) và không xử lý được (BR-14-08); chỉ {@code inspector}
 * kết luận (BR-14-02); mọi hành động có ghi chú và vào sổ cái qua {@code sys.audit.v1} (BR-14-05).
 */
public class AlertCaseService {

  static final String TX = "oversightTransactionManager";
  static final String OBJECT_TYPE = "anomaly_alert";
  static final String ACTION_VIEW = "XEM_CANH_BAO_BAT_THUONG";
  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_INSPECTOR = "inspector";
  static final String CLOSE_ACTION = "DONG";
  static final String CLOSE_NOTE = "Đã lập yêu cầu mở lại kỳ để thi hành kết luận có vi phạm.";
  static final int WINDOW_DAYS = 90;
  static final int WINDOW_LIMIT = 5000;
  private static final int MAX_SCAN = 10;

  private final AnomalyAlertRepository alerts;
  private final AlertSubjects subjects;
  private final AlertHandlers handlers;
  private final PeriodApi periods;
  private final AlertNotices notices;
  private final OversightAuditLog audit;
  private final AlertMetrics metrics;
  private final Clock clock;

  public AlertCaseService(
      AnomalyAlertRepository alerts,
      AlertSubjects subjects,
      AlertHandlers handlers,
      PeriodApi periods,
      AlertNotices notices,
      OversightAuditLog audit,
      AlertMetrics metrics,
      Clock clock) {
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.handlers = handlers;
    this.periods = Objects.requireNonNull(periods, "periods");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.metrics = metrics == null ? (s, o) -> {} : metrics;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Người gọi: vai trò và đơn vị của phân công đang chọn. */
  public record Viewer(UUID userId, List<String> roles, UUID unitId) {
    public Viewer {
      Objects.requireNonNull(userId, "userId");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }

    boolean inspector() {
      return roles.contains(ROLE_INSPECTOR);
    }

    boolean appraiser() {
      return roles.contains(ROLE_APPRAISER);
    }

    String role() {
      return inspector() ? ROLE_INSPECTOR : appraiser() ? ROLE_APPRAISER : null;
    }
  }

  /** Cảnh báo kèm đối tượng, kỳ và hành động (hành động chỉ có ở chi tiết). */
  public record View(
      AnomalyAlert alert, Subject subject, PeriodSnapshot period, List<Action> actions) {}

  /** Thẻ phân bố 90 ngày (SCR-1401 trường 3). */
  public record Counts(
      int windowDays, int newCount, int validCount, int assigned, int violations) {}

  /** Hành động theo {@code AlertActionInput}. */
  public record Command(String type, String note, UUID assignedTo, String verdict) {}

  /** Cảnh báo không tồn tại hoặc người gọi không được thấy (lớp REST trả 404). */
  public static final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException() {
      super("Không tìm thấy cảnh báo");
    }
  }

  /** Tối đa {@code size} cảnh báo thấy được sau con trỏ, theo mức rồi thời gian phát hiện. */
  @Transactional(value = TX, readOnly = true)
  public List<View> list(TenantId tenant, Viewer viewer, Filter filter, After after, int size) {
    Set<UUID> scope = scope(tenant, viewer);
    if (filter.unitId() != null) {
      Set<UUID> branch = new HashSet<>(subjects.scopeOf(tenant, filter.unitId()));
      if (viewer.unitId() != null) {
        branch.retainAll(scope);
      }
      scope = branch;
    }
    List<View> out = new ArrayList<>();
    After cursor = after;
    int batch = Math.max(size, 50);
    for (int round = 0; round < MAX_SCAN && out.size() < size; round++) {
      List<AnomalyAlert> rows = alerts.page(tenant, filter, cursor, batch);
      Map<UUID, Subject> subs = subjects.resolve(tenant, rows);
      for (AnomalyAlert a : rows) {
        Subject s = subs.get(a.id());
        if (out.size() < size && visible(viewer, a, s, scope)) {
          out.add(new View(a, s, period(tenant, a), List.of()));
        }
      }
      if (rows.size() < batch) {
        break;
      }
      AnomalyAlert last = rows.get(rows.size() - 1);
      cursor = new After(AlertWorkflow.order(last.severity()), last.detectedAt(), last.id());
    }
    return out;
  }

  @Transactional(value = TX, readOnly = true)
  public Counts counts(TenantId tenant, Viewer viewer) {
    Set<UUID> scope = scope(tenant, viewer);
    List<Tally> rows =
        alerts.window(tenant, clock.instant().minus(Duration.ofDays(WINDOW_DAYS)), WINDOW_LIMIT);
    Map<UUID, Subject> subs = subjects.resolve(tenant, rows.stream().map(Tally::alert).toList());
    int n = 0;
    int valid = 0;
    int assigned = 0;
    int violations = 0;
    for (Tally t : rows) {
      if (!visible(viewer, t.alert(), subs.get(t.alert().id()), scope)) {
        continue;
      }
      n++;
      valid += t.valid() ? 1 : 0;
      assigned += t.assigned() ? 1 : 0;
      violations += t.violation() ? 1 : 0;
    }
    return new Counts(WINDOW_DAYS, n, valid, assigned, violations);
  }

  /** Chi tiết; người xử lý ({@code appraiser}) mở lần đầu thì chuyển {@code DANG_XEM}, luỹ đẳng. */
  @Transactional(TX)
  public View get(TenantId tenant, Viewer viewer, UUID id) {
    AnomalyAlert a = alerts.find(tenant, id).orElseThrow(NotFoundException::new);
    Subject s = subjects.resolve(tenant, List.of(a)).get(a.id());
    if (!visible(viewer, a, s, scope(tenant, viewer))) {
      throw new NotFoundException();
    }
    if (AlertRules.MOI.equals(a.status())
        && viewer.appraiser()
        && alerts.markViewed(tenant, a, viewer.userId())) {
      a = viewed(a);
      audit.record(
          tenant,
          viewer.userId(),
          ACTION_VIEW,
          OBJECT_TYPE,
          a.id().toString(),
          null,
          viewer.role());
    }
    return new View(a, s, period(tenant, a), alerts.actions(tenant, a));
  }

  /** Ghi hành động xử lý; trả cảnh báo với trạng thái mới. */
  @Transactional(TX)
  public View act(TenantId tenant, Viewer viewer, UUID id, String ifMatch, Command c) {
    validate(c);
    AnomalyAlert a = alerts.find(tenant, id).orElseThrow(NotFoundException::new);
    Subject s = subjects.resolve(tenant, List.of(a)).get(a.id());
    if (s != null && s.people().contains(viewer.userId())) {
      throw new BusinessException(
          "OVERSIGHT-403-SEPARATION-OF-DUTIES",
          "Đồng chí là đối tượng của cảnh báo nên không xử lý cảnh báo này.");
    }
    if (!visible(viewer, a, s, scope(tenant, viewer))) {
      throw new NotFoundException();
    }
    if (AlertWorkflow.CONCLUDE.equals(c.type()) && !viewer.inspector()) {
      throw new BusinessException(
          "OVERSIGHT-403-SEPARATION-OF-DUTIES", "Chỉ cơ quan kiểm tra được kết luận vi phạm.");
    }
    if (!viewer.inspector() && !viewer.appraiser()) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xử lý cảnh báo.");
    }
    ETags.requireMatch(ifMatch, a.rowVersion(), "OVERSIGHT");
    PeriodSnapshot period = period(tenant, a);
    boolean closable =
        AlertWorkflow.CONCLUDE.equals(c.type())
            && AlertWorkflow.VIOLATION.equals(c.verdict())
            && ((period != null && period.status() == PeriodStatus.MO)
                || alerts.reopenFiled(tenant, a.id()));
    AlertWorkflow.Decision d;
    try {
      d = AlertWorkflow.decide(a.status(), c.type(), c.verdict(), closable);
    } catch (AlertWorkflow.InvalidState e) {
      throw new BusinessException("OVERSIGHT-409-INVALID-STATE", e.getMessage());
    }
    UUID actionId = Ids.newUuidV7();
    String verdict = AlertWorkflow.CONCLUDE.equals(c.type()) ? c.verdict() : null;
    UUID assignee = AlertWorkflow.ASSIGN.equals(c.type()) ? c.assignedTo() : null;
    alerts.insertAction(
        tenant, a, c.type(), assignee, verdict, c.note().strip(), viewer.userId(), clock.instant());
    for (String next : d.statuses()) {
      if (!alerts.updateStatus(tenant, a, next, viewer.userId())) {
        throw new BusinessException(
            "OVERSIGHT-409-VERSION-CONFLICT", "Cảnh báo vừa được người khác xử lý.");
      }
      a = a.withStatus(next);
    }
    int unblocked = 0;
    if (d.unblock() && alerts.deactivateBlocks(tenant, a, viewer.userId()) > 0) {
      unblocked = AlertRules.evidenceIds(a.evidence()).size();
    }
    audit.record(
        tenant, viewer.userId(), c.type(), OBJECT_TYPE, a.id().toString(), null, viewer.role());
    switch (c.type()) {
      case AlertWorkflow.VALIDATE -> {
        Set<UUID> others = otherHandlers(tenant, a, viewer);
        if (!others.isEmpty()) {
          notices.validated(tenant, others, a.id(), unblocked, actionId);
        }
        metrics.decided(a.signalCode(), AlertWorkflow.VALIDATE);
      }
      case AlertWorkflow.ASSIGN -> {
        notices.assigned(tenant, assignee, a.id(), actionId);
        metrics.decided(a.signalCode(), AlertWorkflow.ASSIGN);
      }
      case AlertWorkflow.CONCLUDE -> metrics.decided(a.signalCode(), verdict);
      default -> {}
    }
    return new View(a, s, period, alerts.actions(tenant, a));
  }

  /**
   * Yêu cầu mở lại kỳ lấy cảnh báo làm căn cứ vừa được lập: cảnh báo có vi phạm chuyển {@code DONG}
   * (QT-14 "Hoàn tất"). Gọi trong giao dịch tạo yêu cầu.
   */
  public boolean closeOnReopen(TenantId tenant, UUID alertId, UUID actor) {
    Optional<AnomalyAlert> found = alerts.find(tenant, alertId);
    if (found.isEmpty() || !AlertWorkflow.awaitsReopen(found.get().status())) {
      return false;
    }
    AnomalyAlert a = found.get();
    alerts.insertAction(tenant, a, CLOSE_ACTION, null, null, CLOSE_NOTE, actor, clock.instant());
    if (!alerts.updateStatus(tenant, a, AlertRules.DONG, actor)) {
      throw new BusinessException(
          "OVERSIGHT-409-VERSION-CONFLICT", "Cảnh báo vừa được người khác xử lý.");
    }
    audit.record(tenant, actor, CLOSE_ACTION, OBJECT_TYPE, a.id().toString(), null, ROLE_INSPECTOR);
    return true;
  }

  /** Tên người (cho người thực hiện và người được giao trong hành động). */
  public Map<UUID, String> names(TenantId tenant, View v) {
    Set<UUID> people = new HashSet<>();
    for (Action x : v.actions()) {
      if (x.actor() != null) {
        people.add(x.actor());
      }
      if (x.assignedTo() != null) {
        people.add(x.assignedTo());
      }
    }
    return people.isEmpty() ? Map.of() : subjects.personNames(tenant, people);
  }

  /**
   * Đối tượng không thấy cảnh báo (BR-14-03); cảnh báo chưa tra được đơn vị ({@code NGUOI_DUYET})
   * chỉ cơ quan kiểm tra thấy; còn lại phải thuộc cây đơn vị của phân công.
   */
  static boolean visible(Viewer v, AnomalyAlert a, Subject s, Set<UUID> scope) {
    if (!v.inspector() && !v.appraiser()) {
      return false;
    }
    if (s != null && s.people().contains(v.userId())) {
      return false;
    }
    if (s == null || s.unitId() == null) {
      return v.inspector();
    }
    return scope.contains(s.unitId());
  }

  private Set<UUID> scope(TenantId tenant, Viewer viewer) {
    return viewer.unitId() == null ? Set.of() : subjects.scopeOf(tenant, viewer.unitId());
  }

  private Set<UUID> otherHandlers(TenantId tenant, AnomalyAlert a, Viewer viewer) {
    if (handlers == null) {
      return Set.of();
    }
    PeriodId p = PeriodId.of(a.periodId());
    Set<UUID> out =
        new HashSet<>(
            switch (a.subjectType()) {
              case "CA_NHAN" -> handlers.ofProfiles(tenant, p, List.of(a.subjectId()));
              case "DON_VI" -> handlers.ofUnits(tenant, p, List.of(a.subjectId()));
              default -> Set.<UUID>of();
            });
    out.remove(viewer.userId());
    return out;
  }

  private PeriodSnapshot period(TenantId tenant, AnomalyAlert a) {
    return periods.findById(tenant, PeriodId.of(a.periodId())).orElse(null);
  }

  private static AnomalyAlert viewed(AnomalyAlert a) {
    return new AnomalyAlert(
        a.id(),
        a.periodId(),
        a.periodBucket(),
        a.signalCode(),
        a.severity(),
        a.subjectType(),
        a.subjectId(),
        a.evidence(),
        a.detectedAt(),
        a.sourceEventId(),
        a.modelVersion(),
        AlertWorkflow.VIEWING,
        a.rowVersion());
  }

  private static void validate(Command c) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (c == null || c.type() == null || !AlertWorkflow.TYPES.contains(c.type())) {
      errors.add(new ProblemFieldError("type", "Chọn hành động xử lý.", null));
    }
    if (c == null || !AlertWorkflow.noteValid(c.note())) {
      errors.add(new ProblemFieldError("note", "Ghi chú xử lý cần từ 20 đến 2.000 ký tự.", null));
    }
    if (c != null && AlertWorkflow.ASSIGN.equals(c.type()) && c.assignedTo() == null) {
      errors.add(new ProblemFieldError("assignedTo", "Chọn người được giao kiểm tra.", null));
    }
    if (c != null
        && AlertWorkflow.CONCLUDE.equals(c.type())
        && (c.verdict() == null || !AlertWorkflow.VERDICTS.contains(c.verdict()))) {
      errors.add(new ProblemFieldError("verdict", "Chọn kết luận kiểm tra.", null));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", errors.get(0).message(), errors);
    }
  }
}
