package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRole;
import vn.viettel.okrkpi.contract.tasks.model.MyTasksPage;
import vn.viettel.okrkpi.contract.tasks.model.MyTasksPageCounts;
import vn.viettel.okrkpi.contract.tasks.model.MyTasksPageKeyResultsInner;
import vn.viettel.okrkpi.contract.tasks.model.MyTasksQuickFilter;
import vn.viettel.okrkpi.contract.tasks.model.PeriodRef;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskExportRequest;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.contract.tasks.model.TaskSource;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.contract.tasks.model.TaskSummary;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.direct.TaskPermittedActions;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.mine.MyTaskService;
import vn.viettel.okrkpi.task.application.mine.MyTaskService.MineItem;
import vn.viettel.okrkpi.task.application.mine.TaskExportService;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.mine.QuickFilter;

/**
 * "Việc của tôi" (SCR-0503, SCR-0504, SCR-0002), nhận việc và xuất Excel (CORE-02-18). Danh sách
 * chỉ lọc theo người gọi (điểm chốt HD-04).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class MyTaskController {

  static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final MyTaskService mine;
  private final TaskExportService exports;
  private final TaskTransitionService transitions;
  private final TaskDelayRiskService delayRisks;
  private final TaskCallers callers;
  private final CursorCodec cursors;

  public MyTaskController(
      MyTaskService mine,
      TaskExportService exports,
      TaskTransitionService transitions,
      TaskDelayRiskService delayRisks,
      TaskCallers callers,
      CursorCodec cursors) {
    this.mine = Objects.requireNonNull(mine, "mine");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.delayRisks = Objects.requireNonNull(delayRisks, "delayRisks");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping("/mine")
  @RequiresPermission(operationId = "listMyTasks", module = "tasks", resourceType = "task")
  public MyTasksPage listMyTasks(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "filter", required = false) MyTasksQuickFilter filter,
      @RequestParam(value = "view", required = false) String view,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "overdue", required = false) Boolean overdue,
      @RequestParam(value = "role", required = false) AssigneeRole role,
      @RequestParam(value = "group", required = false) TaskGroup group,
      @RequestParam(value = "kr", required = false) UUID kr,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (view != null && !"today".equals(view)) {
      throw TaskDraftService.invalid("view", "Cách xem không hợp lệ");
    }
    MyTaskService.MineQuery query =
        new MyTaskService.MineQuery(
            period,
            filter == null ? null : QuickFilter.valueOf(filter.getValue()),
            "today".equals(view),
            TaskAssignController.statuses(status),
            overdue,
            role == null ? null : role.getValue(),
            group == null ? null : group.getValue(),
            kr,
            q);
    String scope = "tasks-mine:" + query;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    TaskActor actor = callers.current();
    MyTaskService.MinePage result;
    try {
      result =
          mine.listMine(
              TenantContext.require(),
              query,
              actor,
              page.afterKey().map(k -> k.getDate(0)).orElse(null),
              page.afterKey().map(k -> k.getUuid(1)).orElse(null),
              page.fetchSize());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    CursorPage<MineItem> paged =
        cursors.page(
            result.items(), page, i -> CursorKey.of(i.row().effectiveDue(), i.row().task().id()));
    PeriodSnapshot p = result.period();
    return new MyTasksPage(
        paged.items().stream().map(MyTaskController::summary).toList(),
        paged.nextCursor(),
        new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate()),
        new MyTasksPageCounts(
            result.counts().getOrDefault(QuickFilter.TAT_CA, 0),
            result.counts().getOrDefault(QuickFilter.CHAM, 0),
            result.counts().getOrDefault(QuickFilter.SAP_HAN, 0),
            result.counts().getOrDefault(QuickFilter.CHO_NGHIEM_THU, 0),
            result.counts().getOrDefault(QuickFilter.HOAN_THANH, 0)),
        result.keyResults().entrySet().stream()
            .map(e -> new MyTasksPageKeyResultsInner(e.getKey(), e.getValue()))
            .toList());
  }

  @PostMapping("/{id}/accept")
  @Idempotent
  @RequiresPermission(operationId = "acceptTask", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> acceptTask(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TaskTransitionService.Transitioned done;
    try {
      done = mine.acceptTask(tenant, id, expected, callers.current());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    TaskRow t = done.task();
    TaskDetail dto = TaskTransitionController.detail(t, transitions.stateLog(tenant, t));
    TaskDelayRiskController.view(delayRisks.find(tenant, t)).ifPresent(dto::delayRisk);
    return ETags.ok(dto, t.rowVersion());
  }

  @PostMapping("/export")
  @Idempotent
  @RequiresPermission(operationId = "exportTasks", module = "tasks", resourceType = "task")
  public ResponseEntity<Void> exportTasks(@RequestBody TaskExportRequest body) {
    TaskExportRequest r = body == null ? new TaskExportRequest() : body;
    Set<String> statuses = new LinkedHashSet<>();
    if (r.getStatus() != null) {
      r.getStatus().forEach(s -> statuses.add(s.getValue()));
    }
    boolean forUnit = r.getAssignee() == null;
    Boolean overdue = Boolean.TRUE.equals(r.getOverdueOnly()) ? Boolean.TRUE : null;
    TaskExportService.ExportQuery query =
        new TaskExportService.ExportQuery(
            new MyTaskService.MineQuery(
                r.getPeriod(),
                r.getFilter() == null ? null : QuickFilter.valueOf(r.getFilter().getValue()),
                false,
                statuses,
                overdue,
                r.getRole() == null ? null : r.getRole().getValue(),
                r.getGroup() == null ? null : r.getGroup().getValue(),
                r.getKeyResultId(),
                r.getQ()),
            new TaskQueryService.UnitQuery(
                r.getUnit(), r.getPeriod(), statuses, r.getKeyResultId(), overdue, false, r.getQ()),
            forUnit);
    String jobId = exports.export(TenantContext.require(), query, callers.current());
    return ResponseEntity.accepted()
        .location(URI.create(TaskAssignController.JOBS + jobId))
        .header(HttpHeaders.RETRY_AFTER, "2")
        .build();
  }

  @GetMapping("/exports/{jobId}")
  @RequiresPermission(operationId = "downloadTaskExport", module = "tasks", resourceType = "task")
  public ResponseEntity<byte[]> downloadTaskExport(@PathVariable("jobId") String jobId) {
    byte[] file =
        exports
            .file(TenantContext.require(), jobId, callers.current().personId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("nhiem-vu.xlsx").build().toString())
        .body(file);
  }

  private static TaskSummary summary(MineItem item) {
    TaskRow t = item.row().task();
    LocalDate due = item.row().effectiveDue();
    return new TaskSummary(
            t.id(),
            t.code(),
            t.title(),
            TaskStatus.fromValue(t.status().name()),
            t.overdue(),
            due,
            percent(t.progressPct()),
            AssigneeRole.fromValue(item.row().role()),
            "",
            "",
            t.periodId(),
            (int) t.rowVersion(),
            TaskGroup.fromValue(t.taskGroup()),
            TaskSource.fromValue(t.source()),
            t.plannedQuantity(),
            t.doneQuantity(),
            t.unit(),
            item.row().openBlockers(),
            item.workingDaysLeft())
        .keyResultId(t.keyResultId())
        .permittedActions(TaskPermittedActions.mine(t.status(), item.row().role()));
  }

  private static int percent(BigDecimal ratio) {
    return ratio == null ? 0 : ratio.movePointRight(2).intValue();
  }
}
