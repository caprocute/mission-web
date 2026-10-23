package vn.viettel.okrkpi.task.application.assign;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.CatalogDefaults;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.NewDraft;
import vn.viettel.okrkpi.task.application.lifecycle.port.TransitionTableProvider;
import vn.viettel.okrkpi.task.application.tree.TaskTreeService;
import vn.viettel.okrkpi.task.domain.assign.TaskCodes;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;
import vn.viettel.okrkpi.task.domain.similar.TaskSimilarity;
import vn.viettel.okrkpi.task.domain.tree.TaskTree;

/**
 * Soạn nhiệm vụ {@code NHAP} của SCR-0501 (CORE-02-14): khung soạn mới, lưu nháp lần đầu (sinh mã
 * {@code NV-yyyy-Qn-nnnn}), lưu nháp với {@code If-Match}, huỷ bản nháp và đọc ở dạng soạn. Chưa
 * phát sự kiện; giao việc ở {@link TaskAssignService}.
 */
public class TaskDraftService {

  static final String TX = "taskTransactionManager";
  public static final String NOT_DRAFT = "TASK-409-NOT-DRAFT";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  static final Set<String> SOURCES = Set.of("KE_HOACH", "VAN_BAN", "DANG_KY", "DOT_XUAT");
  static final Set<String> GROUPS = Set.of("THUONG_XUYEN", "THEN_CHOT");
  static final BigDecimal MAX_COMPONENT = new BigDecimal("999.999");
  static final Set<String> PRIORITIES = Set.of("CAO", "TRUNG_BINH", "THAP");

  private final TaskDraftRepository drafts;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final TransitionTableProvider tables;
  private final Clock clock;
  private final TaskTreeService tree;

  public TaskDraftService(
      TaskDraftRepository drafts,
      PeriodApi periods,
      OrgDirectoryApi org,
      TransitionTableProvider tables,
      Clock clock) {
    this(drafts, periods, org, tables, clock, null);
  }

