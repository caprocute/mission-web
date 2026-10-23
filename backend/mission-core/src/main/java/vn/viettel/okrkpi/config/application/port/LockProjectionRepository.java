package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.LockEvent;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bản chiếu lô tính điểm, hàng đợi hồ sơ lỗi, nhật ký sự kiện và hộp lệnh của saga chốt kỳ ({@code
 * config.period_lock_batch}, {@code period_lock_failure}, {@code period_lock_event}, {@code
 * period_lock_command}). Các hàm ghi lô và lỗi dành cho bộ chiếu phản hồi saga (DV-06-06,
 * DV-06-07).
 */
public interface LockProjectionRepository {

  /** Tổng số hồ sơ theo các lô đã chiếu. */
  record BatchTotals(int batches, int profiles, int scored) {}

  /** Một lệnh trong hộp lệnh, cũng là một tác vụ dài. */
  record CommandRow(
      UUID id,
      UUID lockOrderId,
      LockCommand command,
      String status,
      UUID requestedBy,
      Instant createdAt,
      Instant updatedAt) {}

  /** Lô của lệnh, theo mã tăng dần, sau {@code afterId}; {@code status} trống là mọi trạng thái. */
  List<LockBatch> batches(
      TenantId tenantId, UUID lockOrderId, String status, UUID afterId, int fetchSize);

  BatchTotals batchTotals(TenantId tenantId, UUID lockOrderId);

  /** Hồ sơ còn trong hàng đợi lỗi, theo mã hồ sơ tăng dần, sau {@code afterProfileId}. */
  List<LockFailure> openFailures(
      TenantId tenantId, UUID lockOrderId, UUID afterProfileId, int fetchSize);

  int countOpenFailures(TenantId tenantId, UUID lockOrderId);

  /** Những mã trong {@code profileIds} còn nằm trong hàng đợi lỗi. */
  List<UUID> openFailuresAmong(TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds);

  /** Nhật ký mới nhất trước, sau cặp ({@code beforeAt}, {@code beforeId}) khi có. */
  List<LockEvent> events(
      TenantId tenantId, UUID lockOrderId, Instant beforeAt, UUID beforeId, int fetchSize);

  void appendEvent(TenantId tenantId, UUID lockOrderId, LockEvent event);

  void upsertBatch(TenantId tenantId, UUID lockOrderId, LockBatch batch);

  void upsertFailure(TenantId tenantId, UUID lockOrderId, LockFailure failure);

  /** Đưa hồ sơ ra khỏi hàng đợi lỗi (đã tính lại được). */
  int resolveFailures(TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds, Instant at);

  Optional<CommandRow> command(TenantId tenantId, UUID commandId);

  /** Kết thúc các lệnh {@code command} chưa kết thúc của lệnh khoá kỳ. */
  int finishCommands(
      TenantId tenantId, UUID lockOrderId, LockCommand command, String status, Instant at);
}
