package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Gói con diện TW gửi qua EdXML ({@code dossier.package_transfer}, CORE-06-08). */
public interface PackageTransferRepository {

  /** Trạng thái chưa kết thúc: còn gửi, chờ biên nhận hoặc chờ thử lại. */
  List<String> OPEN = List.of("CHUA_GUI", "CHO", "DA_GUI", "LOI");

  /** Một gói con. */
  record TransferRow(
      UUID id,
      int periodBucket,
      UUID packageId,
      UUID batchId,
      UUID profileId,
      UUID personId,
      String destination,
      String status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef,
      Instant acknowledgedAt,
      String lastError,
      int notifiedAttempts,
      UUID requestedBy,
      Instant updatedAt) {

    /** Lỗi không còn lịch thử lại (thư chết ở SVC-13 hoặc MOD-06 từ chối gửi). */
    public boolean deadEnd() {
      return "HUY".equals(status) || "LOI".equals(status) && nextAttemptAt == null;
    }
  }

  /** Gói con mới ở {@code CHUA_GUI}. */
  record NewTransfer(UUID id, UUID profileId, UUID personId) {}

  void insert(
      TenantId tenant,
      int periodBucket,
      UUID packageId,
      UUID batchId,
      String destination,
      Collection<NewTransfer> transfers,
      UUID actor);

  Optional<TransferRow> find(TenantId tenant, UUID id);

  List<TransferRow> ofPackage(TenantId tenant, UUID packageId);

  List<TransferRow> ofBatch(TenantId tenant, UUID batchId);

  /** Gói con chưa kết thúc, cũ nhất trước, không đổi trong {@code quiet} gần đây. */
  List<TransferRow> open(TenantId tenant, Duration quiet, int limit);

  /**
   * Chép trạng thái SVC-13 (bỏ qua khi gói con đã kết thúc). Trả {@code false} khi không có gì thay
   * đổi.
   */
  boolean applyRemote(TenantId tenant, UUID id, EdxmlGateway.RemoteState state);

  /** MOD-06 kết thúc gói con: {@code HUY} hoặc {@code LOI} không thử lại, kèm lý do. */
  void close(TenantId tenant, UUID id, String status, String reason);

  /**
   * Ghi đã thông báo lần lỗi {@code attempts}; {@code false} khi lần này đã được thông báo (pod
   * khác, lượt trước).
   */
  boolean markNotified(TenantId tenant, UUID id, int attempts);
}
