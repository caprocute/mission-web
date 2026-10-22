package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinEvents;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinExtension;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.NewCheckin;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator;
import vn.viettel.okrkpi.okr.domain.RiskFlagPolicy;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyExceptions;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Check-in KR của QT-03 (CORE-01-08, ADR-04 tầng 1: chạy thẳng trong MOD-01, không qua BPMN). Lưu
 * nháp ({@code NHAP}) chỉ người tạo thấy; gửi ({@code DA_GUI}) ghi một dòng bất biến, cập nhật giá
 * trị hiện tại, mức tự tin, tiến độ KR và tiến độ mục tiêu trong cùng một giao dịch ({@code timeout
 * = 5}). Kiểm BR-03-01, BR-03-02, BR-03-05; BR-03-03 bảo đảm bằng việc không có lệnh sửa, xoá.
 *
 * <p>Bảng {@code okr.kr_checkin} chỉ ghi thêm nên "ghi đè bản nháp" là ghi một dòng {@code NHAP}
 * mới: bản nháp hiệu lực là dòng {@code NHAP} mới nhất của người tạo sau lần gửi gần nhất của người
 * đó; các dòng nháp cũ hơn không còn được đọc (suy luận).
 */
public class OkrCheckinService {

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final String NHAP = "NHAP";
  static final String DA_GUI = "DA_GUI";

  /** Tham số Policy Pack: bắt buộc ghi vướng mắc khi tự tin Thấp (suy luận tên khoá). */
  static final String P_BLOCKER_REQUIRED_ON_LOW = "okr_checkin_blocker_required_on_low";

  /** Ngưỡng BR-03-05: thay đổi quá 50% khoảng chỉ tiêu so với lần gửi trước. */
  static final BigDecimal LARGE_CHANGE = new BigDecimal("0.5");

  static final int MAX_TEXT = 1000;
  static final int MAX_OVERRIDE_REASON = 500;
  static final int MAX_SCALE = 4;
  static final BigDecimal MAX_ABS_VALUE = new BigDecimal("99999999999999.9999");

  private final OkrCheckinRepository repository;
  private final PeriodApi periods;
  private final OkrCheckService checks;
  private final Supplier<OkrCheckinDelegatePort> delegates;
  private final Supplier<List<OkrCheckinExtension>> extensions;
  private final OkrCheckinEvents events;

