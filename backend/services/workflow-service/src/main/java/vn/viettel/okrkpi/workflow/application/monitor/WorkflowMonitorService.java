package vn.viettel.okrkpi.workflow.application.monitor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.AssigneeDirectory;
import vn.viettel.okrkpi.workflow.application.port.AssigneeSeparation;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.ExtensionLimits;
import vn.viettel.okrkpi.workflow.application.port.InstanceMonitorStore;
import vn.viettel.okrkpi.workflow.application.port.MonitorAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.MonitorEngine;
import vn.viettel.okrkpi.workflow.application.port.MonitorLedger;
import vn.viettel.okrkpi.workflow.application.port.TenantScope;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.domain.monitor.DueExtension;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceFilter;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceSummary;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitorRules;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;
import vn.viettel.okrkpi.workflow.domain.monitor.WorkflowDiagram;
import vn.viettel.okrkpi.workflow.domain.replica.EmploymentStatus;

/**
 * Giám sát tiến trình theo mẫu (BS-01 SCR-1506; QT-15): danh sách đọc bảng chiếu {@code
 * flowable.wf_task_projection}, sơ đồ với bước hiện tại, và hai thao tác có kiểm soát là chuyển
 * người xử lý và gia hạn một bước. Cả hai thao tác ghi bắt buộc lý do, cập nhật engine và bảng
 * chiếu trong cùng giao dịch, ghi sổ cái qua outbox; không có thao tác sửa biến hay nhảy bước.
 *
 * <p>Quản trị tenant (AC-02) thao tác trong tenant mình; quản trị nền tảng (AC-01) xem tổng hợp
 * theo cell và thao tác ở phạm vi nền tảng. Phạm vi do OPA quyết định theo {@code tenant_id} của
 * tài nguyên; đối tượng của tenant khác mà OPA từ chối trả 404 để không lộ sự tồn tại.
 */
@Service
public class WorkflowMonitorService {

  /** Phần {@code MODULE} của mã {@code WORKFLOW-409-VERSION-CONFLICT}. */
  static final String MODULE = "WORKFLOW";

  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String NOT_ELIGIBLE = "WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE";

  /** Tiền tố {@code tenant_id} của tài nguyên tổng hợp cell: chỉ phạm vi N của OPA khớp. */
  static final String CELL_RESOURCE_PREFIX = "cell:";

  /** Giá trị keyset thay cho hạn rỗng (bước không hạn xếp cuối). */
  static final String NO_DUE = "-";

  /** Quét lịch nghỉ thêm sau hạn đề nghị để dời khỏi ngày nghỉ dài. */
  static final int CALENDAR_SLACK_DAYS = 31;

  private final InstanceMonitorStore store;
  private final MonitorEngine engine;
  private final MonitorAccessPolicy policy;
  private final AssigneeDirectory directory;
  private final AssigneeSeparation separation;
  private final ExtensionLimits limits;
  private final WorkingCalendar calendar;
  private final MonitorLedger ledger;
  private final TenantScope tenants;
  private final CurrentApprover currentApprover;
  private final CursorCodec cursors;
  private final MonitorSettings settings;
  private final Clock clock;

