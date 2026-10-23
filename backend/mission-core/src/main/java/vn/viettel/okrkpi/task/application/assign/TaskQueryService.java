package vn.viettel.okrkpi.task.application.assign;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitFilter;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitTaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;

/**
 * Đọc nhiệm vụ của MOD-02 (CORE-02-14): chi tiết ({@code getTask}), người được giao ({@code
 * listTaskAssignees}) và việc của đơn vị ({@code listUnitTasks}). OPA kiểm vai trò và phạm vi; ở
 * đây chỉ chặn thêm người chỉ có vai trò cán bộ mà không tham gia nhiệm vụ, và che tiêu đề nhiệm vụ
 * hạn chế với người không tham gia.
 */
public class TaskQueryService {

  static final String TX = "taskTransactionManager";
  static final String RESTRICTED_TITLE = TaskAssignService.RESTRICTED_TITLE;
  static final int MAX_SUBUNIT_DEPTH = 10;

  private final TaskLifecycleRepository tasks;
  private final TaskDraftRepository drafts;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final Clock clock;

  public TaskQueryService(
      TaskLifecycleRepository tasks,
      TaskDraftRepository drafts,
      PeriodApi periods,
      OrgDirectoryApi org,
      Clock clock) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = Objects.requireNonNull(org, "org");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Chi tiết nhiệm vụ và vai trò của người gọi trên nhiệm vụ (rỗng khi không tham gia). */
  public record TaskView(TaskRow task, String callerRole, boolean masked) {}

  /**
   * Bộ lọc người dùng gửi lên của {@code listUnitTasks}. {@code source} và {@code
   * pendingChangeRequest} dựng hàng chờ duyệt của người giao việc: đăng ký ({@code NHAP} nguồn
   * {@code DANG_KY}, QT-05 bước 3) và đề nghị {@code CHO_DUYET} (SCR-0601 "Duyệt đề nghị").
   */
  public record UnitQuery(
      UUID unitId,
      UUID periodId,
      Set<String> statuses,
      UUID keyResultId,
      Boolean overdue,
      boolean includeSubunits,
      String q,
      String source,
      Boolean pendingChangeRequest) {

    public UnitQuery(
        UUID unitId,
        UUID periodId,
        Set<String> statuses,
        UUID keyResultId,
        Boolean overdue,
        boolean includeSubunits,
        String q) {
      this(unitId, periodId, statuses, keyResultId, overdue, includeSubunits, q, null, null);
    }
  }

  @Transactional(value = TX, readOnly = true)
  public TaskView getTask(TenantId tenant, UUID taskId, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    DraftRow draft = drafts.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
    List<AssigneeRow> assignees = drafts.assignees(tenant, task.periodBucket(), taskId);
    String role =
        assignees.stream()
            .filter(a -> "PERSON".equals(a.kind()) && a.id().equals(actor.personId()))
            .map(AssigneeRow::role)
            .findFirst()
            .orElse(null);
    boolean participant =
        role != null
            || actor.personId().equals(task.assignerId())
            || actor.personId().equals(task.acceptorId());
    if (!participant && memberOnly(actor)) {
      throw new NoSuchElementException();
    }
    if ("NHAP".equals(task.status().name()) && !participant && !actor.roles().contains("manager")) {
      throw new NoSuchElementException();
    }
    return new TaskView(task, role, draft.restricted() && !participant);
  }

  @Transactional(value = TX, readOnly = true)
  public List<AssigneeRow> listTaskAssignees(TenantId tenant, UUID taskId, TaskActor actor) {
    TaskView view = getTask(tenant, taskId, actor);
    return drafts.assignees(tenant, view.task().periodBucket(), taskId);
  }

  /** Việc của đơn vị theo kỳ; đơn vị mặc định là đơn vị công tác của người gọi. */
  @Transactional(value = TX, readOnly = true)
  public List<UnitTaskRow> listUnitTasks(
      TenantId tenant, UnitQuery query, TaskActor actor, UUID afterId, int limit) {
    UUID unit = query.unitId();
    if (unit == null) {
      unit =
          org.assignmentsOf(tenant, actor.personId(), today()).stream()
              .findFirst()
              .map(a -> a.orgUnitId())
              .orElseThrow(
                  () ->
                      new BusinessException(
                          "COMMON-403-OUT-OF-SCOPE", "Chưa xác định được đơn vị công tác"));
    }
    PeriodSnapshot period =
        query.periodId() == null
            ? periods.findCurrent(tenant).orElseThrow(NoSuchElementException::new)
            : periods
                .findById(tenant, PeriodId.of(query.periodId()))
                .orElseThrow(NoSuchElementException::new);
    Set<UUID> units = new LinkedHashSet<>();
    units.add(unit);
    if (query.includeSubunits()) {
      for (OrgTree tree : OrgTree.values()) {
        units.addAll(org.descendantsOf(tenant, tree, unit, MAX_SUBUNIT_DEPTH));
      }
    }
    List<UnitTaskRow> rows =
        drafts.listUnitTasks(
            tenant,
            PlanSchedule.bucketOf(period.startDate()),
            new UnitFilter(
                new ArrayList<>(units),
                period.id().value(),
                query.statuses(),
                query.keyResultId(),
                query.overdue(),
                query.q(),
                query.source(),
                query.pendingChangeRequest()),
            afterId,
            limit);
    return rows;
  }

  private static boolean memberOnly(TaskActor actor) {
    return actor.roles().stream().allMatch(r -> "member".equals(r));
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(TaskDraftService.ZONE));
  }
}
