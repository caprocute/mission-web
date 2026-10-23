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

  // ---------------------------------------------------------------- xử lý cảnh báo (CORE-07-11)

  /**
   * Bộ lọc danh sách; tập rỗng là không lọc. {@code unitId} (thu hẹp theo nhánh đơn vị) do dịch vụ
   * áp sau khi tra đối tượng.
   */
  record Filter(
      UUID periodId,
      UUID unitId,
      Collection<String> signals,
      Collection<String> severities,
      Collection<String> statuses) {}

  /** Vị trí con trỏ: thứ hạng mức (Cao trước), thời điểm phát hiện giảm dần, mã giảm dần. */
  record After(int rank, Instant detectedAt, UUID id) {}

  /** Một dòng {@code oversight.alert_action}. */
  record Action(
      UUID id,
      String action,
      UUID actor,
      UUID assignedTo,
      String verdict,
      String note,
      boolean active,
      Instant actedAt) {}

  /** Cảnh báo trong cửa sổ đếm cùng cờ đã có hành động tương ứng. */
  record Tally(AnomalyAlert alert, boolean valid, boolean assigned, boolean violation) {}

  Optional<AnomalyAlert> find(TenantId tenant, UUID id);

  List<AnomalyAlert> page(TenantId tenant, Filter filter, After after, int limit);

  List<Tally> window(TenantId tenant, Instant since, int limit);

  List<Action> actions(TenantId tenant, AnomalyAlert alert);

  /** {@code MOI} sang {@code DANG_XEM} không tăng {@code row_version}; {@code false} khi đã đổi. */
  boolean markViewed(TenantId tenant, AnomalyAlert alert, UUID actor);

  /** Ghi hành động xử lý của người dùng (không có cờ chặn). */
  void insertAction(
      TenantId tenant,
      AnomalyAlert alert,
      String action,
      UUID assignedTo,
      String verdict,
      String note,
      UUID actor,
      Instant at);

  /** Đã có yêu cầu mở lại kỳ lấy cảnh báo làm căn cứ ({@code basis_ref = CANH_BAO:<id>}). */
  boolean reopenFiled(TenantId tenant, UUID alertId);
}
