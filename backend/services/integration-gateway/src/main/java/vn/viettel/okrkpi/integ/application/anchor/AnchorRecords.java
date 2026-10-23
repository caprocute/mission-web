package vn.viettel.okrkpi.integ.application.anchor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Theo dõi ngày cần neo và bản neo từng đích ({@code integ.anchor_window}, {@code
 * anchor_dispatch}).
 */
public interface AnchorRecords {

  record Window(UUID id, String cellCode, Instant windowEnd, int attempts) {}

  record PendingReceipt(
      UUID id,
      UUID cellSealId,
      AnchorTarget target,
      String connectorCode,
      byte[] receipt,
      Instant receivedAt,
      int relayAttempts) {}

  /** Ngày đã qua hạn chờ, chưa kiểm; {@code receipts} là số đích đã trả biên nhận. */
  record Unchecked(UUID id, String cellCode, Instant windowEnd, boolean fannedOut, int receipts) {}

  /** Ghi ngày cần neo; {@code false} khi đã có (EVT-19 của tenant khác cùng ngày, giao lại). */
  boolean registerWindow(
      TenantId tenant, UUID id, String cellCode, Instant windowEnd, UUID sourceEventId);

  /** Ngày đến hạn hỏi gói neo, khoá dòng (bỏ qua dòng pod khác đang giữ). */
  List<Window> lockDueWindows(TenantId tenant, Instant now, int limit);

  void postponeWindow(TenantId tenant, UUID windowId, int attempts, Instant next);

  void windowFannedOut(TenantId tenant, UUID windowId, UUID cellSealId, Instant at);

  void insertDispatch(
      TenantId tenant,
      UUID id,
      UUID windowId,
      UUID cellSealId,
      AnchorTarget target,
      UUID connectorId,
      UUID outboundMessageId);

  /** Đích của thông điệp ra {@code messageId}; rỗng khi thông điệp không phải bản neo. */
  Optional<UUID> dispatchOf(TenantId tenant, UUID messageId);

  /** Ghi biên nhận lần đầu; lần nhận sau giữ biên nhận cũ. */
  void recordReceipt(TenantId tenant, UUID dispatchId, byte[] receipt, Instant at);

  /** Biên nhận chưa chuyển cho ledger-service, khoá dòng. */
  List<PendingReceipt> lockPendingReceipts(TenantId tenant, int limit);

  void receiptRelayed(TenantId tenant, UUID dispatchId, Instant at);

  void relayFailed(TenantId tenant, UUID dispatchId, int attempts);

  /** Ngày có cuối ngày trước {@code before} chưa kiểm số biên nhận, khoá dòng. */
  List<Unchecked> lockUnchecked(TenantId tenant, Instant before, int limit);

  void windowChecked(TenantId tenant, UUID windowId, Instant at);
}
