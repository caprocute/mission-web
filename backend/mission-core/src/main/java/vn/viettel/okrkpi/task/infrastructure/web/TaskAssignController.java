package vn.viettel.okrkpi.task.infrastructure.web;

import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.tasks.model.AssignCheckItem;
import vn.viettel.okrkpi.contract.tasks.model.AssignCheckResult;
import vn.viettel.okrkpi.contract.tasks.model.AssignRequest;
import vn.viettel.okrkpi.contract.tasks.model.AssignResult;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRef;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRole;
import vn.viettel.okrkpi.contract.tasks.model.BulkAssignRequest;
import vn.viettel.okrkpi.contract.tasks.model.ListTaskAssignees200Response;
import vn.viettel.okrkpi.contract.tasks.model.PeriodRef;
import vn.viettel.okrkpi.contract.tasks.model.PersonWorkload;
import vn.viettel.okrkpi.contract.tasks.model.PersonWorkloadWeeksInner;
import vn.viettel.okrkpi.contract.tasks.model.TaskAssignee;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetailAllOfSourceDoc;
import vn.viettel.okrkpi.contract.tasks.model.TaskDraft;
import vn.viettel.okrkpi.contract.tasks.model.TaskDraftTemplate;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.contract.tasks.model.TaskMilestone;
import vn.viettel.okrkpi.contract.tasks.model.TaskNodeKind;
import vn.viettel.okrkpi.contract.tasks.model.TaskPriority;
import vn.viettel.okrkpi.contract.tasks.model.TaskSource;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.contract.tasks.model.TaskSummary;
import vn.viettel.okrkpi.contract.tasks.model.TaskSummaryPage;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitTaskRow;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService;
import vn.viettel.okrkpi.task.application.direct.TaskDirectionService;
import vn.viettel.okrkpi.task.application.direct.TaskPermittedActions;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.mine.LeadWorkloads;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules.CheckItem;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.mine.Workload;