  public WorkflowMonitorService(
      InstanceMonitorStore store,
      MonitorEngine engine,
      MonitorAccessPolicy policy,
      AssigneeDirectory directory,
      AssigneeSeparation separation,
      ExtensionLimits limits,
      WorkingCalendar calendar,
      MonitorLedger ledger,
      TenantScope tenants,
      CurrentApprover currentApprover,
      CursorCodec cursors,
      MonitorSettings settings,
      Clock clock) {
    this.store = store;
    this.engine = engine;
    this.policy = policy;
    this.directory = directory;
    this.separation = separation;
    this.limits = limits;
    this.calendar = calendar;
    this.ledger = ledger;
    this.tenants = tenants;
    this.currentApprover = currentApprover;
    this.cursors = cursors;
    this.settings = settings;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ danh sách

  /**
   * Một trang tiến trình đang chạy ({@code listWorkflowInstances}).
   *
   * @param templateCode lọc theo mã mẫu, có thể {@code null}
   * @param stuck lọc tiến trình kẹt, có thể {@code null}
   * @param cell mã cell: có thì xem tổng hợp cả cell (phạm vi nền tảng), {@code null} thì xem
   *     tenant của người gọi
   * @param periodId lọc theo kỳ, có thể {@code null}
   * @param unitId lọc theo đơn vị xử lý của bước đang chờ (đơn vị của nhóm ứng viên), có thể {@code
   *     null}
   * @param limit số dòng, {@code null} dùng mặc định 20
   * @param cursor con trỏ trang trước
   */
  @Transactional(readOnly = true, timeout = 5)
  public InstancePage list(
      String templateCode,
      Boolean stuck,
      String cell,
      UUID periodId,
      UUID unitId,
      Integer limit,
      String cursor) {
    Approver approver = currentApprover.current();
    Instant now = clock.instant();
    boolean cellScope = cell != null && !cell.isBlank();
    InstanceFilter filter;
    if (cellScope) {
      policy.checkView(resource("workflow_instance", null, CELL_RESOURCE_PREFIX + cell.strip()));
      filter = InstanceFilter.cell(templateCode, stuck, periodId, now).withUnit(unitId);
    } else {
      policy.checkView(resource("workflow_instance", null, approver.tenantId().toString()));
      filter =
          InstanceFilter.tenant(approver.tenantId(), templateCode, stuck, periodId, now)
              .withUnit(unitId);
    }
    PageRequest request = cursors.pageRequest(limit, cursor, filter.cursorScope());
    if (cellScope && !settings.cellCode().equalsIgnoreCase(cell.strip())) {
      // Mỗi cell có engine riêng; dữ liệu cell khác do dịch vụ của cell đó trả.
      return new InstancePage(List.of(), null);
    }
    InboxPosition after =
        request
            .afterKey()
            .map(
                k ->
                    new InboxPosition(
                        NO_DUE.equals(k.getString(0)) ? null : k.getInstant(0), k.getString(1)))
            .orElse(null);
    List<TaskProjection> rows = store.find(filter, after, request.fetchSize());
    CursorPage<TaskProjection> page = cursors.page(rows, request, WorkflowMonitorService::keyOf);
    List<InstanceSummary> items =
        page.items().stream()
            .map(p -> InstanceSummary.of(p, settings.cellCode(), now, cellScope))
            .toList();
    return new InstancePage(items, page.nextCursor());
  }

  private static CursorKey keyOf(TaskProjection row) {
    return CursorKey.of(row.dueAt() == null ? NO_DUE : row.dueAt(), row.taskId());
  }

  // ------------------------------------------------------------------ sơ đồ

  /** Sơ đồ tiến trình với bước hiện tại ({@code getWorkflowDiagram}). */
  @Transactional(readOnly = true, timeout = 5)
  public WorkflowDiagram diagram(String businessKey) {
    BusinessKey key = BusinessKey.parse(businessKey).orElseThrow(WorkflowNotFoundException::new);
    Approver approver = currentApprover.current();
    WorkflowDiagram diagram =
        engine
            .diagram(key, approver.tenantId())
            .or(() -> engine.diagram(key, null))
            .orElseThrow(WorkflowNotFoundException::new);
    authorize(
        approver,
        diagram.tenantId(),
        () ->
            policy.checkDiagram(
                resource(typeOf(key), key.id().toString(), diagram.tenantId().toString())));
    return diagram;
  }

  // ------------------------------------------------------------------ chuyển người xử lý

  /** Chuyển người xử lý của một user task ({@code reassignWorkflowTask}). */
  @Transactional(timeout = 5)
  public TaskResult reassign(ReassignCommand command) {
    String reason = requireReason(command.reason());
    Approver approver = currentApprover.current();
    MonitoredTask task =
        engine.findTask(command.taskId()).orElseThrow(WorkflowNotFoundException::new);
    authorize(approver, task.tenantId(), () -> policy.checkReassign(taskResource(task)));
    return tenants.within(
        task.tenantId(),
        () -> {
          checkVersion(command.expectedVersion(), task);
          if (command.assigneeId().equals(task.assigneeId())) {
            throw new BusinessException(
                VALIDATION,
                "Người nhận mới đang là người xử lý bước này.",
                List.of(
                    ProblemFieldError.of(
                        "assigneeId", "Chọn người khác người đang xử lý.", "different")));
          }
          CandidateGroup candidate = task.primaryCandidate();
          LocalDate today = LocalDate.ofInstant(clock.instant(), settings.zone());
          if (!directory.activeIn(
              task.tenantId(),
              command.assigneeId(),
              candidate == null ? null : candidate.unitId(),
              today)) {
            throw new BusinessException(
                NOT_ELIGIBLE,
                "Người nhận không có phân công còn hiệu lực ở đơn vị xử lý bước này.",
                List.of(
                    ProblemFieldError.of(
                        "assigneeId", "Người nhận không thuộc đơn vị xử lý bước.", "eligible")));
          }
          // Người đã nghỉ hưu, thôi việc theo org_replica.person_v (EVT-16) không nhận việc; chưa
          // có dòng nhân sự thì chỉ dựa vào phân công (suy luận, wiki trang 14 mục DV-06-10).
          if (directory
              .employmentStatus(task.tenantId(), command.assigneeId())
              .filter(s -> !EmploymentStatus.allowsAssignment(s))
              .isPresent()) {
            throw new BusinessException(
                NOT_ELIGIBLE,
                "Người nhận đã nghỉ hưu hoặc thôi việc.",
                List.of(
                    ProblemFieldError.of(
                        "assigneeId", "Người nhận không còn công tác.", "employed")));
          }
          separation.check(task, command.assigneeId());
          int revision = engine.reassign(task, command.assigneeId(), approver.actorId(), reason);
          store.updateAssignee(task.tenantId(), task.taskId(), command.assigneeId(), revision);
          ledger.reassigned(task, command.assigneeId(), approver.actorId(), revision);
          return result(task);
        });
  }

  // ------------------------------------------------------------------ gia hạn

  /** Gia hạn một bước ({@code extendWorkflowTask}). */
  @Transactional(timeout = 5)
  public TaskResult extend(ExtendCommand command) {
    String reason = requireReason(command.reason());
    Approver approver = currentApprover.current();
    MonitoredTask task =
        engine.findTask(command.taskId()).orElseThrow(WorkflowNotFoundException::new);
    authorize(approver, task.tenantId(), () -> policy.checkExtend(taskResource(task)));
    return tenants.within(
        task.tenantId(),
        () -> {
          checkVersion(command.expectedVersion(), task);
          Instant now = clock.instant();
          int limit =
              limits
                  .maxWorkingDays(task.tenantId(), task.templateCode())
                  .orElse(settings.defaultMaxExtensionDays());
          Instant base = task.dueAt() == null || task.dueAt().isBefore(now) ? now : task.dueAt();
          LocalDate from = LocalDate.ofInstant(base, settings.zone());
          LocalDate to =
              LocalDate.ofInstant(
                      command.dueAt().isAfter(base) ? command.dueAt() : base, settings.zone())
                  .plusDays(CALENDAR_SLACK_DAYS);
          Map<LocalDate, Boolean> overrides = calendar.overrides(task.tenantId(), from, to);
          DueExtension.Result extension =
              DueExtension.evaluate(
                  task.dueAt(), command.dueAt(), now, limit, overrides, settings.zone());
          extension.violation().ifPresent(v -> rejectExtension(v, extension));
          int revision = engine.extend(task, extension.newDue(), approver.actorId(), reason);
          store.updateDue(task.tenantId(), task.taskId(), extension.newDue(), revision);
          ledger.extended(task, extension.newDue(), approver.actorId(), revision);
          return result(task);
        });
  }

  private static void rejectExtension(DueExtension.Violation v, DueExtension.Result r) {
    String message =
        switch (v) {
          case NOT_IN_FUTURE -> "Hạn mới phải sau thời điểm hiện tại.";
          case NOT_LATER -> "Hạn mới phải sau hạn hiện tại của bước.";
          case OVER_LIMIT ->
              "Gia hạn tối đa "
                  + r.limit()
                  + " ngày làm việc theo tham số của mẫu luồng; đề nghị gia thêm "
                  + r.addedWorkingDays()
                  + " ngày làm việc.";
        };
    String rule =
        switch (v) {
          case NOT_IN_FUTURE, NOT_LATER -> "after";
          case OVER_LIMIT -> "maxWorkingDays";
        };
    throw new BusinessException(
        VALIDATION, message, List.of(ProblemFieldError.of("dueAt", message, rule)));
  }

  // ------------------------------------------------------------------ dùng chung

  private TaskResult result(MonitoredTask task) {
    TaskProjection projection =
        store
            .find(task.tenantId(), task.taskId())
            .orElseThrow(() -> new IllegalStateException("Bảng chiếu thiếu user task đang mở"));
    return new TaskResult(projection, clock.instant());
  }

  /** Hỏi OPA; khi tài nguyên thuộc tenant khác mà bị từ chối thì trả 404 để không lộ sự tồn tại. */
  private static void authorize(Approver approver, UUID resourceTenant, Runnable check) {
    if (approver.tenantId().equals(resourceTenant)) {
      check.run();
      return;
    }
    try {
      check.run();
    } catch (BusinessException e) {
      if (e.errorCode().code().startsWith("COMMON-403")) {
        throw new WorkflowNotFoundException();
      }
      throw e;
    }
  }

  private static void checkVersion(Long expected, MonitoredTask task) {
    if (expected != null && expected != task.revision()) {
      throw VersionConflictException.of(MODULE, expected, task.revision());
    }
  }

  private static String requireReason(String raw) {
    return MonitorRules.reason(raw)
        .orElseThrow(
            () ->
                new BusinessException(
                    VALIDATION,
                    "Vui lòng nhập lý do, tối đa 1.000 ký tự.",
                    List.of(
                        ProblemFieldError.of(
                            "reason", "Vui lòng nhập lý do, tối đa 1.000 ký tự.", "required"))));
  }

  private static AuthzResource taskResource(MonitoredTask task) {
    CandidateGroup candidate = task.primaryCandidate();
    return AuthzResource.builder()
        .type(typeOf(task.businessKey()))
        .id(task.businessKey().id().toString())
        .tenantId(task.tenantId().toString())
        .ownerUnitId(candidate == null ? null : candidate.unitId().toString())
        .classification("C1")
        .build();
  }

  private static AuthzResource resource(String type, String id, String tenantId) {
    return AuthzResource.builder()
        .type(type)
        .id(id)
        .tenantId(tenantId)
        .classification("C1")
        .build();
  }

  private static String typeOf(BusinessKey key) {
    return key.type().toLowerCase(Locale.ROOT);
  }

  /**
   * Trang giám sát.
   *
   * @param items các dòng
   * @param nextCursor con trỏ trang sau, {@code null} khi hết
   */
  public record InstancePage(List<InstanceSummary> items, String nextCursor) {

    public InstancePage {
      items = List.copyOf(items);
    }
  }

  /**
   * Yêu cầu chuyển người xử lý.
   *
   * @param taskId user task
   * @param expectedVersion phiên bản trong {@code If-Match}, {@code null} khi gửi {@code *}
   * @param assigneeId người nhận mới
   * @param reason lý do
   */
  public record ReassignCommand(
      String taskId, Long expectedVersion, UUID assigneeId, String reason) {

    public ReassignCommand {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(assigneeId, "assigneeId");
    }
  }

  /**
   * Yêu cầu gia hạn.
   *
   * @param taskId user task
   * @param expectedVersion phiên bản trong {@code If-Match}, {@code null} khi gửi {@code *}
   * @param dueAt hạn mới đề nghị
   * @param reason lý do
   */
  public record ExtendCommand(String taskId, Long expectedVersion, Instant dueAt, String reason) {

    public ExtendCommand {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(dueAt, "dueAt");
    }
  }

  /**
   * Kết quả thao tác: dòng chiếu sau khi ghi ({@code WorkflowInboxItem}).
   *
   * @param projection dòng chiếu
   * @param evaluatedAt thời điểm tính cờ quá hạn
   */
  public record TaskResult(TaskProjection projection, Instant evaluatedAt) {}
}