  /** Như trên, kèm kiểm cây phân rã và chế độ phân công (CORE-02-28); {@code tree} có thể rỗng. */
  public TaskDraftService(
      TaskDraftRepository drafts,
      PeriodApi periods,
      OrgDirectoryApi org,
      TransitionTableProvider tables,
      Clock clock,
      TaskTreeService tree) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = Objects.requireNonNull(org, "org");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tree = tree;
  }

  /** Dịch vụ cây nhiệm vụ; rỗng khi chưa cấu hình. */
  TaskTreeService tree() {
    return tree;
  }

  /** Khung soạn: kỳ và bản nháp (chưa lưu thì {@code id} rỗng). */
  public record Template(PeriodSnapshot period, DraftRow draft) {}

  /** Khung soạn nhiệm vụ mới: kỳ đang mở, đơn vị của người giao, giá trị mặc định. */
  @Transactional(value = TX, readOnly = true)
  public Template newDraft(TenantId tenant, TaskActor actor) {
    PeriodSnapshot period =
        periods
            .findCurrent(tenant)
            .orElseThrow(() -> new BusinessException(PERIOD_LOCKED, "Chưa có kỳ đang mở"));
    TaskDraftSpec spec =
        new TaskDraftSpec(
            period.id().value(),
            "DOT_XUAT",
            null,
            null,
            "",
            "THUONG_XUYEN",
            null,
            "TRUNG_BINH",
            null,
            List.of(),
            null,
            actor.personId(),
            List.of(),
            "",
            null,
            "",
            "",
            "",
            null,
            null,
            null);
    DraftRow row =
        new DraftRow(
            null,
            null,
            "NHAP",
            period.id().value(),
            PlanSchedule.bucketOf(period.startDate()),
            unitOf(tenant, actor.personId()),
            actor.personId(),
            false,
            spec,
            0);
    return new Template(period, row);
  }

  /** Lưu nháp lần đầu: nhiệm vụ {@code NHAP} mang mã mới, người giao là người gọi. */
  @Transactional(TX)
  public DraftRow createDraft(TenantId tenant, TaskDraftSpec spec, TaskActor actor) {
    validate(spec);
    try {
      periods.assertWritable(tenant, PeriodId.of(spec.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không lưu được nhiệm vụ");
    }
    return insert(tenant, spec, actor, null);
  }

  /**
   * Thêm nhiệm vụ {@code NHAP} trong giao dịch của người gọi, chủ trì là đơn vị khi {@code
   * leadUnitId} khác {@code null}: nhiệm vụ xác nhận từ văn bản đến (CORE-02-16). Người gọi đã kiểm
   * khoá kỳ.
   */
  public DraftRow insertDraft(
      TenantId tenant, TaskDraftSpec spec, TaskActor actor, UUID leadUnitId) {
    return insert(tenant, spec, actor, leadUnitId);
  }

  /**
   * Thêm nhiệm vụ {@code NHAP}; {@code leadUnitId} khác {@code null} khi chủ trì là đơn vị (giao
   * đồng loạt).
   */
  DraftRow insert(TenantId tenant, TaskDraftSpec spec, TaskActor actor, UUID leadUnitId) {
    validate(spec);
    PeriodSnapshot period = period(tenant, spec.periodId());
    try {
      periods.assertWritable(tenant, period.id());
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không lưu được nhiệm vụ");
    }
    int bucket = PlanSchedule.bucketOf(period.startDate());
    TaskDraftSpec stored = normalize(spec, period);
    if (tree != null) {
      tree.requirePlacement(tenant, null, period.id().value(), stored.node());
    }
    CatalogDefaults catalog = catalog(tenant, stored.catalogItemId());
    UUID id = UUID.randomUUID();
    String code = TaskCodes.code(bucket, drafts.nextCodeNumber(tenant, bucket));
    UUID unit = unitOf(tenant, actor.personId());
    drafts.insertDraft(
        tenant,
        new NewDraft(
            id,
            code,
            period.id().value(),
            bucket,
            unit,
            actor.personId(),
            stored,
            catalog.difficulty(),
            catalog.standardScore(),
            fingerprint(stored, leadUnitId),
            tables.tableFor(tenant, period.id().value()).policyVersion(),
            leadUnitId),
        clock.instant());
    return drafts.findDraft(tenant, id).orElseThrow(NoSuchElementException::new);
  }

  /** Lưu nháp khi nhiệm vụ còn {@code NHAP} và khớp {@code If-Match}. */
  @Transactional(TX)
  public DraftRow saveDraft(
      TenantId tenant, UUID taskId, long expected, TaskDraftSpec spec, TaskActor actor) {
    DraftRow current = drafts.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
    requireDraft(current, expected);
    try {
      periods.assertWritable(tenant, PeriodId.of(current.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không lưu được nhiệm vụ");
    }
    validate(spec);
    if (!current.periodId().equals(spec.periodId())) {
      throw invalid("periodId", "Không đổi được kỳ của nhiệm vụ đã lưu");
    }
    TaskDraftSpec stored = normalize(spec, period(tenant, spec.periodId()));
    if (tree != null) {
      tree.requirePlacement(tenant, taskId, stored.periodId(), stored.node());
    }
    CatalogDefaults catalog = catalog(tenant, stored.catalogItemId());
    long version =
        drafts.storeDraft(
            tenant,
            current,
            expected,
            stored,
            catalog.difficulty(),
            catalog.standardScore(),
            fingerprint(stored, null),
            actor.personId(),
            clock.instant());
    if (version < 0) {
      throw new VersionConflictException("TASK", expected, null);
    }
    return drafts.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
  }

  /** Huỷ bản nháp; nhiệm vụ đã giao phải huỷ qua đề nghị (SCR-0602). */
  @Transactional(TX)
  public void deleteDraft(TenantId tenant, UUID taskId, long expected, TaskActor actor) {
    DraftRow current = drafts.findDraft(tenant, taskId).orElseThrow(NoSuchElementException::new);
    requireDraft(current, expected);
    try {
      periods.assertWritable(tenant, PeriodId.of(current.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không huỷ được bản nháp");
    }
    if (!drafts.discardDraft(tenant, current.periodBucket(), taskId, expected)) {
      throw new VersionConflictException("TASK", expected, null);
    }
  }

  /** Nhiệm vụ {@code NHAP} ở dạng soạn kèm kỳ; nhiệm vụ đã giao thì không có (404). */
  @Transactional(value = TX, readOnly = true)
  public Template getDraft(TenantId tenant, UUID taskId) {
    DraftRow row =
        drafts
            .findDraft(tenant, taskId)
            .filter(d -> "NHAP".equals(d.status()))
            .orElseThrow(NoSuchElementException::new);
    return new Template(period(tenant, row.periodId()), row);
  }

  static void requireDraft(DraftRow current, long expected) {
    if (!"NHAP".equals(current.status())) {
      throw new BusinessException(NOT_DRAFT, "Nhiệm vụ đã được giao, không sửa được bản nháp");
    }
    if (current.rowVersion() != expected) {
      throw new VersionConflictException("TASK", expected, current.rowVersion());
    }
  }

  PeriodSnapshot period(TenantId tenant, UUID periodId) {
    return periods
        .findById(tenant, PeriodId.of(periodId))
        .orElseThrow(() -> invalid("periodId", "Kỳ không tồn tại"));
  }

  /** Đơn vị công tác hiện tại của người giao trong bản sao tổ chức; rỗng khi chưa có. */
  UUID unitOf(TenantId tenant, UUID person) {
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    // Bản sao đã xếp phân công chính (branch CHINH) lên đầu.
    return org.assignmentsOf(tenant, person, today).stream()
        .findFirst()
        .map(AssignmentView::orgUnitId)
        .orElse(null);
  }

  private CatalogDefaults catalog(TenantId tenant, UUID catalogItemId) {
    if (catalogItemId == null) {
      return new CatalogDefaults(null, null);
    }
    return drafts
        .catalogDefaults(tenant, catalogItemId)
        .orElseThrow(() -> invalid("catalogItemId", "Mục danh mục không tồn tại"));
  }

  /** Giá trị cột bắt buộc khi bản nháp còn thiếu: hạn là cuối kỳ, số lượng là 1 (suy luận). */
  private static TaskDraftSpec normalize(TaskDraftSpec s, PeriodSnapshot period) {
    return new TaskDraftSpec(
        s.periodId(),
        s.source(),
        s.planItemId(),
        s.catalogItemId(),
        s.title().strip(),
        s.taskGroup(),
        s.keyResultId(),
        s.priority() == null ? "TRUNG_BINH" : s.priority(),
        s.leadPersonId(),
        s.coAssignees(),
        s.dueDate() == null ? period.endDate() : s.dueDate(),
        s.acceptorId(),
        s.milestones(),
        s.productName(),
        s.quantity() == null || s.quantity().signum() <= 0 ? BigDecimal.ONE : s.quantity(),
        s.unit() == null ? "" : s.unit(),
        s.evidenceRequired(),
        s.qualityCriteria(),
        s.sourceDocNumber(),
        s.sourceDocTitle(),
        s.basis(),
        s.node());
  }

  static void validate(TaskDraftSpec s) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (s == null) {
      throw invalid("title", "Thiếu nội dung nhiệm vụ");
    }
    check(errors, s.periodId() != null, "periodId", "Chưa chọn kỳ");
    check(
        errors, s.source() != null && SOURCES.contains(s.source()), "source", "Nguồn không hợp lệ");
    check(
        errors,
        s.title() != null && !s.title().isBlank() && s.title().length() <= 500,
        "title",
        "Tên nhiệm vụ bắt buộc, tối đa 500 ký tự");
    check(
        errors,
        s.taskGroup() != null && GROUPS.contains(s.taskGroup()),
        "taskGroup",
        "Nhóm nhiệm vụ không hợp lệ");
    check(
        errors,
        s.priority() == null || PRIORITIES.contains(s.priority()),
        "priority",
        "Mức ưu tiên không hợp lệ");
    check(
        errors,
        s.quantity() == null || s.quantity().signum() >= 0,
        "quantity",
        "Số lượng không được âm");
    check(errors, length(s.unit(), 50), "unit", "Đơn vị tính tối đa 50 ký tự");
    check(errors, length(s.productName(), 500), "productName", "Sản phẩm tối đa 500 ký tự");
    check(
        errors,
        length(s.evidenceRequired(), 1000),
        "evidenceRequired",
        "Minh chứng yêu cầu tối đa 1.000 ký tự");
    check(
        errors,
        length(s.qualityCriteria(), 2000),
        "qualityCriteria",
        "Tiêu chuẩn chất lượng tối đa 2.000 ký tự");
    check(errors, length(s.basis(), 1000), "basis", "Căn cứ tối đa 1.000 ký tự");
    check(
        errors,
        s.milestones().stream()
            .allMatch(m -> m.date() != null && m.content() != null && length(m.content(), 500)),
        "milestones",
        "Mốc kiểm soát cần ngày và nội dung tối đa 500 ký tự");
    check(
        errors,
        s.coAssignees().stream()
            .allMatch(
                a -> a.id() != null && ("PERSON".equals(a.kind()) || "UNIT".equals(a.kind()))),
        "coAssignees",
        "Người phối hợp không hợp lệ");
    check(
        errors,
        TaskTree.KINDS.contains(s.node().kind()),
        "nodeKind",
        "Loại công việc không hợp lệ");
    BigDecimal component = s.node().componentScore();
    check(
        errors,
        component == null
            || (component.signum() >= 0
                && component.compareTo(MAX_COMPONENT) <= 0
                && component.stripTrailingZeros().scale() <= 3),
        "componentScore",
        "Điểm thành phần từ 0 đến 999,999, tối đa ba chữ số thập phân");
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Thông tin nhiệm vụ chưa hợp lệ", errors);
    }
  }

  /** Dấu vân tay chống trùng (BR-05-06, CORE-02-15): kỳ, tên, sản phẩm, chủ trì đã chuẩn hoá. */
  public static byte[] fingerprint(TaskDraftSpec s, UUID leadUnitId) {
    return TaskSimilarity.fingerprint(
        s.periodId(),
        s.title(),
        s.productName(),
        leadUnitId != null ? leadUnitId : s.leadPersonId());
  }

  private static boolean length(String v, int max) {
    return v == null || v.length() <= max;
  }

  private static void check(List<ProblemFieldError> errors, boolean ok, String field, String msg) {
    if (!ok) {
      errors.add(new ProblemFieldError(field, msg, null));
    }
  }

  public static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }
}
