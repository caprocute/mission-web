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
  private boolean pauseRequested;
  private UUID pauseRequestedBy;
  private Instant pauseRequestedAt;
  private LockCancelRequest cancelRequest;

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

  /**
   * Nạp lại trạng thái điều khiển đã lưu (cờ tạm dừng, đề nghị huỷ) khi đọc từ kho; không đổi
   * {@code rowVersion}.
   */
  public PeriodLockOrder withControl(
      boolean pause, UUID pauseBy, Instant pauseAt, LockCancelRequest cancel) {
    this.pauseRequested = pause;
    this.pauseRequestedBy = pauseBy;
    this.pauseRequestedAt = pauseAt;
    this.cancelRequest = cancel;
    return this;
  }

  /** Saga đang chạy và chưa bước vào bù trừ (đề nghị huỷ chưa được duyệt). */
  public boolean controllable() {
    return status == LockOrderStatus.DANG_CHAY
        && sagaId != null
        && (cancelRequest == null || !cancelRequest.approved());
  }

  /**
   * Đặt cờ tạm dừng sau bước hiện tại (QT-08 bước 9).
   *
   * @return {@code false} khi cờ đã đặt từ trước (không đổi gì)
   */
  public boolean requestPause(UUID actor, Instant at) {
    requireControllable();
    if (pauseRequested) {
      return false;
    }
    pauseRequested = true;
    pauseRequestedBy = Objects.requireNonNull(actor, "actor");
    pauseRequestedAt = at;
    rowVersion++;
    return true;
  }

  /**
   * Gỡ cờ tạm dừng để saga chạy tiếp.
   *
   * @return {@code false} khi saga không bị tạm dừng (không đổi gì)
   */
  public boolean resume() {
    requireControllable();
    if (!pauseRequested) {
      return false;
    }
    pauseRequested = false;
    pauseRequestedBy = null;
    pauseRequestedAt = null;
    rowVersion++;
    return true;
  }

  /**
   * Đề nghị huỷ và bù trừ toàn bộ (BR-08-09); điểm không quay lại do bên gọi kiểm theo bước saga.
   *
   * @return {@code false} khi đã có đề nghị huỷ đang chờ duyệt (không đổi gì)
   */
  public boolean requestCancel(UUID actor, String reason, Instant at) {
    requireControllable();
    if (cancelRequest != null) {
      return false;
    }
    cancelRequest =
        new LockCancelRequest(Objects.requireNonNull(actor, "actor"), at, reason, null, null);
    rowVersion++;
    return true;
  }

  /** Người duyệt thứ hai duyệt huỷ; phải khác người đề nghị (bốn mắt, BR-08-09). */
  public void approveCancel(UUID approver, Instant at) {
    Objects.requireNonNull(approver, "approver");
    if (cancelRequest == null || cancelRequest.approved() || status != LockOrderStatus.DANG_CHAY) {
      throw new IllegalStatusException(status, LockOrderStatus.DA_HUY);
    }
    if (approver.equals(cancelRequest.requestedBy())) {
      throw new SamePersonException("Người duyệt huỷ phải khác người đề nghị huỷ.");
    }
    cancelRequest = cancelRequest.approve(approver, at);
    rowVersion++;
  }

  /** Saga chốt kỳ hoàn tất (kỳ {@code DA_KHOA}): lệnh chuyển {@code HOAN_TAT}. */
  public void completed() {
    move(LockOrderStatus.HOAN_TAT);
    status = LockOrderStatus.HOAN_TAT;
    pauseRequested = false;
    rowVersion++;
  }

  /** Saga đã bù trừ xong theo đề nghị huỷ đã duyệt: lệnh chuyển {@code DA_HUY}. */
  public void cancelled() {
    if (cancelRequest == null || !cancelRequest.approved()) {
      throw new IllegalStatusException(status, LockOrderStatus.DA_HUY);
    }
    move(LockOrderStatus.DA_HUY);
    status = LockOrderStatus.DA_HUY;
    pauseRequested = false;
    rowVersion++;
  }

  private void requireControllable() {
    if (!controllable()) {
      throw new IllegalStatusException(status, status);
    }
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

  public boolean pauseRequested() {
    return pauseRequested;
  }

  public UUID pauseRequestedBy() {
    return pauseRequestedBy;
  }

  public Instant pauseRequestedAt() {
    return pauseRequestedAt;
  }

  /** Đề nghị huỷ và bù trừ, {@code null} khi chưa có. */
  public LockCancelRequest cancelRequest() {
    return cancelRequest;
  }
}
