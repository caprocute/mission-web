package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho {@code oversight.anomaly_alert} và {@code oversight.alert_action} (CORE-07-10). Mọi lời gọi
 * chạy trong giao dịch của người gọi, trong ngữ cảnh RLS của tenant.
 */
public interface AnomalyAlertRepository {

  /** Khoá tư vấn theo giao dịch cho một khoá chống trùng. */
  void lock(String key);

  /** Cảnh báo cùng dấu hiệu, đối tượng, kỳ ở mọi trạng thái. */
  Optional<AnomalyAlert> findByKey(
      TenantId tenant,
      int bucket,
      UUID periodId,
      String signalCode,
      String subjectType,
      UUID subjectId);

  boolean existsBySource(TenantId tenant, int bucket, UUID sourceEventId);

  void insert(TenantId tenant, AnomalyAlert alert, UUID actor);

  /** Thay chuỗi bằng chứng và mức (cảnh báo gộp nhận thêm cảnh báo con). */
  void updateEvidence(TenantId tenant, AnomalyAlert alert, UUID actor);

  /** Đổi trạng thái theo {@code row_version}; {@code false} khi dòng đã đổi. */
  boolean updateStatus(TenantId tenant, AnomalyAlert alert, String status, UUID actor);

  /** Ghi một hành động; {@code active} chỉ cho hành động tự động có cờ chặn. */
  void insertAction(
      TenantId tenant,
      AnomalyAlert alert,
      String action,
      String note,
      boolean active,
      UUID actor,
      Instant at);

  /** Gỡ mọi cờ chặn còn hiệu lực của cảnh báo. */
  int deactivateBlocks(TenantId tenant, AnomalyAlert alert, UUID actor);

  /** Cảnh báo cấp đơn vị đang mở của một dấu hiệu, kỳ có đối tượng trong {@code units}. */
  List<AnomalyAlert> openUnitAlerts(
      TenantId tenant, int bucket, UUID periodId, String signalCode, Collection<UUID> units);

  /** Minh chứng (trong {@code evidenceIds}) đang có cờ chặn còn hiệu lực. */
  Set<UUID> blockedEvidence(TenantId tenant, Collection<UUID> evidenceIds);

  /** Cảnh báo đang mở của một dấu hiệu trong kỳ (mọi trạng thái trừ {@code DONG}). */
  List<AnomalyAlert> openAlerts(TenantId tenant, UUID periodId, String signalCode, int limit);

  /** Cảnh báo mức Cao trạng thái {@code MOI} chưa có hành động thông báo tổng hợp. */
  List<AnomalyAlert> pendingHighDigest(TenantId tenant, String digestNote, int limit);
}