/**
 * Soạn và giao nhiệm vụ của QT-05 (SCR-0501, CORE-02-14), chi tiết nhiệm vụ (SCR-0601) và việc của
 * đơn vị (SCR-0201, SCR-1601). Tên người, tên đơn vị chưa có nguồn bản chiếu nên trả chuỗi rỗng để
 * giao diện tự tra (suy luận, như {@link TaskTransitionController}).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskAssignController {

  static final String JOBS = "/api/v1/jobs/";
  private static final String MASKED = "Nội dung hạn chế";

  private final TaskDraftService drafts;
  private final TaskAssignService assign;
  private final TaskQueryService queries;
  private final TaskTransitionService transitions;
  private final TaskDelayRiskService delayRisks;
  private final TaskCallers callers;
  private final CursorCodec cursors;
  private final TaskBlockerService blockers;
  private final TaskChangeRequestService changes;

  private final TaskDirectionService directions;

  public TaskAssignController(
      TaskDraftService drafts,
      TaskAssignService assign,
      TaskQueryService queries,
      TaskTransitionService transitions,
      TaskDelayRiskService delayRisks,
      TaskCallers callers,
      CursorCodec cursors,
      TaskBlockerService blockers,
      TaskChangeRequestService changes,
      TaskDirectionService directions) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.assign = Objects.requireNonNull(assign, "assign");
    this.queries = Objects.requireNonNull(queries, "queries");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.delayRisks = Objects.requireNonNull(delayRisks, "delayRisks");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.directions = Objects.requireNonNull(directions, "directions");
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(operationId = "createTaskDraft", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDraft> createTaskDraft(@RequestBody TaskDraft body) {
    DraftRow row = drafts.createDraft(TenantContext.require(), spec(body), callers.current());
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + row.id()))
        .eTag(ETags.of(row.rowVersion()))
        .body(draft(row));
  }

  @GetMapping("/drafts/new")
  @RequiresPermission(operationId = "getNewTaskDraft", module = "tasks", resourceType = "task")
  public TaskDraftTemplate getNewTaskDraft() {
    return template(drafts.newDraft(TenantContext.require(), callers.current()));
  }

  @GetMapping("/{id}/draft")
  @RequiresPermission(operationId = "getTaskDraft", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDraftTemplate> getTaskDraft(@PathVariable("id") UUID id) {
    TaskDraftService.Template t = found(() -> drafts.getDraft(TenantContext.require(), id));
    return ETags.ok(template(t), t.draft().rowVersion());
  }

  @PutMapping("/{id}")
  @Idempotent
  @RequiresPermission(operationId = "saveTaskDraft", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDraft> saveTaskDraft(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody TaskDraft body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TenantId tenant = TenantContext.require();
    TaskDraftSpec spec = spec(body);
    DraftRow row = found(() -> drafts.saveDraft(tenant, id, expected, spec, callers.current()));
    return ETags.ok(draft(row), row.rowVersion());
  }

  @DeleteMapping("/{id}")
  @Idempotent
  @RequiresPermission(operationId = "deleteTaskDraft", module = "tasks", resourceType = "task")
  public ResponseEntity<Void> deleteTaskDraft(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    found(
        () -> {
          drafts.deleteDraft(tenant, id, expected, actor);
          return Boolean.TRUE;
        });
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/assign-check")
  @RequiresPermission(operationId = "checkTaskAssignment", module = "tasks", resourceType = "task")
  public AssignCheckResult checkTaskAssignment(
      @RequestBody TaskDraft body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    TaskAssignService.CheckResult r =
        assign.check(TenantContext.require(), spec(body), callers.current(), authorization);
    AssignCheckResult dto =
        new AssignCheckResult(
                r.items().stream().map(TaskAssignController::checkItem).toList(),
                r.similar().stream().map(TaskSimilarController::dto).toList())
            .workingDays(r.workingDays());
    LeadWorkloads.Result w = r.workload();
    if (w != null) {
      dto.setWorkload(
          new PersonWorkload(
              w.personId(),
              "",
              w.leadCount(),
              w.coCount(),
              w.threshold(),
              w.weeks().entrySet().stream()
                  .map(e -> new PersonWorkloadWeeksInner(Workload.label(e.getKey()), e.getValue()))
                  .toList()));
    }
    return dto;
  }

  @PostMapping("/{id}/assign")
  @Idempotent
  @RequiresPermission(operationId = "assignTask", module = "tasks", resourceType = "task")
  public ResponseEntity<AssignResult> assignTask(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) AssignRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TenantId tenant = TenantContext.require();
    String reason = body == null ? null : body.getSimilarReason();
    TaskAssignService.Assigned done =
        found(
            () ->
                assign.assignTask(tenant, id, expected, reason, authorization, callers.current()));
    TaskRow t = done.task();
    return ETags.ok(
        new AssignResult(t.id(), t.code(), TaskStatus.fromValue(t.status().name()), ""),
        t.rowVersion());
  }

  @PostMapping("/bulk-assign")
  @Idempotent
  @RequiresPermission(operationId = "bulkAssignTasks", module = "tasks", resourceType = "task")
  public ResponseEntity<Void> bulkAssignTasks(@RequestBody BulkAssignRequest body) {
    if (body == null || body.getDraft() == null) {
      throw TaskDraftService.invalid("draft", "Thiếu nội dung nhiệm vụ");
    }
    String jobId =
        assign.bulkAssign(
            TenantContext.require(), spec(body.getDraft()), body.getUnitIds(), callers.current());
    return ResponseEntity.accepted()
        .location(URI.create(JOBS + jobId))
        .header(HttpHeaders.RETRY_AFTER, "2")
        .build();
  }

  @GetMapping("/{id}")
  @RequiresPermission(operationId = "getTask", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> getTask(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    TaskQueryService.TaskView view = found(() -> queries.getTask(tenant, id, callers.current()));
    TaskRow t = view.task();
    TaskDetail dto = TaskTransitionController.detail(t, transitions.stateLog(tenant, t));
    if (view.callerRole() != null) {
      dto.setRole(AssigneeRole.fromValue(view.callerRole()));
    }
    if (view.masked()) {
      dto.setTitle(MASKED);
      dto.setDescription("");
    } else {
      dto.setBlockers(blockers.listOf(tenant, t).stream().map(TaskBlockerController::dto).toList());
      TaskChangeRequestController.decorate(dto, changes, tenant, t);
    }
    TaskDelayRiskController.view(delayRisks.find(tenant, t)).ifPresent(dto::delayRisk);
    Set<String> actions =
        new LinkedHashSet<>(TaskPermittedActions.detail(t.status(), view.callerRole()));
    actions.addAll(directions.directionActionsOn(tenant, t, callers.current()));
    dto.setPermittedActions(actions);
    return ETags.ok(dto, t.rowVersion());
  }

  @GetMapping("/{id}/assignees")
  @RequiresPermission(operationId = "listTaskAssignees", module = "tasks", resourceType = "task")
  public ListTaskAssignees200Response listTaskAssignees(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    List<AssigneeRow> rows = found(() -> queries.listTaskAssignees(tenant, id, callers.current()));
    return new ListTaskAssignees200Response(
        rows.stream()
            .map(
                a ->
                    new TaskAssignee(
                        TaskAssignee.KindEnum.fromValue(a.kind()),
                        a.id(),
                        "",
                        AssigneeRole.fromValue(a.role())))
            .toList());
  }

  @GetMapping
  @RequiresPermission(operationId = "listUnitTasks", module = "tasks", resourceType = "task")
  public TaskSummaryPage listUnitTasks(
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "kr", required = false) UUID kr,
      @RequestParam(value = "overdue", required = false) Boolean overdue,
      @RequestParam(value = "source", required = false) TaskSource source,
      @RequestParam(value = "pendingChangeRequest", required = false) Boolean pendingChangeRequest,
      @RequestParam(value = "includeSubunits", required = false) Boolean includeSubunits,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    Set<String> statuses = statuses(status);
    boolean subunits = Boolean.TRUE.equals(includeSubunits);
    String scope =
        "tasks:"
            + unit
            + ":"
            + period
            + ":"
            + statuses
            + ":"
            + kr
            + ":"
            + overdue
            + ":"
            + subunits
            + ":"
            + q
            + ":"
            + source
            + ":"
            + pendingChangeRequest;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    TaskActor actor = callers.current();
    List<UnitTaskRow> rows =
        queries.listUnitTasks(
            TenantContext.require(),
            new TaskQueryService.UnitQuery(
                unit,
                period,
                statuses,
                kr,
                overdue,
                subunits,
                q,
                source == null ? null : source.getValue(),
                pendingChangeRequest),
            actor,
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<UnitTaskRow> result = cursors.page(rows, page, r -> CursorKey.of(r.task().id()));
    TaskDirectionService.DirectionActions allowed =
        directions.directionActions(TenantContext.require(), actor);
    return new TaskSummaryPage(
        result.items().stream()
            .map(
                r ->
                    summary(r, actor)
                        .permittedActions(
                            allowed.on(r.task().assignerId(), r.orgUnitId(), r.task().status())))
            .toList(),
        result.nextCursor());
  }

  static Set<String> statuses(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String s : csv.split(",")) {
      String v = s.trim();
      if (v.isEmpty()) {
        continue;
      }
      if (Arrays.stream(TaskStatus.values()).noneMatch(x -> x.getValue().equals(v))) {
        throw TaskDraftService.invalid("status", "Trạng thái lọc không hợp lệ");
      }
      out.add(v);
    }
    return out;
  }

  private static TaskSummary summary(UnitTaskRow r, TaskActor actor) {
    TaskRow t = r.task();
    boolean visible =
        !r.restricted()
            || actor.personId().equals(t.assignerId())
            || actor.personId().equals(r.leadAssigneeId());
    LocalDate due = t.extendedDueDate() != null ? t.extendedDueDate() : t.dueDate();
    return new TaskSummary(
            t.id(),
            t.code(),
            visible ? t.title() : MASKED,
            TaskStatus.fromValue(t.status().name()),
            t.overdue(),
            due,
            t.progressPct() == null ? 0 : t.progressPct().movePointRight(2).intValue(),
            AssigneeRole.CHU_TRI,
            "",
            "",
            t.periodId(),
            (int) t.rowVersion(),
            TaskGroup.fromValue(t.taskGroup()),
            TaskSource.fromValue(t.source()),
            t.plannedQuantity(),
            t.doneQuantity(),
            t.unit(),
            0,
            0)
        .keyResultId(t.keyResultId());
  }

  static TaskDraftSpec spec(TaskDraft d) {
    if (d == null) {
      throw TaskDraftService.invalid("title", "Thiếu nội dung nhiệm vụ");
    }
    TaskDetailAllOfSourceDoc doc = d.getSourceDoc();
    return new TaskDraftSpec(
        d.getPeriodId(),
        d.getSource() == null ? null : d.getSource().getValue(),
        d.getPlanItemId(),
        d.getCatalogItemId(),
        d.getTitle(),
        d.getTaskGroup() == null ? null : d.getTaskGroup().getValue(),
        d.getKeyResultId(),
        d.getPriority() == null ? null : d.getPriority().getValue(),
        d.getLeadPersonId(),
        d.getCoAssignees() == null
            ? List.of()
            : d.getCoAssignees().stream()
                .map(
                    a ->
                        new TaskDraftSpec.Assignee(
                            a.getKind() == null ? null : a.getKind().getValue(), a.getId()))
                .toList(),
        d.getDueDate(),
        d.getAcceptorId(),
        d.getMilestones() == null
            ? List.of()
            : d.getMilestones().stream()
                .map(m -> new TaskDraftSpec.Milestone(m.getDate(), m.getContent()))
                .toList(),
        d.getProductName(),
        d.getQuantity(),
        d.getUnit(),
        d.getEvidenceRequired(),
        d.getQualityCriteria(),
        doc == null ? null : doc.getNumber(),
        doc == null ? null : doc.getTitle(),
        d.getBasis(),
        new TaskDraftSpec.Node(
            d.getNodeKind() == null ? null : d.getNodeKind().getValue(),
            d.getParentTaskId(),
            d.getComponentScore()));
  }

  static TaskDraft draft(DraftRow row) {
    TaskDraftSpec s = row.spec();
    TaskDraft dto =
        new TaskDraft(
                TaskStatus.fromValue(row.status()),
                row.periodId(),
                "",
                row.assignerId(),
                "",
                TaskSource.fromValue(s.source()),
                Objects.requireNonNullElse(s.title(), ""),
                TaskGroup.fromValue(s.taskGroup()),
                TaskPriority.fromValue(s.priority()),
                s.coAssignees().stream()
                    .map(a -> new AssigneeRef(AssigneeRef.KindEnum.fromValue(a.kind()), a.id(), ""))
                    .collect(Collectors.toList()),
                s.milestones().stream()
                    .map(m -> new TaskMilestone(m.date(), m.content()))
                    .collect(Collectors.toList()),
                Objects.requireNonNullElse(s.productName(), ""),
                s.quantity() == null ? java.math.BigDecimal.ONE : s.quantity(),
                Objects.requireNonNullElse(s.unit(), ""),
                Objects.requireNonNullElse(s.evidenceRequired(), ""),
                Objects.requireNonNullElse(s.qualityCriteria(), ""))
            .id(row.id())
            .code(row.code())
            .planItemId(s.planItemId())
            .catalogItemId(s.catalogItemId())
            .keyResultId(s.keyResultId())
            .leadPersonId(s.leadPersonId())
            .dueDate(s.dueDate())
            .acceptorId(s.acceptorId())
            .basis(s.basis())
            .nodeKind(TaskNodeKind.fromValue(s.node().kind()))
            .parentTaskId(s.node().parentTaskId())
            .componentScore(s.node().componentScore());
    if (row.id() != null) {
      dto.setRowVersion((int) row.rowVersion());
    }
    if (s.sourceDocNumber() != null || s.sourceDocTitle() != null) {
      dto.setSourceDoc(
          new TaskDetailAllOfSourceDoc(
              Objects.requireNonNullElse(s.sourceDocNumber(), ""),
              Objects.requireNonNullElse(s.sourceDocTitle(), "")));
    }
    return dto;
  }

  private static TaskDraftTemplate template(TaskDraftService.Template t) {
    PeriodSnapshot p = t.period();
    return new TaskDraftTemplate(
        new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate()), draft(t.draft()));
  }

  private static AssignCheckItem checkItem(CheckItem c) {
    return new AssignCheckItem(
        c.rule(), AssignCheckItem.LevelEnum.fromValue(c.level().name()), c.passed(), c.message());
  }

  private static <T> T found(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
