package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh khoá kỳ ({@code config.period_lock_order}, QT-08 bước 1–2). Tách vai trò BR-08-01: người
 * duyệt khác người lập, người khởi chạy khác người duyệt.
 */
public final class PeriodLockOrder {

  /** Vi phạm tách vai trò (BR-08-01). */
  public static final class SamePersonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SamePersonException(String message) {
      super(message);
    }
  }

  /** Chuyển trạng thái không hợp lệ. */
  public static final class IllegalStatusException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final LockOrderStatus from;

    IllegalStatusException(LockOrderStatus from, LockOrderStatus to) {
      super("Lệnh khoá kỳ không chuyển được từ " + from + " sang " + to);
      this.from = from;
    }

    public LockOrderStatus from() {
      return from;
    }
  }

  private final UUID id;
  private final TenantId tenantId;
  private final PeriodId periodId;
  private final String basisDoc;
  private final Instant plannedLockAt;
  private final UUID requestedBy;
  private final Instant requestedAt;
  private final String idempotencyKey;
  private LockOrderStatus status;
  private UUID approvedBy;
  private Instant approvedAt;
  private UUID startedBy;
  private Instant startedAt;
  private UUID sagaId;
  private int rowVersion;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PeriodLockOrder(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      LockOrderStatus status,
      String basisDoc,
      Instant plannedLockAt,
      UUID requestedBy,
      Instant requestedAt,
      UUID approvedBy,
      Instant approvedAt,
      UUID startedBy,
      Instant startedAt,
      UUID sagaId,
      String idempotencyKey,
      int rowVersion) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.periodId = Objects.requireNonNull(periodId, "periodId");
    this.status = Objects.requireNonNull(status, "status");
    this.basisDoc = Objects.requireNonNull(basisDoc, "basisDoc");
    this.plannedLockAt = plannedLockAt;
    this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
    this.requestedAt = requestedAt;
    this.approvedBy = approvedBy;
    this.approvedAt = approvedAt;
    this.startedBy = startedBy;
    this.startedAt = startedAt;
    this.sagaId = sagaId;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.rowVersion = rowVersion;
  }

  /** Lệnh mới lập, chờ AC-04 duyệt. */
  public static PeriodLockOrder request(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      String basisDoc,
      Instant plannedLockAt,
      UUID requestedBy,
      Instant at,
      String idempotencyKey) {
    return new PeriodLockOrder(
        id,
        tenantId,
        periodId,
        LockOrderStatus.CHO_DUYET,
        basisDoc,
        plannedLockAt,
        requestedBy,
        at,
        null,
        null,
        null,
        null,
        null,
        idempotencyKey,
        0);
  }

  /** AC-04 duyệt (BR-08-01: khác người lập). */
  public void approve(UUID approver, Instant at) {
    Objects.requireNonNull(approver, "approver");
    move(LockOrderStatus.DA_DUYET);
    if (approver.equals(requestedBy)) {
      throw new SamePersonException("Người phê duyệt phải khác người lập lệnh khoá kỳ.");
    }
    status = LockOrderStatus.DA_DUYET;
    approvedBy = approver;
    approvedAt = at;
    rowVersion++;
  }

  /** AC-02 khởi chạy saga (BR-08-01: khác người duyệt). */
  public void start(UUID starter, UUID saga, Instant at) {
    Objects.requireNonNull(starter, "starter");
    Objects.requireNonNull(saga, "saga");
    move(LockOrderStatus.DANG_CHAY);
    requireStarterDiffersFromApprover(starter);
    status = LockOrderStatus.DANG_CHAY;
    startedBy = starter;
    startedAt = at;
    sagaId = saga;
    rowVersion++;
  }

  /** Kiểm trước khi gọi saga: lệnh đã duyệt và người khởi chạy khác người duyệt. */
  public void checkStartable(UUID starter) {
    move(LockOrderStatus.DANG_CHAY);
    requireStarterDiffersFromApprover(starter);
  }

  private void requireStarterDiffersFromApprover(UUID starter) {
    if (starter.equals(approvedBy)) {
      throw new SamePersonException("Người khởi chạy phải khác người phê duyệt lệnh khoá kỳ.");
    }
  }

  private void move(LockOrderStatus target) {
    if (!status.canMoveTo(target)) {
      throw new IllegalStatusException(status, target);
    }
  }

  public UUID id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public PeriodId periodId() {
    return periodId;
  }

  public LockOrderStatus status() {
    return status;
  }

  public String basisDoc() {
    return basisDoc;
  }

  public Instant plannedLockAt() {
    return plannedLockAt;
  }

  public UUID requestedBy() {
    return requestedBy;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public UUID approvedBy() {
    return approvedBy;
  }

  public Instant approvedAt() {
    return approvedAt;
  }

  public UUID startedBy() {
    return startedBy;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public UUID sagaId() {
    return sagaId;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public int rowVersion() {
    return rowVersion;
  }
}
