package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Aggregate kỳ đánh giá của {@code MOD-08} ({@code config.period}, QT-08, SCR-0801).
 *
 * <p>Kỳ mới luôn ở {@code MO}; "Đã tạo" trên màn hình chỉ là kỳ chưa tới ngày bắt đầu ({@link
 * #notStarted(LocalDate)}). Trạng thái chỉ đi theo năm chuyển của {@link PeriodState}: chốt kỳ đặt
 * {@code locked_at}, mở lại hậu kiểm tăng {@code reopen_count}.
 */
public final class Period {

  private final PeriodId id;
  private final TenantId tenantId;
  private final String code;
  private final String name;
  private final PeriodKind kind;
  private final PeriodFlow flow;
  private final PeriodId parentId;
  private final LocalDate startDate;
  private final LocalDate endDate;
  private final int bucket;
  private final PeriodSchedule schedule;
  private final UUID createdBy;
  private final Instant createdAt;
  private PeriodState state;
  private Instant lockedAt;
  private UUID lockedBy;
  private UUID lockSagaId;
  private int reopenCount;
  private int rowVersion;

  private Period(Builder b) {
    this.id = Objects.requireNonNull(b.id, "id");
    this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
    this.code = requireText(b.code, "code", 32);
    this.name = requireText(b.name, "name", 100);
    this.kind = Objects.requireNonNull(b.kind, "kind");
    this.flow = Objects.requireNonNull(b.flow, "flow");
    this.parentId = b.parentId;
    this.startDate = Objects.requireNonNull(b.startDate, "startDate");
    this.endDate = Objects.requireNonNull(b.endDate, "endDate");
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("Ngày kết thúc kỳ phải không sớm hơn ngày bắt đầu");
    }
    this.bucket = bucketOf(kind, startDate);
    this.schedule = Objects.requireNonNull(b.schedule, "schedule");
    this.createdBy = Objects.requireNonNull(b.createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(b.createdAt, "createdAt");
    this.state = Objects.requireNonNull(b.state, "state");
    this.lockedAt = b.lockedAt;
    this.lockedBy = b.lockedBy;
    this.lockSagaId = b.lockSagaId;
    this.reopenCount = b.reopenCount;
    this.rowVersion = b.rowVersion;
    if (state == PeriodState.DA_KHOA && lockedAt == null) {
      throw new IllegalArgumentException("Kỳ đã khoá phải có thời điểm khoá");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Giá trị phân vùng {@code period_bucket} = năm × 10 + quý của ngày bắt đầu; kỳ năm và năm học
   * lấy quý 0, kỳ tháng xếp vào quý chứa tháng (CHECK {@code ck_period__bucket}).
   */
  public static int bucketOf(PeriodKind kind, LocalDate startDate) {
    int year = startDate.getYear();
    int quarter =
        switch (kind) {
          case NAM, NAM_HOC -> 0;
          case QUY, THANG -> (startDate.getMonthValue() - 1) / 3 + 1;
        };
    return year * 10 + quarter;
  }

  /** Hai kỳ chồng lấn khi cùng loại, cùng luồng và khoảng ngày giao nhau (khoảng đóng). */
  public boolean overlaps(Period other) {
    return kind == other.kind
        && flow == other.flow
        && !startDate.isAfter(other.endDate)
        && !other.startDate.isAfter(endDate);
  }

  /** Kỳ đã tạo nhưng chưa tới ngày bắt đầu (hiển thị "Đã tạo"). */
  public boolean notStarted(LocalDate today) {
    return today.isBefore(startDate);
  }

  /** Kỳ chứa ngày {@code day}. */
  public boolean covers(LocalDate day) {
    return !day.isBefore(startDate) && !day.isAfter(endDate);
  }

  /**
   * Chuyển trạng thái theo baseline mục 9.1.
   *
   * @param causeRef saga hoặc đề nghị mở lại gây ra chuyển; với chốt kỳ đây là {@code lock_saga_id}
   * @throws IllegalPeriodTransitionException khi chuyển không hợp lệ
   */
  public PeriodTransition moveTo(
      PeriodState target, UUID logId, UUID causeRef, UUID actorId, Instant at) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(causeRef, "causeRef");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(at, "at");
    PeriodChangeCause cause =
        state
            .causeOf(target)
            .orElseThrow(() -> new IllegalPeriodTransitionException(state, target));
    PeriodState from = state;
    if (cause == PeriodChangeCause.SAGA_CHOT_KY) {
      lockSagaId = causeRef;
    }
    if (target == PeriodState.DA_KHOA) {
      lockedAt = at;
      lockedBy = actorId;
    }
    if (target == PeriodState.MO_LAI_HAU_KIEM) {
      reopenCount++;
    }
    state = target;
    rowVersion++;
    return new PeriodTransition(
        logId, id.value(), from, target, cause, causeRef, actorId, at, rowVersion);
  }

  private static String requireText(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " không được trống");
    }
    if (value.length() > max) {
      throw new IllegalArgumentException(field + " dài quá " + max + " ký tự");
    }
    return value;
  }

  public PeriodId id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public String code() {
    return code;
  }

  public String name() {
    return name;
  }

  public PeriodKind kind() {
    return kind;
  }

  public PeriodFlow flow() {
    return flow;
  }

  public PeriodId parentId() {
    return parentId;
  }

  public LocalDate startDate() {
    return startDate;
  }

  public LocalDate endDate() {
    return endDate;
  }

  public int bucket() {
    return bucket;
  }

  public PeriodSchedule schedule() {
    return schedule;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public PeriodState state() {
    return state;
  }

  public Instant lockedAt() {
    return lockedAt;
  }

  public UUID lockedBy() {
    return lockedBy;
  }

  public UUID lockSagaId() {
    return lockSagaId;
  }

  public int reopenCount() {
    return reopenCount;
  }

  public int rowVersion() {
    return rowVersion;
  }

  /** Dựng kỳ mới hoặc khôi phục kỳ từ kho. */
  public static final class Builder {
    private PeriodId id;
    private TenantId tenantId;
    private String code;
    private String name;
    private PeriodKind kind;
    private PeriodFlow flow;
    private PeriodId parentId;
    private LocalDate startDate;
    private LocalDate endDate;
    private PeriodSchedule schedule;
    private UUID createdBy;
    private Instant createdAt;
    private PeriodState state = PeriodState.MO;
    private Instant lockedAt;
    private UUID lockedBy;
    private UUID lockSagaId;
    private int reopenCount;
    private int rowVersion;

    private Builder() {}

    public Builder id(PeriodId v) {
      id = v;
      return this;
    }

    public Builder tenantId(TenantId v) {
      tenantId = v;
      return this;
    }

    public Builder code(String v) {
      code = v;
      return this;
    }

    public Builder name(String v) {
      name = v;
      return this;
    }

    public Builder kind(PeriodKind v) {
      kind = v;
      return this;
    }

    public Builder flow(PeriodFlow v) {
      flow = v;
      return this;
    }

    public Builder parentId(PeriodId v) {
      parentId = v;
      return this;
    }

    public Builder startDate(LocalDate v) {
      startDate = v;
      return this;
    }

    public Builder endDate(LocalDate v) {
      endDate = v;
      return this;
    }

    public Builder schedule(PeriodSchedule v) {
      schedule = v;
      return this;
    }

    public Builder created(UUID by, Instant at) {
      createdBy = by;
      createdAt = at;
      return this;
    }

    public Builder state(PeriodState v) {
      state = v;
      return this;
    }

    public Builder locked(Instant at, UUID by, UUID sagaId) {
      lockedAt = at;
      lockedBy = by;
      lockSagaId = sagaId;
      return this;
    }

    public Builder reopenCount(int v) {
      reopenCount = v;
      return this;
    }

    public Builder rowVersion(int v) {
      rowVersion = v;
      return this;
    }

    public Period build() {
      return new Period(this);
    }
  }
}