  public OkrCheckinService(
      OkrCheckinRepository repository,
      PeriodApi periods,
      OkrCheckService checks,
      Supplier<OkrCheckinDelegatePort> delegates,
      Supplier<List<OkrCheckinExtension>> extensions,
      OkrCheckinEvents events) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.delegates = Objects.requireNonNull(delegates, "delegates");
    this.extensions = Objects.requireNonNull(extensions, "extensions");
    this.events = Objects.requireNonNull(events, "events");
  }

  /** Nội dung lưu nháp hoặc gửi (SCR-0301 trường 5-11, SCR-0302). */
  public record CheckinCommand(
      String status,
      BigDecimal value,
      String confidence,
      BigDecimal sourceValue,
      Instant sourceFetchedAt,
      String overrideReason,
      String highlights,
      String blockerNote,
      String channel,
      boolean confirmLargeChange) {}

  /** Check-in vừa ghi kèm KR, mục tiêu sau khi tính lại (MSG-03-01). */
  public record CheckinOutcome(CheckinRow checkin, CheckinTarget keyResult, boolean onBehalf) {}

  /** Một trang lịch sử kèm chủ sở hữu KR để đánh dấu "check-in thay". */
  public record History(List<CheckinRow> items, UUID owner) {}

  // ---------------------------------------------------------------- lệnh

  @Transactional(transactionManager = TX, timeout = 5)
  public CheckinOutcome record(
      TenantId tenant, UUID actor, UUID keyResultId, String idempotencyKey, CheckinCommand cmd) {
    CheckinTarget target = repository.target(tenant, keyResultId).orElseThrow(NotFound::new);
    boolean onBehalf = requireCheckinRight(tenant, actor, target);
    requireOpen(tenant, target);
    validate(tenant, target, cmd);
    if (NHAP.equals(cmd.status())) {
      return saveDraft(tenant, actor, target, cmd, onBehalf);
    }
    return submit(tenant, actor, target, idempotencyKey, cmd, onBehalf);
  }

  /** Lịch sử check-in {@code DA_GUI}, mới nhất trước; bản nháp chỉ trả cho người tạo. */
  @Transactional(transactionManager = TX, readOnly = true)
  public History history(
      TenantId tenant, UUID viewer, UUID keyResultId, Instant afterAt, UUID afterId, int limit) {
    CheckinTarget target = repository.target(tenant, keyResultId).orElseThrow(NotFound::new);
    // Bản nháp luôn mới hơn mọi bản đã gửi của người tạo nên chỉ trả ở trang đầu.
    UUID draftId =
        afterAt != null || viewer == null
            ? null
            : repository
                .draft(tenant, target.periodBucket(), keyResultId, viewer)
                .map(CheckinRow::id)
                .orElse(null);
    return new History(
        repository.history(
            tenant, target.periodBucket(), keyResultId, draftId, afterAt, afterId, limit),
        target.owner());
  }

  // ---------------------------------------------------------------- nội bộ

  private CheckinOutcome saveDraft(
      TenantId tenant, UUID actor, CheckinTarget target, CheckinCommand cmd, boolean onBehalf) {
    UUID id = UUID.randomUUID();
    repository.insert(tenant, newCheckin(id, target, cmd, NHAP, "NHAP-" + id), actor);
    CheckinRow row = repository.byId(tenant, target.periodBucket(), id).orElseThrow();
    return new CheckinOutcome(row, target, onBehalf);
  }

  private CheckinOutcome submit(
      TenantId tenant,
      UUID actor,
      CheckinTarget unlocked,
      String key,
      CheckinCommand cmd,
      boolean onBehalf) {
    int bucket = unlocked.periodBucket();
    // Gửi lại sau khi khoá Idempotency-Key đã hết hạn: khoá UQ của kr_checkin trả lại kết quả cũ.
    Optional<CheckinRow> replay = repository.byIdempotencyKey(tenant, bucket, key);
    if (replay.isPresent()) {
      return replayOf(tenant, replay.get(), unlocked, cmd, onBehalf);
    }
    CheckinTarget target =
        repository
            .lockTarget(tenant, bucket, unlocked.objectiveId(), unlocked.keyResultId())
            .orElseThrow(NotFound::new);
    requireApproved(target);
    Optional<CheckinRow> last = repository.lastSubmitted(tenant, bucket, target.keyResultId());
    if (!cmd.confirmLargeChange()) {
      last.ifPresent(l -> largeChange(target, l.value(), cmd.value()));
    }
    UUID id = UUID.randomUUID();
    repository.insert(tenant, newCheckin(id, target, cmd, DA_GUI, key), actor);
    BigDecimal krProgress =
        ProgressCalculator.keyResult(
            target.metricType(),
            target.direction(),
            target.baselineValue(),
            target.targetValue(),
            cmd.value());
    // CORE-01-09, BR-03-06: cờ rủi ro theo lần gửi ngay trước của KR.
    String riskFlag =
        RiskFlagPolicy.next(
            target.riskFlag(), last.map(CheckinRow::confidence).orElse(null), cmd.confidence());
    if (!repository.writeKeyResultProgress(
        tenant,
        bucket,
        target.keyResultId(),
        target.rowVersion(),
        cmd.value(),
        cmd.confidence(),
        krProgress,
        riskFlag,
        actor)) {
      throw new BusinessException(
          MODULE + "-409-VERSION-CONFLICT",
          "Kết quả then chốt vừa được cập nhật, vui lòng gửi lại");
    }
    BigDecimal objectiveProgress =
        ProgressCalculator.objective(
            repository.objectiveWeights(tenant, bucket, target.objectiveId()));
    repository.writeObjectiveProgress(
        tenant, bucket, target.objectiveId(), objectiveProgress, actor);
    CheckinRow row = repository.byId(tenant, bucket, id).orElseThrow();
    long version = target.rowVersion() + 1;
    events.checkedIn(tenant, target, row, version);
    if (RiskFlagPolicy.becameAtRisk(target.riskFlag(), riskFlag)) {
      events.atRisk(tenant, target, row, version);
    }
    List<OkrCheckinExtension> hooks = extensions.get();
    if (hooks != null) {
      for (OkrCheckinExtension hook : hooks) {
        hook.onSubmitted(
            new OkrCheckinExtension.Submitted(
                tenant, actor, target, row, krProgress, objectiveProgress, onBehalf));
      }
    }
    return new CheckinOutcome(
        row, repository.target(tenant, target.keyResultId()).orElseThrow(), onBehalf);
  }

  private CheckinOutcome replayOf(
      TenantId tenant,
      CheckinRow previous,
      CheckinTarget target,
      CheckinCommand cmd,
      boolean onBehalf) {
    boolean same =
        DA_GUI.equals(previous.status())
            && previous.keyResultId().equals(target.keyResultId())
            && previous.value().compareTo(cmd.value()) == 0
            && previous.confidence().equals(cmd.confidence());
    if (!same) {
      throw new IdempotencyExceptions.Mismatch();
    }
    return new CheckinOutcome(previous, target, onBehalf);
  }

  /** Chủ sở hữu KR hoặc người được giao check-in thay; trả {@code true} khi là check-in thay. */
  private boolean requireCheckinRight(TenantId tenant, UUID actor, CheckinTarget target) {
    if (actor.equals(target.owner())) {
      return false;
    }
    OkrCheckinDelegatePort port = delegates.get();
    if (port != null && port.isDelegate(tenant, target.keyResultId(), actor)) {
      return true;
    }
    throw new Forbidden();
  }

  /** BR-03-01 phần kỳ: kỳ phải {@code MO}; kỳ khoá trả MSG-03-05. */
  private void requireOpen(TenantId tenant, CheckinTarget target) {
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(target.periodId()))
            .orElseThrow(() -> new IllegalStateException("KR trỏ tới kỳ không tồn tại"));
    boolean writable = period.status() == PeriodStatus.MO;
    if (writable) {
      try {
        periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(target.ownerUnitId()));
      } catch (PeriodLockedException e) {
        writable = false;
      }
    }
    if (!writable) {
      throw new BusinessException(
          MODULE + "-409-PERIOD-LOCKED",
          "Kỳ " + period.name() + " đã khoá. Check-in lưu trên máy chưa được gửi.");
    }
    requireApproved(target);
  }

  /** BR-03-01 phần mục tiêu: chỉ check-in KR của mục tiêu đã duyệt. */
  private static void requireApproved(CheckinTarget target) {
    if (!"DA_DUYET".equals(target.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-OBJECTIVE-NOT-APPROVED",
          "Chỉ cập nhật tiến độ được cho kết quả then chốt của mục tiêu đã được phê duyệt.");
    }
  }

  /** BR-03-02 và giới hạn trường của {@code OkrCheckinCreate}. */
  private void validate(TenantId tenant, CheckinTarget target, CheckinCommand cmd) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (cmd.status() == null) {
      errors.add(field("status", "Chọn lưu nháp hoặc gửi"));
    }
    if (cmd.value() == null) {
      errors.add(field("value", "Nhập giá trị hiện tại"));
    } else if (cmd.value().stripTrailingZeros().scale() > MAX_SCALE) {
      errors.add(field("value", "Giá trị hiện tại có tối đa 4 chữ số sau dấu phẩy"));
    } else if (cmd.value().abs().compareTo(MAX_ABS_VALUE) > 0) {
      errors.add(field("value", "Giá trị hiện tại quá lớn"));
    }
    if (cmd.confidence() == null) {
      errors.add(field("confidence", "Chọn mức tự tin"));
    }
    length(errors, "highlights", cmd.highlights(), MAX_TEXT, "Kết quả nổi bật");
    length(errors, "blockerNote", cmd.blockerNote(), MAX_TEXT, "Vướng mắc");
    length(errors, "overrideReason", cmd.overrideReason(), MAX_OVERRIDE_REASON, "Lý do");
    if (cmd.sourceValue() != null
        && cmd.value() != null
        && cmd.sourceValue().compareTo(cmd.value()) != 0
        && blank(cmd.overrideReason())) {
      // Ràng buộc ck_kr_checkin__override_reason; thông điệp đầy đủ MSG-03-04 thuộc CORE-01-10.
      throw new BusinessException(
          MODULE + "-422-OVERRIDE-REASON-REQUIRED",
          "Vui lòng ghi lý do khi thay đổi số liệu lấy từ nguồn.",
          List.of(field("overrideReason", "Ghi lý do thay đổi số liệu")));
    }
    if (errors.isEmpty()
        && DA_GUI.equals(cmd.status())
        && "THAP".equals(cmd.confidence())
        && blank(cmd.blockerNote())
        && checks.flag(
            tenant, target.periodId(), target.ownerUnitId(), P_BLOCKER_REQUIRED_ON_LOW)) {
      errors.add(field("blockerNote", "Ghi vướng mắc, đề nghị hỗ trợ khi mức tự tin Thấp"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", errors.getFirst().message(), List.copyOf(errors));
    }
  }

  /** BR-03-05, MSG-03-02: chênh quá 50% khoảng chỉ tiêu so với lần gửi trước phải xác nhận. */
  private static void largeChange(CheckinTarget target, BigDecimal previous, BigDecimal value) {
    if (previous == null || target.targetValue() == null) {
      return;
    }
    BigDecimal base = target.baselineValue() == null ? BigDecimal.ZERO : target.baselineValue();
    BigDecimal range = target.targetValue().subtract(base).abs();
    if (range.signum() == 0) {
      return;
    }
    BigDecimal ratio = value.subtract(previous).abs().divide(range, 6, RoundingMode.HALF_UP);
    if (ratio.compareTo(LARGE_CHANGE) > 0) {
      String pct =
          ratio
              .movePointRight(2)
              .setScale(1, RoundingMode.HALF_UP)
              .stripTrailingZeros()
              .toPlainString()
              .replace('.', ',');
      String message =
          "Giá trị mới chênh " + pct + "% so với lần trước. Đồng chí có chắc chắn không?";
      throw new BusinessException(
          MODULE + "-422-LARGE-CHANGE-UNCONFIRMED",
          message,
          List.of(new ProblemFieldError("value", message, "BR-03-05")));
    }
  }

  private static NewCheckin newCheckin(
      UUID id, CheckinTarget target, CheckinCommand cmd, String status, String key) {
    return new NewCheckin(
        id,
        target.periodId(),
        target.periodBucket(),
        target.keyResultId(),
        cmd.value(),
        cmd.confidence(),
        trim(cmd.highlights()),
        trim(cmd.blockerNote()),
        cmd.channel() == null ? "WEB" : cmd.channel(),
        status,
        key,
        cmd.sourceValue(),
        cmd.sourceFetchedAt(),
        trim(cmd.overrideReason()));
  }

  private static void length(
      List<ProblemFieldError> errors, String name, String value, int max, String label) {
    if (value != null && value.length() > max) {
      errors.add(field(name, label + " tối đa " + max + " ký tự"));
    }
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String trim(String s) {
    return blank(s) ? null : s.strip();
  }

  /** Không có KR theo id (404). */
  public static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFound() {
      super("Không tìm thấy", null, false, false);
    }
  }

  /** Người gọi không phải chủ sở hữu KR hay người được giao check-in thay (403). */
  public static final class Forbidden extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Forbidden() {
      super("Không có quyền check-in kết quả then chốt này", null, false, false);
    }
  }
}
