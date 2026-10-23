package vn.viettel.okrkpi.task.application.fromplan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.NewDraft;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.NewChange;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTaskNotices;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks.ItemContent;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks.LinkedTask;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TransitionTableProvider;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovalRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.CatalogRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.domain.assign.TaskCodes;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.fromplan.PlanLineSync;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;

/**
 * Sinh, cập nhật và huỷ nhiệm vụ khi kế hoạch quý được duyệt (CORE-02-11, QT-04 bước 8, QT-05 bước
 * 1a, BR-04-11). Gọi từ consumer EVT-03 trong giao dịch của inbox, nên nhận lại cùng sự kiện không
 * sinh trùng; mỗi dòng đã có nhiệm vụ gắn thì bỏ qua.
 *
 * <p>Dòng mới sinh nhiệm vụ {@code NHAP} nguồn {@code KE_HOACH} mang sản phẩm, số lượng, hạn, điểm,
 * mức khó của dòng, tiêu chuẩn chất lượng và minh chứng của mục danh mục. Dòng sửa ({@code
 * origin_item_id} trỏ dòng cũ đã có nhiệm vụ) cập nhật theo {@link PlanLineSync#update}. Dòng của
 * phiên bản cũ không còn được trỏ tới là dòng bỏ: bản nháp bị xoá, nhiệm vụ đã giao được huỷ qua
 * máy trạng thái bằng đề nghị huỷ đã duyệt (guard {@code HUY_DA_DUYET}, phát EVT-06); có minh chứng
 * đã xác nhận thì không huỷ, ghi cảnh báo đối soát và báo người lập.
 */
public class PlanTaskGenerator {

  static final String TX = "taskTransactionManager";
  static final String SOURCE = "KE_HOACH";
  static final String NOTE = "SINH_TU_KE_HOACH";
  static final String NOTE_REASON = "Sinh từ kế hoạch quý đã duyệt";
  static final String CANCEL_REASON = "Dòng kế hoạch đã bỏ khi điều chỉnh kế hoạch quý";
  static final String CANCEL = "CANCEL";
  private static final Logger log = LoggerFactory.getLogger(PlanTaskGenerator.class);

  /** Kết quả xử lý một lần duyệt. */
  public record Summary(
      int created, int updated, int discarded, int cancelled, List<String> blocked) {

    public Summary {
      blocked = List.copyOf(blocked);
    }

    static final Summary EMPTY = new Summary(0, 0, 0, 0, List.of());
  }

  private final PlanRepository plans;
  private final PlanTasks tasks;
  private final TaskDraftRepository drafts;
  private final TaskLifecycleRepository lifecycle;
  private final TaskTransitionService transitions;
  private final TaskChangeRequests changes;
  private final TaskDeliverables deliverables;
  private final TransitionTableProvider tables;
  private final PeriodApi periods;
  private final PlanTaskNotices notices;
  private final Clock clock;

  public PlanTaskGenerator(
      PlanRepository plans,
      PlanTasks tasks,
      TaskDraftRepository drafts,
      TaskLifecycleRepository lifecycle,
      TaskTransitionService transitions,
      TaskChangeRequests changes,
      TaskDeliverables deliverables,
      TransitionTableProvider tables,
      PeriodApi periods,
      PlanTaskNotices notices,
      Clock clock) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.deliverables = deliverables == null ? (t, id) -> List.of() : deliverables;
    this.tables = Objects.requireNonNull(tables, "tables");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Áp một EVT-03.
   *
   * @param supersededVersionId phiên bản đã duyệt trước đó (điều chỉnh), {@code null} khi duyệt lần
   *     đầu
   * @param approvedBy người duyệt; là người duyệt đề nghị huỷ nhiệm vụ của dòng bỏ
   */
  @Transactional(TX)
  public Summary onApproved(
      TenantId tenant, UUID planId, UUID versionId, UUID supersededVersionId, UUID approvedBy) {
    Optional<PlanRow> found = plans.findPlan(tenant, planId);
    if (found.isEmpty()) {
      log.warn("EVT-03: không thấy kế hoạch {} của tenant {}", planId, tenant.value());
      return Summary.EMPTY;
    }
    Run run = new Run(tenant, found.get(), versionId, approvedBy);
    run.apply(supersededVersionId);
    Summary summary =
        new Summary(run.created, run.updated, run.discarded, run.cancelled, run.blocked);
    notices.generated(
        tenant,
        planId,
        versionId,
        run.planner,
        summary.created(),
        summary.updated(),
        summary.cancelled() + summary.discarded());
    if (!summary.blocked().isEmpty()) {
      log.warn(
          "EVT-03 kế hoạch {}: không huỷ được nhiệm vụ {} của dòng đã bỏ (BR-04-11)",
          planId,
          summary.blocked());
      notices.cancelBlocked(tenant, planId, versionId, run.planner, summary.blocked());
    }
    return summary;
  }

  /** Trạng thái của một lần xử lý. */
  private final class Run {

