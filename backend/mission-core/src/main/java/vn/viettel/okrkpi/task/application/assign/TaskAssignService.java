package vn.viettel.okrkpi.task.application.assign;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.events.NhiemVuDaGiao;
import vn.viettel.okrkpi.task.application.assign.port.BulkAssignJobs;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.mine.LeadWorkloads;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules.CheckItem;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;

/**
 * Kiểm tra trước khi giao, giao đơn lẻ và giao đồng loạt của QT-05 (SCR-0501, CORE-02-14). Giao
 * chạy lệnh ASSIGN của máy trạng thái ({@code NHAP → DA_GIAO}, {@code task_state_log}, EVT-06), rồi
 * ghi EVT-04 và EVT-21 MSG-05-14 tới chủ trì trong cùng giao dịch.
 */
public class TaskAssignService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String RESTRICTED_TITLE = "Nhiệm vụ hạn chế";

  /** Trường của {@code TaskDraft} tương ứng với từng quy tắc, để giao diện tô đúng ô. */
  static final Map<String, String> FIELDS =
      Map.of(
          "BR-05-01", "leadPersonId",
          "BR-05-02", "acceptorId",
          "BR-05-03", "productName",
          "BR-05-04", "planItemId",
          "BR-05-09", "basis",
          "BR-04-03", "title");

  private final TaskDraftService drafts;
  private final TaskDraftRepository repository;
  private final TaskTransitionService transitions;
  private final TaskAssignedEvents events;
  private final BulkAssignJobs jobs;
  private final PeriodApi periods;
  private final OkrApi okr;
  private final OrgDirectoryApi org;
  private final WorkingCalendarApi calendar;
  private final Function<TenantId, List<String>> vagueWords;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate perUnit;
  private final Executor executor;
  private final Clock clock;
  private final LeadWorkloads workloads;

  public TaskAssignService(
      TaskDraftService drafts,
      TaskDraftRepository repository,
      TaskTransitionService transitions,
      TaskAssignedEvents events,
      BulkAssignJobs jobs,
      PeriodApi periods,
      OkrApi okr,
      OrgDirectoryApi org,
      WorkingCalendarApi calendar,
      Function<TenantId, List<String>> vagueWords,
      ApplicationEventPublisher publisher,
      PlatformTransactionManager transactionManager,
      Executor executor,
      Clock clock,
      LeadWorkloads workloads) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.events = Objects.requireNonNull(events, "events");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.okr = okr;
    this.org = Objects.requireNonNull(org, "org");
    this.calendar = calendar;
    this.vagueWords = Objects.requireNonNull(vagueWords, "vagueWords");
    this.publisher = publisher == null ? e -> {} : publisher;
    this.perUnit = new TransactionTemplate(transactionManager);
    this.perUnit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.workloads = workloads;
  }

  /** Kết quả kiểm tra: danh sách quy tắc và số ngày làm việc tới hạn (nếu tính được). */
  public record CheckResult(
      List<CheckItem> items, Integer workingDays, LeadWorkloads.Result workload) {}

  /** Kết quả giao: nhiệm vụ sau khi giao và chủ trì. */
  public record Assigned(TaskRow task, UUID leadId) {}

  /** Kiểm tra tức thì của bản đang soạn (QT-05 bước 5); không ghi dữ liệu. */
  @Transactional(value = TX, readOnly = true)
  public CheckResult check(TenantId tenant, TaskDraftSpec spec, TaskActor actor) {
    TaskDraftService.validate(spec);
    PeriodSnapshot period = drafts.period(tenant, spec.periodId());
    int bucket = PlanSchedule.bucketOf(period.startDate());
    List<CheckItem> items = new ArrayList<>(rules(tenant, spec, period, bucket, actor.personId()));
    LeadWorkloads.Result workload = null;
    if (workloads != null && spec.leadPersonId() != null && spec.dueDate() != null) {
      // BR-05-11 (CORE-02-18): cảnh báo, không chặn.
      workload =
          workloads.of(tenant, bucket, period.id().value(), spec.leadPersonId(), spec.dueDate());
      items.add(workload.check());
    }
    return new CheckResult(List.copyOf(items), workingDays(tenant, spec.dueDate()), workload);
  }

  /**
   * Giao nhiệm vụ {@code NHAP}: kiểm quy tắc chặn (422 kèm {@code rule} trong {@code errors[]}), KR
   * cùng kỳ đã duyệt, chủ trì còn công tác; chạy ASSIGN rồi ghi EVT-04, EVT-21.
   */
  @Transactional(TX)
  public Assigned assignTask(TenantId tenant, UUID taskId, long expected, TaskActor actor) {
    DraftRow draft = repository.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
    TaskDraftService.requireDraft(draft, expected);
    try {
      periods.assertWritable(tenant, PeriodId.of(draft.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(TaskDraftService.PERIOD_LOCKED, "Kỳ đã khoá, không giao được");
    }
    PeriodSnapshot period = drafts.period(tenant, draft.periodId());
    List<CheckItem> items =
        rules(tenant, draft.spec(), period, draft.periodBucket(), draft.assignerId());
    List<AssigneeRow> assignees = repository.assignees(tenant, draft.periodBucket(), taskId);
    UUID leadUnit =
        assignees.stream()
            .filter(a -> "CHU_TRI".equals(a.role()) && "UNIT".equals(a.kind()))
            .map(AssigneeRow::id)
            .findFirst()
            .orElse(null);
    if (leadUnit != null) {
      items =
          rules(
              tenant,
              draft.spec().withLead(leadUnit),
              period,
              draft.periodBucket(),
              draft.assignerId());
    }
    requirePassed(items);
    requireKeyResult(tenant, draft.spec());
    if (leadUnit == null) {
      requireActive(tenant, draft.spec().leadPersonId());
    }
    return doAssign(tenant, draft, assignees, leadUnit, expected, actor);
  }

  private Assigned doAssign(
      TenantId tenant,
      DraftRow draft,
      List<AssigneeRow> assignees,
      UUID leadUnit,
      long expected,
      TaskActor actor) {
    TaskRow task =
        transitions
            .transition(tenant, draft.id(), new TaskCommand.Assign(), actor, expected)
            .task();
    repository.markAssigned(tenant, draft.periodBucket(), draft.id(), clock.instant());
    UUID lead = leadUnit != null ? leadUnit : draft.spec().leadPersonId();
    List<UUID> co =
        assignees.stream().filter(a -> "PHOI_HOP".equals(a.role())).map(AssigneeRow::id).toList();
    List<UUID> notify = leadUnit != null ? org.headsOf(tenant, leadUnit, today()) : List.of(lead);
    events.assigned(
        new TaskAssignedEvents.Assigned(
            tenant,
            draft.id(),
            draft.spec().planItemId(),
            draft.restricted() ? RESTRICTED_TITLE : draft.spec().title(),
            draft.code(),
            lead,
            co,
            notify,
            task.dueDate(),
            "THEN_CHOT".equals(draft.spec().taskGroup()),
            draft.restricted(),
            actor.personId(),
            task.rowVersion()));
    List<UUID> people = new ArrayList<>();
    assignees.stream().filter(a -> "PERSON".equals(a.kind())).forEach(a -> people.add(a.id()));
    publisher.publishEvent(
        new NhiemVuDaGiao(
            tenant, draft.periodId(), draft.id(), actor.personId(), people, clock.instant()));
    return new Assigned(task, lead);
  }

  /**
   * Giao đồng loạt (202): mỗi đơn vị nhận một nhiệm vụ riêng cùng nội dung, chủ trì là đơn vị đó,
   * mỗi nhiệm vụ một giao dịch; đơn vị lỗi không chặn đơn vị khác. Trả mã tác vụ.
   */
  public String bulkAssign(
      TenantId tenant, TaskDraftSpec spec, List<UUID> unitIds, TaskActor actor) {
    TaskDraftService.validate(spec);
    if (unitIds == null || unitIds.isEmpty() || unitIds.size() > 1000) {
      throw TaskDraftService.invalid("unitIds", "Danh sách đơn vị nhận không hợp lệ");
    }
    List<UUID> units = unitIds.stream().distinct().toList();
    String jobId = UUID.randomUUID().toString();
    jobs.accepted(tenant, jobId, actor.personId(), units.size());
    executor.execute(
        () -> {
          try {
            TenantContext.call(
                tenant,
                () -> {
                  runBulk(tenant, spec, units, actor, jobId);
                  return null;
                });
          } catch (Exception e) {
            jobs.finished(
                tenant,
                jobId,
                0,
                List.of(new BulkAssignJobs.Failure(null, "Giao đồng loạt bị gián đoạn")));
          }
        });
    return jobId;
  }

  void runBulk(
      TenantId tenant, TaskDraftSpec spec, List<UUID> units, TaskActor actor, String jobId) {
    int assigned = 0;
    List<BulkAssignJobs.Failure> failures = new ArrayList<>();
    for (int i = 0; i < units.size(); i++) {
      UUID unit = units.get(i);
      try {
        perUnit.executeWithoutResult(s -> assignToUnit(tenant, spec, unit, actor));
        assigned++;
      } catch (BusinessException e) {
        failures.add(new BulkAssignJobs.Failure(unit, e.getMessage()));
      } catch (RuntimeException e) {
        failures.add(new BulkAssignJobs.Failure(unit, "Không giao được cho đơn vị này"));
      }
      jobs.progress(tenant, jobId, i + 1, units.size());
    }
    jobs.finished(tenant, jobId, assigned, failures);
  }

  private void assignToUnit(TenantId tenant, TaskDraftSpec spec, UUID unit, TaskActor actor) {
    TaskDraftSpec forUnit = spec.withLead(null);
    DraftRow draft = drafts.insert(tenant, forUnit, actor, unit);
    PeriodSnapshot period = drafts.period(tenant, draft.periodId());
    requirePassed(
        rules(
            tenant, draft.spec().withLead(unit), period, draft.periodBucket(), draft.assignerId()));
    requireKeyResult(tenant, draft.spec());
    doAssign(
        tenant,
        draft,
        repository.assignees(tenant, draft.periodBucket(), draft.id()),
        unit,
        draft.rowVersion(),
        actor);
  }

  private List<CheckItem> rules(
      TenantId tenant, TaskDraftSpec spec, PeriodSnapshot period, int bucket, UUID assigner) {
    boolean planApproved =
        spec.planItemId() != null && repository.planItemApproved(tenant, bucket, spec.planItemId());
    return AssignmentRules.check(
        spec,
        new AssignmentRules.Context(
            period.startDate(),
            period.endDate(),
            planApproved,
            assigner,
            vagueWords.apply(tenant)));
  }

  static void requirePassed(List<CheckItem> items) {
    if (!AssignmentRules.blocked(items)) {
      return;
    }
    List<ProblemFieldError> errors =
        items.stream()
            .filter(i -> i.level() == AssignmentRules.Level.CHAN && !i.passed())
            .map(i -> new ProblemFieldError(FIELDS.get(i.rule()), i.message(), i.rule()))
            .toList();
    throw new BusinessException(VALIDATION, "Nhiệm vụ chưa đủ điều kiện để giao", errors);
  }

  private void requireKeyResult(TenantId tenant, TaskDraftSpec spec) {
    if (spec.keyResultId() == null || okr == null) {
      return;
    }
    boolean ok =
        okr.findKeyResult(tenant, spec.keyResultId())
            .filter(OkrApi.KeyResultSnapshot::approved)
            .filter(k -> k.periodId().value().equals(spec.periodId()))
            .isPresent();
    if (!ok) {
      throw TaskDraftService.invalid(
          "keyResultId", "Kết quả then chốt liên kết phải đã duyệt và cùng kỳ với nhiệm vụ");
    }
  }

  private void requireActive(TenantId tenant, UUID person) {
    if (person != null && org.assignmentsOf(tenant, person, today()).isEmpty()) {
      throw TaskDraftService.invalid(
          "leadPersonId", "Người chủ trì không còn công tác tại đơn vị trong hệ thống");
    }
  }

  private Integer workingDays(TenantId tenant, LocalDate due) {
    if (calendar == null || due == null) {
      return null;
    }
    LocalDate day = today();
    int n = 0;
    for (int i = 0; i < 400 && day.isBefore(due); i++) {
      day = day.plusDays(1);
      if (calendar.isWorkingDay(tenant, day)) {
        n++;
      }
    }
    return n;
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(TaskDraftService.ZONE));
  }
}
