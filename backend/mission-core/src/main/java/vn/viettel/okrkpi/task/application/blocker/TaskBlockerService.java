package vn.viettel.okrkpi.task.application.blocker;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.blocker.port.BlockerNotices;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers.BlockerRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.blocker.BlockerRules;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Báo và xử lý điểm nghẽn của QT-06 bước 3, 4 (SCR-0601, SCR-0504). Người thực hiện (chủ trì, phối
 * hợp) báo khi nhiệm vụ đang mở; người giao hoặc người đang được giao xử lý thì giao người xử lý,
 * đặt hạn, ghi kết quả, chuyển cấp trên hoặc đóng. Mỗi bước ghi EVT-21 vào outbox cùng giao dịch.
 */
public class TaskBlockerService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final Set<String> TASK_OPEN = Set.of("DA_GIAO", "DANG_THUC_HIEN");

  static final String T_REPORTED = "DIEM_NGHEN_MOI";
  static final String T_HANDLER = "DIEM_NGHEN_GIAO_XU_LY";
  static final String T_ESCALATED = "DIEM_NGHEN_CHUYEN_CAP_TREN";
  static final String T_CLOSED = "DIEM_NGHEN_DA_XU_LY";

  private final TaskBlockers blockers;
  private final TaskLifecycleRepository tasks;
  private final TaskDraftRepository assignees;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final BlockerNotices notices;
  private final Clock clock;

  public TaskBlockerService(
      TaskBlockers blockers,
      TaskLifecycleRepository tasks,
      TaskDraftRepository assignees,
      PeriodApi periods,
      OrgDirectoryApi org,
      BlockerNotices notices,
      Clock clock) {
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.assignees = Objects.requireNonNull(assignees, "assignees");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = Objects.requireNonNull(org, "org");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Nội dung người thực hiện báo. */
  public record Report(
      String content,
      String causeCategory,
      String causeClaimed,
      String impactLevel,
      LocalDate resolveBy) {}

  /**
   * JSON Merge Patch của {@code BlockerPatch}: cờ {@code has*} cho biết trường có mặt (kể cả {@code
   * null} để xoá).
   */
  public record Patch(
      boolean hasHandler,
      UUID handler,
      boolean hasResolveBy,
      LocalDate resolveBy,
      String status,
      String note) {}

  /** Điểm nghẽn kèm nhiệm vụ để controller dựng phản hồi. */
  public record Result(TaskRow task, BlockerRow blocker) {}

  @Transactional(TX)
  public Result reportBlocker(TenantId tenant, UUID taskId, Report r, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (!isAssignee(tenant, task, actor.personId())) {
      throw new BusinessException(
          FORBIDDEN, "Chỉ người được giao nhiệm vụ mới báo được điểm nghẽn");
    }
    if (!TASK_OPEN.contains(task.status().name())) {
      throw new BusinessException(
          INVALID_STATE, "Nhiệm vụ không còn đang thực hiện nên không báo được điểm nghẽn");
    }
    writable(tenant, task);
    validate(r, task);
    int seq = blockers.nextSeq(tenant, task.periodBucket(), taskId);
    UUID id = UUID.randomUUID();
    blockers.insert(
        tenant,
        new TaskBlockers.NewBlocker(
            id,
            BlockerRules.code(task.code(), seq),
            taskId,
            task.periodId(),
            task.periodBucket(),
            r.content().strip(),
            r.causeCategory(),
            r.causeClaimed(),
            r.impactLevel(),
            r.resolveBy(),
            actor.personId()),
        clock.instant());
    BlockerRow row =
        blockers
            .find(tenant, task.periodBucket(), taskId, id)
            .orElseThrow(NoSuchElementException::new);
    notify(tenant, task, row, T_REPORTED, List.of(task.assignerId()), actor);
    return new Result(task, row);
  }

  @Transactional(TX)
  public Result handleBlocker(
      TenantId tenant, UUID taskId, UUID blockerId, long expected, Patch p, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    BlockerRow b =
        blockers
            .find(tenant, task.periodBucket(), taskId, blockerId)
            .orElseThrow(NoSuchElementException::new);
    requireHandler(task, b, actor);
    if (b.rowVersion() != expected) {
      throw new VersionConflictException("TASK", expected, b.rowVersion());
    }
    writable(tenant, task);
    UUID handler = p.hasHandler() ? p.handler() : b.handlerId();
    LocalDate resolveBy = p.hasResolveBy() ? p.resolveBy() : b.resolveBy();
    if (p.status() != null && !in(BlockerRules.STATUSES, p.status())) {
      throw invalid("status", "Trạng thái điểm nghẽn không hợp lệ");
    }
    if (p.note() != null && p.note().length() > 2000) {
      throw invalid("resolutionNote", "Kết quả xử lý tối đa 2.000 ký tự");
    }
    String target =
        p.status() != null
            ? p.status()
            : (p.hasHandler() ? BlockerRules.afterHandler(b.status(), handler) : b.status());
    BlockerRules.Violation v = BlockerRules.check(b.status(), target, handler, p.note());
    if (v == BlockerRules.Violation.INVALID_TRANSITION) {
      throw new BusinessException(
          INVALID_STATE, "Điểm nghẽn không chuyển được sang trạng thái này");
    }
    if (v == BlockerRules.Violation.HANDLER_REQUIRED) {
      throw invalid("handlerPersonId", "Chưa chọn người xử lý điểm nghẽn");
    }
    if (v == BlockerRules.Violation.NOTE_REQUIRED) {
      throw invalid("resolutionNote", "Vui lòng ghi kết quả hoặc lý do đóng điểm nghẽn");
    }
    boolean resolved = "DA_GIAI_QUYET".equals(target) && !"DA_GIAI_QUYET".equals(b.status());
    String resolution = p.note() != null && !p.note().isBlank() ? p.note().strip() : b.resolution();
    BlockerRow row =
        apply(
            tenant,
            task,
            b,
            expected,
            new TaskBlockers.Change(
                handler,
                resolveBy,
                target,
                resolution,
                resolved ? clock.instant() : null,
                b.escalatedTo(),
                actor.personId()));
    if (handler != null && !handler.equals(b.handlerId())) {
      notify(tenant, task, row, T_HANDLER, List.of(handler), actor);
    }
    if (!target.equals(b.status()) && ("DA_GIAI_QUYET".equals(target) || "DONG".equals(target))) {
      notify(tenant, task, row, T_CLOSED, List.of(b.createdBy()), actor);
    }
    return new Result(task, row);
  }

  /**
   * Chuyển cấp trên (QT-06 bước 5): người nhận là người được chọn hoặc những người đứng đầu đơn vị
   * được chọn; người nhận đầu tiên thành người xử lý, điểm nghẽn sang {@code DANG_XU_LY}. Hợp đồng
   * ghi trạng thái {@code CHUYEN_CAP_TREN} nhưng {@code BlockerStatus} và CHECK của TL-03 chưa có
   * giá trị này, nên việc chuyển cấp trên ghi ở {@code escalated_to_person_id} (suy luận).
   */
  @Transactional(TX)
  public Result escalateBlocker(
      TenantId tenant,
      UUID taskId,
      UUID blockerId,
      long expected,
      UUID targetPerson,
      UUID targetUnit,
      String note,
      TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    BlockerRow b =
        blockers
            .find(tenant, task.periodBucket(), taskId, blockerId)
            .orElseThrow(NoSuchElementException::new);
    requireHandler(task, b, actor);
    if (b.rowVersion() != expected) {
      throw new VersionConflictException("TASK", expected, b.rowVersion());
    }
    if (!BlockerRules.OPEN.contains(b.status())) {
      throw new BusinessException(
          INVALID_STATE, "Điểm nghẽn đã xử lý xong, không chuyển cấp trên được");
    }
    writable(tenant, task);
    if (note == null || note.strip().length() < 5 || note.length() > 2000) {
      throw invalid("note", "Ghi chú chuyển cấp trên từ 5 đến 2.000 ký tự");
    }
    Set<UUID> recipients = new LinkedHashSet<>();
    if (targetPerson != null) {
      recipients.add(targetPerson);
    }
    if (targetUnit != null) {
      recipients.addAll(org.headsOf(tenant, targetUnit, LocalDate.now(clock)));
    }
    if (recipients.isEmpty()) {
      throw invalid("targetPersonId", "Chưa chọn người hoặc đơn vị tiếp nhận");
    }
    UUID to = recipients.iterator().next();
    BlockerRow row =
        apply(
            tenant,
            task,
            b,
            expected,
            new TaskBlockers.Change(
                to, b.resolveBy(), "DANG_XU_LY", b.resolution(), null, to, actor.personId()));
    notify(
        tenant,
        task,
        row,
        T_ESCALATED,
        new ArrayList<>(recipients),
        actor,
        Map.of("ghiChu", note.strip()));
    return new Result(task, row);
  }

  /** Điểm nghẽn của nhiệm vụ cho phản hồi chi tiết. */
  @Transactional(value = TX, readOnly = true)
  public List<BlockerRow> listOf(TenantId tenant, TaskRow task) {
    return blockers.listOf(tenant, task.periodBucket(), task.id());
  }

  private BlockerRow apply(
      TenantId tenant, TaskRow task, BlockerRow b, long expected, TaskBlockers.Change c) {
    if (!blockers.change(tenant, task.periodBucket(), b.id(), expected, c, clock.instant())) {
      throw new VersionConflictException("TASK", expected, null);
    }
    return blockers
        .find(tenant, task.periodBucket(), task.id(), b.id())
        .orElseThrow(NoSuchElementException::new);
  }

  private boolean isAssignee(TenantId tenant, TaskRow task, UUID person) {
    return assignees.assignees(tenant, task.periodBucket(), task.id()).stream()
        .anyMatch(a -> "PERSON".equals(a.kind()) && person.equals(a.id()) && role(a));
  }

  private static boolean role(AssigneeRow a) {
    return "CHU_TRI".equals(a.role()) || "PHOI_HOP".equals(a.role());
  }

  private static void requireHandler(TaskRow task, BlockerRow b, TaskActor actor) {
    UUID me = actor.personId();
    if (!me.equals(task.assignerId()) && !me.equals(b.handlerId())) {
      throw new BusinessException(
          FORBIDDEN, "Chỉ người giao việc hoặc người được giao xử lý mới xử lý được điểm nghẽn");
    }
  }

  private void writable(TenantId tenant, TaskRow task) {
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không ghi được điểm nghẽn");
    }
  }

  private void notify(
      TenantId tenant,
      TaskRow task,
      BlockerRow b,
      String template,
      List<UUID> to,
      TaskActor actor) {
    notify(tenant, task, b, template, to, actor, Map.of());
  }

  private void notify(
      TenantId tenant,
      TaskRow task,
      BlockerRow b,
      String template,
      List<UUID> to,
      TaskActor actor,
      Map<String, String> extra) {
    List<UUID> recipients =
        to.stream()
            .filter(Objects::nonNull)
            .filter(id -> !id.equals(actor.personId()))
            .distinct()
            .toList();
    if (recipients.isEmpty()) {
      return;
    }
    Map<String, String> params = new java.util.LinkedHashMap<>();
    params.put("maNhiemVu", task.code());
    params.put("tenNhiemVu", task.title());
    params.put("maDiemNghen", b.code());
    if (b.resolveBy() != null) {
      params.put("hanXuLy", b.resolveBy().toString());
    }
    params.putAll(extra);
    notices.notice(
        new BlockerNotices.Notice(
            tenant,
            task.id(),
            b.id(),
            b.rowVersion(),
            template,
            recipients,
            params,
            actor.personId()));
  }

  private static void validate(Report r, TaskRow task) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (r == null || r.content() == null || r.content().isBlank() || r.content().length() > 2000) {
      errors.add(
          new ProblemFieldError(
              "content", "Nội dung điểm nghẽn bắt buộc, tối đa 2.000 ký tự", null));
    }
    if (r == null || !in(BlockerRules.CAUSES, r.causeCategory())) {
      errors.add(new ProblemFieldError("causeCategory", "Nhóm nguyên nhân không hợp lệ", null));
    }
    if (r == null || !in(BlockerRules.CLAIMS, r.causeClaimed())) {
      errors.add(
          new ProblemFieldError(
              "causeClaimed", "Chưa chọn nguyên nhân khách quan hay chủ quan", null));
    }
    if (r == null || !in(BlockerRules.IMPACTS, r.impactLevel())) {
      errors.add(new ProblemFieldError("impactLevel", "Mức ảnh hưởng không hợp lệ", null));
    }
    if (r != null
        && r.resolveBy() != null
        && task.dueDate() != null
        && r.resolveBy().isAfter(task.dueDate().plusYears(1))) {
      errors.add(new ProblemFieldError("resolveBy", "Hạn xử lý không hợp lệ", null));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Thông tin điểm nghẽn chưa hợp lệ", errors);
    }
  }

  private static boolean in(Set<String> values, String v) {
    return v != null && values.contains(v);
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }
}
