package vn.viettel.okrkpi.task.application.change;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers;
import vn.viettel.okrkpi.task.application.change.port.ChangeApprovalStarter;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.change.ChangeRules;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Gửi, xem trước và rút đề nghị gia hạn, điều chỉnh (QT-06 bước 5, SCR-0602). Người chủ trì gửi khi
 * nhiệm vụ còn mở; đề nghị vào thẳng {@code CHO_DUYET} vì hợp đồng không có thao tác lưu nháp,
 * người duyệt mặc định là người giao, gia hạn vượt ngưỡng BR-06-06 được đánh dấu để CORE-02-22
 * chuyển luồng duyệt cấp trên. Hệ số C chỉ để xem trước; điểm chính thức do scoring-service tính.
 */
public class TaskChangeRequestService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final Set<String> TASK_OPEN = Set.of("DA_GIAO", "DANG_THUC_HIEN", "TAM_DUNG");
  static final String T_SUBMITTED = "DE_NGHI_CHO_DUYET";
  static final String T_WITHDRAWN = "DE_NGHI_DA_RUT";
  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final Logger log = LoggerFactory.getLogger(TaskChangeRequestService.class);

  private final TaskChangeRequests changes;
  private final TaskLifecycleRepository tasks;
  private final TaskDraftRepository assignees;
  private final TaskBlockers blockers;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final TaskDeliverables deliverables;
  private final ChangeRequestNotices notices;
  private final Settings settings;
  private final Clock clock;
  private final ChangeApprovalStarter starter;

  /**
   * Tham số Policy Pack (BR-06-01, BR-06-06) khi CORE-05-03 chưa công bố: bước giảm hệ số C, số lần
   * gia hạn tối đa mỗi quý ở cấp người giao, số ngày làm việc tối đa mỗi lần.
   */
  public record Settings(
      BigDecimal coefficientStep, int maxExtensionsPerQuarter, int maxExtraDays) {
    public Settings {
      Objects.requireNonNull(coefficientStep, "coefficientStep");
    }

    public static Settings defaults() {
      return new Settings(new BigDecimal("0.05"), 2, 10);
    }
  }

  /** Nội dung đề nghị ({@code ChangeRequestDraft}). */
  public record Draft(
      String type,
      LocalDate newDueDate,
      BigDecimal newQuantity,
      String newProduct,
      String causeClaimed,
      UUID blockerId,
      String reason) {}

  /** Hệ quả dự kiến ({@code ChangeRequestPreview}). */
  public record Preview(
      int extraWorkingDays,
      LocalDate objectiveScoreDueDate,
      BigDecimal objectiveC,
      LocalDate subjectiveScoreDueDate,
      int subjectiveDelayDays,
      BigDecimal subjectiveC,
      boolean overThreshold,
      boolean lateSubmission,
      boolean cancelBlocked,
      int extensionNo,
      int lateWorkingDays) {}

  /** Nhiệm vụ kèm đề nghị vừa ghi. */
  public record Result(TaskRow task, ChangeRow request) {}

  public TaskChangeRequestService(
      TaskChangeRequests changes,
      TaskLifecycleRepository tasks,
      TaskDraftRepository assignees,
      TaskBlockers blockers,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      TaskDeliverables deliverables,
      ChangeRequestNotices notices,
      Settings settings,
      Clock clock,
      ChangeApprovalStarter starter) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.assignees = Objects.requireNonNull(assignees, "assignees");
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = calendar;
    this.deliverables = deliverables;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.starter = starter;
  }

  /** Xem trước hệ quả, không ghi dữ liệu. */
  @Transactional(value = TX, readOnly = true)
  public Preview previewChangeRequest(TenantId tenant, UUID taskId, Draft d, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    requireLead(tenant, task, actor);
    validate(tenant, task, d, false);
    return preview(tenant, task, d);
  }

  /**
   * Gửi đề nghị: kiểm như xem trước, chặn huỷ khi đã có minh chứng xác nhận (BR-06-09, MSG-06-09),
   * khoá nhiệm vụ rồi kiểm đề nghị cùng loại đang chờ (409), ghi đề nghị và EVT-21 tới người duyệt.
   * Gửi sau hạn vẫn nhận, ghi vết số ngày chậm trước khi gửi (BR-06-07).
   */
  @Transactional(TX)
  public Result submitChangeRequest(TenantId tenant, UUID taskId, Draft d, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    requireLead(tenant, task, actor);
    if (!TASK_OPEN.contains(task.status().name())) {
      throw new BusinessException(INVALID_STATE, "Nhiệm vụ đã kết thúc, không gửi được đề nghị");
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không gửi được đề nghị");
    }
    validate(tenant, task, d, true);
    if ("TAM_DUNG".equals(d.type()) && "TAM_DUNG".equals(task.status().name())) {
      throw new BusinessException(INVALID_STATE, "Nhiệm vụ đang tạm dừng");
    }
    Preview p = preview(tenant, task, d);
    if (p.cancelBlocked()) {
      throw new BusinessException(
          INVALID_STATE,
          "Nhiệm vụ đã có sản phẩm được nghiệm thu nên không thể huỷ.",
          List.of(),
          "MSG-06-09");
    }
    changes.lockTask(tenant, task.periodBucket(), taskId);
    if (changes.hasPending(tenant, task.periodBucket(), taskId, d.type())) {
      throw pending();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if ("GIA_HAN".equals(d.type())) {
      values.put("extensionNo", p.extensionNo());
      values.put("extraWorkingDays", p.extraWorkingDays());
    }
    values.put("overThreshold", p.overThreshold());
    values.put("lateSubmission", p.lateSubmission());
    if (p.lateSubmission()) {
      values.put("lateWorkingDays", p.lateWorkingDays());
    }
    if (d.newProduct() != null && !d.newProduct().isBlank()) {
      values.put("newProduct", d.newProduct().strip());
    }
    UUID id = UUID.randomUUID();
    boolean inserted =
        changes.insert(
            tenant,
            new TaskChangeRequests.NewChange(
                id,
                taskId,
                task.periodId(),
                task.periodBucket(),
                d.type(),
                "GIA_HAN".equals(d.type()) ? d.newDueDate() : null,
                "DOI_SO_LUONG".equals(d.type()) ? d.newQuantity() : null,
                d.reason().strip(),
                d.causeClaimed(),
                d.blockerId(),
                task.assignerId(),
                values,
                actor.personId()),
            clock.instant());
    if (!inserted) {
      throw pending();
    }
    ChangeRow row =
        changes
            .find(tenant, task.periodBucket(), taskId, id)
            .orElseThrow(NoSuchElementException::new);
    Map<String, String> params = params(task, row);
    params.put("vuotNguong", String.valueOf(p.overThreshold()));
    notify(tenant, task, row, T_SUBMITTED, row.approverId(), actor, params);
    if (p.overThreshold() && starter != null) {
      afterCommit(
          () -> {
            try {
              starter.start(
                  tenant, taskId, row.id(), task.assignerId(), row.rowVersion(), actor.personId());
            } catch (RuntimeException e) {
              log.warn(
                  "Chưa khởi tạo được tiến trình duyệt đề nghị {}: {}", row.id(), e.toString());
            }
          });
    }
    return new Result(task, row);
  }

  /** Người gửi rút đề nghị còn {@code CHO_DUYET} (→ {@code DA_RUT}). */
  @Transactional(TX)
  public Result withdrawChangeRequest(
      TenantId tenant, UUID taskId, UUID requestId, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    ChangeRow row =
        changes
            .find(tenant, task.periodBucket(), taskId, requestId)
            .orElseThrow(NoSuchElementException::new);
    if (!actor.personId().equals(row.createdBy())) {
      throw new BusinessException(FORBIDDEN, "Chỉ người gửi mới rút được đề nghị");
    }
    if (!ChangeRules.PENDING.equals(row.status())
        || !changes.withdraw(
            tenant, task.periodBucket(), requestId, actor.personId(), clock.instant())) {
      throw new BusinessException(INVALID_STATE, "Đề nghị đã được xử lý, không rút được");
    }
    ChangeRow after =
        changes
            .find(tenant, task.periodBucket(), taskId, requestId)
            .orElseThrow(NoSuchElementException::new);
    notify(tenant, task, after, T_WITHDRAWN, after.approverId(), actor, params(task, after));
    return new Result(task, after);
  }

  /** Đề nghị của nhiệm vụ cho phản hồi chi tiết. */
  @Transactional(value = TX, readOnly = true)
  public List<ChangeRow> listOf(TenantId tenant, TaskRow task) {
    return changes.listOf(tenant, task.periodBucket(), task.id());
  }

  /** Số lần gia hạn đã được duyệt trong kỳ và mức tối đa ở cấp người giao. */
  @Transactional(value = TX, readOnly = true)
  public int[] extensions(TenantId tenant, TaskRow task) {
    return new int[] {
      changes.approvedExtensions(tenant, task.periodBucket(), task.id()),
      settings.maxExtensionsPerQuarter()
    };
  }

  private Preview preview(TenantId tenant, TaskRow task, Draft d) {
    LocalDate today = LocalDate.now(clock.withZone(VN));
    LocalDate due = task.extendedDueDate() != null ? task.extendedDueDate() : task.dueDate();
    LocalDate scoreDue = task.scoreDueDate() != null ? task.scoreDueDate() : due;
    boolean extension = "GIA_HAN".equals(d.type());
    LocalDate newDue = extension ? d.newDueDate() : due;
    int extra = extension ? (int) workingDays(tenant, due, newDue) : 0;
    boolean late = today.isAfter(due);
    int lateDays = late ? (int) workingDays(tenant, due, today) : 0;
    int subjectiveDelay = (int) workingDays(tenant, scoreDue, newDue);
    int extensionNo =
        extension ? changes.approvedExtensions(tenant, task.periodBucket(), task.id()) + 1 : 0;
    boolean over =
        extension
            && ChangeRules.overThreshold(
                extensionNo, extra, settings.maxExtensionsPerQuarter(), settings.maxExtraDays());
    boolean cancelBlocked =
        "HUY".equals(d.type())
            && deliverables != null
            && deliverables.of(tenant, task.id()).stream()
                .anyMatch(m -> "DA_XAC_NHAN".equals(m.get("status")));
    return new Preview(
        extra,
        newDue,
        ChangeRules.coefficient(settings.coefficientStep(), lateDays),
        scoreDue,
        subjectiveDelay,
        ChangeRules.coefficient(settings.coefficientStep(), subjectiveDelay),
        over,
        late,
        cancelBlocked,
        extensionNo,
        lateDays);
  }

  /**
   * Kiểm nội dung đề nghị. Xem trước chạy khi cán bộ mới nhập hạn mới, nên chỉ kiểm các trường dùng
   * để tính hệ quả; nguyên nhân và lý do chỉ bắt buộc khi gửi ({@code full}).
   */
  private void validate(TenantId tenant, TaskRow task, Draft d, boolean full) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (d == null) {
      throw invalid("type", "Thiếu nội dung đề nghị");
    }
    if (d.type() == null || !ChangeRules.TYPES.contains(d.type())) {
      errors.add(field("type", "Loại đề nghị không hợp lệ"));
    }
    if (d.causeClaimed() != null && !ChangeRules.CAUSES.contains(d.causeClaimed())
        || full && d.causeClaimed() == null) {
      errors.add(field("causeClaimed", "Chưa chọn nguyên nhân khách quan hay chủ quan"));
    }
    if (full && !ChangeRules.validReason(d.reason())) {
      errors.add(field("reason", "Lý do cần từ 20 đến 2.000 ký tự"));
    }
    LocalDate due = task.extendedDueDate() != null ? task.extendedDueDate() : task.dueDate();
    if ("GIA_HAN".equals(d.type())) {
      if (d.newDueDate() == null || !d.newDueDate().isAfter(due)) {
        errors.add(field("newDueDate", "Hạn mới phải sau hạn hiện tại"));
      } else {
        LocalDate end =
            periods
                .findById(tenant, PeriodId.of(task.periodId()))
                .map(PeriodSnapshot::endDate)
                .orElse(null);
        if (end != null && d.newDueDate().isAfter(end)) {
          errors.add(field("newDueDate", "Hạn mới phải nằm trong kỳ"));
        }
      }
    }
    if ("DOI_SO_LUONG".equals(d.type())
        && (d.newQuantity() == null || d.newQuantity().signum() <= 0)) {
      errors.add(field("newQuantity", "Số lượng mới không hợp lệ"));
    }
    if ("DOI_SAN_PHAM".equals(d.type())
        && (d.newProduct() == null || d.newProduct().isBlank() || d.newProduct().length() > 500)) {
      errors.add(field("newProduct", "Sản phẩm mới bắt buộc, tối đa 500 ký tự"));
    }
    if (d.blockerId() != null
        && blockers.find(tenant, task.periodBucket(), task.id(), d.blockerId()).isEmpty()) {
      errors.add(field("blockerId", "Điểm nghẽn không thuộc nhiệm vụ này"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Thông tin đề nghị chưa hợp lệ", errors);
    }
  }

  private void requireLead(TenantId tenant, TaskRow task, TaskActor actor) {
    boolean lead =
        assignees.assignees(tenant, task.periodBucket(), task.id()).stream()
            .anyMatch(
                a ->
                    "PERSON".equals(a.kind())
                        && actor.personId().equals(a.id())
                        && "CHU_TRI".equals(a.role()));
    if (!lead) {
      throw new BusinessException(FORBIDDEN, "Chỉ người chủ trì nhiệm vụ mới gửi được đề nghị");
    }
  }

  /** Ngày làm việc trong nửa khoảng {@code (from, to]}; không có lịch thì Thứ Hai–Thứ Sáu. */
  public long workingDays(TenantId tenant, LocalDate from, LocalDate to) {
    if (from == null || to == null || !to.isAfter(from)) {
      return 0;
    }
    if (calendar != null) {
      return calendar.countWorkingDays(tenant, from, to);
    }
    long n = 0;
    for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        n++;
      }
    }
    return n;
  }

  private void notify(
      TenantId tenant,
      TaskRow task,
      ChangeRow row,
      String template,
      UUID to,
      TaskActor actor,
      Map<String, String> params) {
    if (to == null || to.equals(actor.personId())) {
      return;
    }
    notices.notice(
        new ChangeRequestNotices.Notice(
            tenant,
            task.id(),
            row.id(),
            row.rowVersion(),
            template,
            List.of(to),
            params,
            actor.personId()));
  }

  private static Map<String, String> params(TaskRow task, ChangeRow row) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("maNhiemVu", task.code());
    p.put("tenNhiemVu", task.title());
    p.put("loaiDeNghi", row.type());
    if (row.proposedDueDate() != null) {
      p.put("hanMoi", row.proposedDueDate().toString());
    }
    return p;
  }

  private static void afterCommit(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }

  private static BusinessException pending() {
    return new BusinessException(
        INVALID_STATE, "Đã có một đề nghị cùng loại đang chờ duyệt cho nhiệm vụ này");
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException(VALIDATION, message, List.of(field(name, message)));
  }
}
