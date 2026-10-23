package vn.viettel.okrkpi.task.application.fromplan.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code task.plan_item_confirmation} (bổ sung, CORE-02-11). */
public interface LeadConfirmations {

  /**
   * Một dòng kế hoạch kèm đơn vị lập, đơn vị chủ trì, trạng thái phiên bản và xác nhận hiện có
   * ({@code null} khi chưa xác nhận; dòng ở phiên bản sau thừa hưởng xác nhận của dòng gốc cùng đơn
   * vị chủ trì).
   */
  record LineRow(
      UUID itemId,
      int periodBucket,
      int itemNo,
      UUID ownerUnitId,
      UUID leadUnitId,
      String versionStatus,
      String confirmation) {}

  List<LineRow> lines(TenantId tenant, Collection<UUID> itemIds);

  Optional<LineRow> line(TenantId tenant, UUID itemId);

  /** Ghi (hoặc ghi đè) xác nhận của đơn vị chủ trì cho dòng. */
  void record(TenantId tenant, LineRow line, String status, String reason, UUID actor, Instant at);
}