    final TenantId tenant;
    final PlanRow plan;
    final UUID versionId;
    final UUID approvedBy;
    final UUID planner;
    final Instant now;
    final TransitionTable table;
    final PeriodSnapshot period;
    int created;
    int updated;
    int discarded;
    int cancelled;
    final List<String> blocked = new ArrayList<>();
    int nextCode;

    Run(TenantId tenant, PlanRow plan, UUID versionId, UUID approvedBy) {
      this.tenant = tenant;
      this.plan = plan;
      this.versionId = versionId;
      this.approvedBy = approvedBy;
      this.planner =
          plans
              .approvalRow(tenant, plan.periodBucket(), versionId)
              .map(ApprovalRow::createdBy)
              .orElse(approvedBy);
      this.now = clock.instant();
      this.table = tables.tableFor(tenant, plan.periodId());
      this.period = periods.findById(tenant, PeriodId.of(plan.periodId())).orElse(null);
    }

    void apply(UUID supersededVersionId) {
      int bucket = plan.periodBucket();
      List<ItemRow> items = plans.items(tenant, bucket, versionId);
      List<ItemRow> old =
          supersededVersionId == null
              ? List.of()
              : plans.items(tenant, bucket, supersededVersionId);
      Set<UUID> keys = new LinkedHashSet<>();
      Set<UUID> catalogIds = new HashSet<>();
      for (ItemRow i : items) {
        keys.add(i.id());
        if (i.originItemId() != null) {
          keys.add(i.originItemId());
        }
        if (i.catalogItemId() != null) {
          catalogIds.add(i.catalogItemId());
        }
      }
      old.forEach(o -> keys.add(o.id()));
      Map<UUID, LinkedTask> byItem = new HashMap<>();
      for (LinkedTask t : tasks.linked(tenant, bucket, keys)) {
        byItem.putIfAbsent(t.planItemId(), t);
      }
      Map<UUID, CatalogRow> catalog =
          catalogIds.isEmpty() ? Map.of() : plans.catalogItems(tenant, catalogIds);
      for (ItemRow i : items) {
        if (byItem.containsKey(i.id())) {
          continue;
        }
        LinkedTask prev = i.originItemId() == null ? null : byItem.get(i.originItemId());
        CatalogRow c = i.catalogItemId() == null ? null : catalog.get(i.catalogItemId());
        if (prev != null && !TaskStatus.HUY.name().equals(prev.status())) {
          update(prev, i, c);
        } else {
          create(i, c);
        }
      }
      for (ItemRow o : old) {
        LinkedTask t = byItem.get(o.id());
        if (t != null && !referenced(items, o.id())) {
          remove(t);
        }
      }
    }

    private boolean referenced(Collection<ItemRow> items, UUID oldId) {
      return items.stream().anyMatch(i -> oldId.equals(i.originItemId()));
    }

    private void create(ItemRow i, CatalogRow c) {
      int bucket = plan.periodBucket();
      if (nextCode == 0) {
        nextCode = drafts.nextCodeNumber(tenant, bucket);
      }
      String code = TaskCodes.code(bucket, nextCode++);
      UUID id = UUID.randomUUID();
      TaskDraftSpec spec = spec(i, c, null);
      UUID leadUnit = unitLead(i);
      drafts.insertDraft(
          tenant,
          new NewDraft(
              id,
              code,
              plan.periodId(),
              bucket,
              plan.ownerUnitId(),
              planner,
              spec,
              difficulty(i, c),
              score(i, c),
              TaskDraftService.fingerprint(spec, leadUnit),
              table.policyVersion(),
              leadUnit),
          now);
      lifecycle.appendStateLog(
          tenant,
          plan.periodId(),
          bucket,
          id,
          new StateLogRow(
              now, null, TaskStatus.NHAP, NOTE, planner, NOTE_REASON, table.policyVersion()),
          "{\"planItemId\":\"" + i.id() + "\",\"planVersionId\":\"" + versionId + "\"}");
      created++;
    }

    private void update(LinkedTask t, ItemRow i, CatalogRow c) {
      int bucket = plan.periodBucket();
      boolean done =
          switch (PlanLineSync.update(TaskStatus.valueOf(t.status()))) {
            case REWRITE_DRAFT -> {
              Optional<DraftRow> d = drafts.findDraft(tenant, t.id());
              if (d.isEmpty()) {
                yield false;
              }
              TaskDraftSpec spec = spec(i, c, d.get().spec());
              UUID leadUnit = unitLead(i);
              yield drafts.storeDraft(
                      tenant,
                      d.get(),
                      d.get().rowVersion(),
                      spec,
                      difficulty(i, c),
                      score(i, c),
                      TaskDraftService.fingerprint(spec, leadUnit),
                      planner,
                      now)
                  >= 0;
            }
            case REFRESH_ASSIGNED ->
                tasks.refreshAssigned(
                    tenant,
                    bucket,
                    t.id(),
                    new ItemContent(
                        i.id(),
                        i.title(),
                        product(i, c),
                        quantity(i),
                        i.unit(),
                        dueDate(i),
                        difficulty(i, c),
                        score(i, c)),
                    planner,
                    now);
            case RELINK -> false;
          };
      if (done) {
        updated++;
      } else {
        tasks.relink(tenant, bucket, t.id(), i.id(), planner, now);
      }
    }

