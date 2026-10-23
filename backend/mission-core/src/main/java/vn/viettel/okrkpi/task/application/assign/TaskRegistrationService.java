package vn.viettel.okrkpi.task.application.assign;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.RegistrationNotices;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.CatalogDefaults;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations.OpenAdjustment;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations.Pending;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Cán bộ đăng ký nhiệm vụ từ danh mục và người quản lý trực tiếp duyệt hoặc từ chối (CORE-02-17,
 * QT-05 bước 1c và 3, SCR-0503, BR-05-08). Đăng ký tạo nhiệm vụ {@code NHAP} nguồn {@code DANG_KY},
 * người đăng ký là chủ trì; chỉ thao tác duyệt mới giao được nhiệm vụ này. Duyệt thì giao ({@code
 * DA_GIAO}) và đưa vào phiên bản điều chỉnh kế hoạch đang soạn của đơn vị; chưa có phiên bản điều
 * chỉnh thì nhiệm vụ chờ và người lập kế hoạch được báo (MSG-05-12). Từ chối thì huỷ kèm lý do.
 */
public class TaskRegistrationService {

  static final String TX = "taskTransactionManager";
  static final String SOURCE = "DANG_KY";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String REJECTED = "TU_CHOI_DANG_KY";

  private final TaskDraftService drafts;
  private final TaskDraftRepository repository;
  private final TaskAssignService assign;
  private final TaskTransitionService transitions;
  private final CatalogService catalog;
  private final OrgDirectoryApi org;
  private final PeriodApi periods;
  private final TaskRegistrations registrations;
  private final RegistrationNotices notices;
  private final Clock clock;

  public TaskRegistrationService(
      TaskDraftService drafts,
      TaskDraftRepository repository,
      TaskAssignService assign,
      TaskTransitionService transitions,
      CatalogService catalog,
      OrgDirectoryApi org,
      PeriodApi periods,
      TaskRegistrations registrations,
      RegistrationNotices notices,
      Clock clock) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.assign = Objects.requireNonNull(assign, "assign");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.org = Objects.requireNonNull(org, "org");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.registrations = Objects.requireNonNull(registrations, "registrations");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Quyết định của người quản lý ({@code TaskRegistrationDecision}). */
  public record Decision(boolean approve, String reason, LocalDate dueDate, Integer quantity) {}

  /**
   * {@code registerTask}: mục danh mục phải đang áp dụng cho vị trí của người gọi; hạn đề xuất
   * trong kỳ đang mở.
   */
  @Transactional(TX)
  public TaskRow registerTask(
      TenantId tenant,
      UUID catalogItemId,
      BigDecimal quantity,
      LocalDate proposedDueDate,
      String note,
      TaskActor actor) {
    if (quantity == null || quantity.signum() <= 0) {
      throw TaskDraftService.invalid("quantity", "Số lượng phải lớn hơn 0");
    }
    if (note != null && note.length() > 1000) {
      throw TaskDraftService.invalid("note", "Ghi chú tối đa 1.000 ký tự");
    }
    PeriodSnapshot period =
        periods
            .findCurrent(tenant)
            .orElseThrow(
                () ->
                    new BusinessException(
                        TaskDraftService.PERIOD_LOCKED, "Chưa có kỳ đang mở để đăng ký"));
    try {
      periods.assertWritable(tenant, period.id());
    } catch (PeriodLockedException e) {
      throw new BusinessException(TaskDraftService.PERIOD_LOCKED, "Kỳ đã khoá, không đăng ký được");
    }
    CatalogItem item =
        catalog
            .applicableItem(tenant, actor.personId(), catalogItemId)
            .orElseThrow(
                () ->
                    TaskDraftService.invalid(
                        "catalogItemId", "Mục danh mục không áp dụng cho vị trí việc làm của bạn"));
    requireInPeriod("proposedDueDate", proposedDueDate, period);
    TaskDraftSpec spec =
        new TaskDraftSpec(
            period.id().value(),
            SOURCE,
            null,
            item.id(),
            item.name(),
            "THEN_CHOT".equals(item.group()) ? "THEN_CHOT" : "THUONG_XUYEN",
            null,
            "TRUNG_BINH",
            actor.personId(),
            List.of(),
            proposedDueDate,
            null,
            List.of(),
            nz(item.productName()),
            quantity,
            nz(item.unit()),
            nz(item.evidenceRequired()),
            nz(item.qualityCriteria()),
            null,
            null,
            note == null || note.isBlank() ? null : note.strip());
    DraftRow row = drafts.insert(tenant, spec, actor, null);
    List<UUID> managers =
        org.headsOf(tenant, row.orgUnitId(), today()).stream()
            .filter(m -> !m.equals(actor.personId()))
            .toList();
    notices.submitted(tenant, row.id(), row.code(), managers);
    return transitions.find(tenant, row.id()).orElseThrow(NoSuchElementException::new);
  }