    private void remove(LinkedTask t) {
      TaskStatus status = TaskStatus.valueOf(t.status());
      Optional<vn.viettel.okrkpi.task.domain.lifecycle.Transition> cancel =
          table.find(status, CANCEL);
      boolean confirmed =
          PlanLineSync.mayCancel(status)
              && deliverables.of(tenant, t.id()).stream()
                  .anyMatch(d -> "DA_XAC_NHAN".equals(String.valueOf(d.get("status"))));
      switch (PlanLineSync.removal(status, cancel.isPresent(), confirmed)) {
        case DISCARD_DRAFT -> {
          drafts.discardDraft(tenant, plan.periodBucket(), t.id(), t.rowVersion());
          discarded++;
        }
        case CANCEL -> {
          if (approveCancel(t)) {
            transitions.transition(
                tenant,
                t.id(),
                new TaskCommand.Cancel(CANCEL_REASON),
                new TaskActor(approvedBy, cancel.get().allowedRoles()),
                null);
            cancelled++;
          } else {
            blocked.add(t.code());
          }
        }
        case BLOCKED -> blocked.add(t.code());
        case NONE -> {
          // nhiệm vụ đã huỷ trước đó
        }
      }
    }

    /**
     * Đề nghị huỷ do người lập gửi, người duyệt kế hoạch duyệt ngay (bốn mắt: hai người phải khác
     * nhau); đề nghị huỷ đang chờ (nếu có) được duyệt luôn.
     */
    private boolean approveCancel(LinkedTask t) {
      int bucket = plan.periodBucket();
      UUID requestId = UUID.randomUUID();
      UUID author = planner;
      boolean inserted =
          !planner.equals(approvedBy)
              && changes.insert(
                  tenant,
                  new NewChange(
                      requestId,
                      t.id(),
                      plan.periodId(),
                      bucket,
                      "HUY",
                      null,
                      null,
                      CANCEL_REASON,
                      null,
                      null,
                      approvedBy,
                      Map.of(),
                      planner),
                  now);
      if (!inserted) {
        Optional<ChangeRow> pending =
            changes.listOf(tenant, bucket, t.id()).stream()
                .filter(r -> "HUY".equals(r.type()) && "CHO_DUYET".equals(r.status()))
                .findFirst();
        if (pending.isEmpty()) {
          return false;
        }
        requestId = pending.get().id();
        author = pending.get().createdBy();
      }
      if (approvedBy.equals(author)) {
        return false;
      }
      return changes.decide(
          tenant, bucket, requestId, "DA_DUYET", null, CANCEL_REASON, approvedBy, now);
    }

    private TaskDraftSpec spec(ItemRow i, CatalogRow c, TaskDraftSpec current) {
      boolean personLead = i.lead() != null && "PERSON".equals(i.lead().kind());
      return new TaskDraftSpec(
          plan.periodId(),
          SOURCE,
          i.id(),
          i.catalogItemId(),
          i.title(),
          i.taskGroup(),
          i.keyResultId(),
          current == null ? "TRUNG_BINH" : current.priority(),
          personLead ? i.lead().id() : null,
          i.coAssignees() == null
              ? List.of()
              : i.coAssignees().stream()
                  .map(a -> new TaskDraftSpec.Assignee(a.kind(), a.id()))
                  .toList(),
          dueDate(i),
          current == null ? null : current.acceptorId(),
          current == null ? List.of() : current.milestones(),
          product(i, c),
          quantity(i),
          i.unit() == null ? "" : i.unit(),
          c == null ? "" : nz(c.evidenceRequirement()),
          c == null ? "" : nz(c.qualityStandard()),
          null,
          null,
          current == null ? null : current.basis(),
          current == null ? TaskDraftSpec.Node.ROOT : current.node());
    }

    private UUID unitLead(ItemRow i) {
      return i.lead() != null && "UNIT".equals(i.lead().kind()) ? i.lead().id() : null;
    }

    private LocalDate dueDate(ItemRow i) {
      if (i.dueDate() != null || period == null) {
        return i.dueDate();
      }
      return period.endDate();
    }
  }

  static String product(ItemRow i, CatalogRow c) {
    if (i.productName() != null && !i.productName().isBlank()) {
      return i.productName();
    }
    return c == null ? "" : nz(c.productName());
  }

  static BigDecimal quantity(ItemRow i) {
    return i.quantity() == null || i.quantity().signum() <= 0 ? BigDecimal.ONE : i.quantity();
  }

  static Integer difficulty(ItemRow i, CatalogRow c) {
    if (i.difficulty() != null) {
      return i.difficulty();
    }
    return c == null ? null : c.difficulty();
  }

  static BigDecimal score(ItemRow i, CatalogRow c) {
    if (i.standardScore() != null) {
      return i.standardScore();
    }
    return c == null ? null : c.standardScore();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