  /**
   * {@code decideTaskRegistration}: chỉ người quản lý trực tiếp của đơn vị nhiệm vụ, khác người
   * đăng ký; nhiệm vụ còn {@code NHAP} và khớp {@code If-Match}.
   */
  @Transactional(TX)
  public TaskRow decide(
      TenantId tenant, UUID taskId, long expected, Decision decision, TaskActor actor) {
    DraftRow draft = repository.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (!SOURCE.equals(draft.spec().source())) {
      throw new BusinessException(INVALID_STATE, "Nhiệm vụ không do cán bộ đăng ký");
    }
    TaskDraftService.requireDraft(draft, expected);
    UUID registrant = draft.spec().leadPersonId();
    if (actor.personId().equals(registrant) || actor.personId().equals(draft.assignerId())) {
      throw new BusinessException(FORBIDDEN, "Người đăng ký không tự duyệt được đăng ký của mình");
    }
    if (!org.headsOf(tenant, draft.orgUnitId(), today()).contains(actor.personId())) {
      throw new BusinessException(
          FORBIDDEN, "Chỉ người quản lý trực tiếp của cán bộ được duyệt đăng ký");
    }
    writable(tenant, draft.periodId());
    if (decision == null) {
      throw TaskDraftService.invalid("approve", "Thiếu quyết định duyệt hoặc từ chối");
    }
    if (!decision.approve()) {
      String reason = decision.reason() == null ? "" : decision.reason().strip();
      if (reason.isEmpty() || reason.length() > 1000) {
        throw TaskDraftService.invalid("reason", "Cần nêu lý do từ chối, tối đa 1.000 ký tự");
      }
      transitions.cancelDraft(tenant, taskId, expected, REJECTED, actor, reason);
      notices.rejected(tenant, taskId, draft.code(), registrant);
      return transitions.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    }
    return approve(tenant, draft, expected, decision, actor);
  }

  private TaskRow approve(
      TenantId tenant, DraftRow draft, long expected, Decision decision, TaskActor actor) {
    PeriodSnapshot period = drafts.period(tenant, draft.periodId());
    TaskDraftSpec s = draft.spec();
    LocalDate due = decision.dueDate() != null ? decision.dueDate() : s.dueDate();
    if (decision.dueDate() != null) {
      requireInPeriod("dueDate", due, period);
    }
    if (decision.quantity() != null && (decision.quantity() < 1 || decision.quantity() > 99)) {
      throw TaskDraftService.invalid("quantity", "Số lượng từ 1 đến 99");
    }
    BigDecimal quantity =
        decision.quantity() != null ? BigDecimal.valueOf(decision.quantity()) : s.quantity();
    TaskDraftSpec approved =
        new TaskDraftSpec(
            s.periodId(),
            s.source(),
            s.planItemId(),
            s.catalogItemId(),
            s.title(),
            s.taskGroup(),
            s.keyResultId(),
            s.priority(),
            s.leadPersonId(),
            s.coAssignees(),
            due,
            actor.personId(),
            s.milestones(),
            s.productName(),
            quantity,
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            s.sourceDocNumber(),
            s.sourceDocTitle(),
            s.basis(),
            s.node());
    CatalogDefaults defaults =
        s.catalogItemId() == null
            ? new CatalogDefaults(null, null)
            : repository
                .catalogDefaults(tenant, s.catalogItemId())
                .orElse(new CatalogDefaults(null, null));
    long version =
        repository.storeDraft(
            tenant,
            draft,
            expected,
            approved,
            defaults.difficulty(),
            defaults.standardScore(),
            TaskDraftService.fingerprint(approved, null),
            actor.personId(),
            clock.instant());
    if (version < 0) {
      throw new VersionConflictException("TASK", expected, expected + 1);
    }
    assign.assignRegistration(tenant, draft.id(), version, actor);
    link(tenant, draft, period);
    return transitions.find(tenant, draft.id()).orElseThrow(NoSuchElementException::new);
  }

  /**
   * Gắn các nhiệm vụ đăng ký đã duyệt của đơn vị (kể cả nhiệm vụ vừa duyệt) vào phiên bản điều
   * chỉnh đang soạn; chưa có thì báo người lập kế hoạch (MSG-05-12).
   */
  private void link(TenantId tenant, DraftRow draft, PeriodSnapshot period) {
    int bucket = draft.periodBucket();
    UUID unit = draft.orgUnitId();
    var open = registrations.openAdjustment(tenant, bucket, period.id().value(), unit);
    if (open.isPresent()) {
      OpenAdjustment target = open.get();
      for (Pending p : registrations.pending(tenant, bucket, period.id().value(), unit)) {
        registrations.addItem(
            tenant, bucket, period.id().value(), target, p, target.planner(), clock.instant());
      }
      return;
    }
    List<UUID> planners =
        registrations
            .planner(tenant, bucket, period.id().value(), unit)
            .map(List::of)
            .orElseGet(() -> org.headsOf(tenant, unit, today()));
    notices.waitingForAdjustment(tenant, draft.id(), draft.code(), planners);
  }

  private void writable(TenantId tenant, UUID periodId) {
    try {
      periods.assertWritable(tenant, PeriodId.of(periodId));
    } catch (PeriodLockedException e) {
      throw new BusinessException(TaskDraftService.PERIOD_LOCKED, "Kỳ đã khoá, không đăng ký được");
    }
  }

  private static void requireInPeriod(String field, LocalDate due, PeriodSnapshot period) {
    if (due == null || due.isBefore(period.startDate()) || due.isAfter(period.endDate())) {
      throw TaskDraftService.invalid(field, "Hạn phải nằm trong kỳ đánh giá");
    }
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(TaskDraftService.ZONE));
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
