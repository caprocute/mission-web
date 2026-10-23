package vn.viettel.okrkpi.oversight.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc cảnh báo bất thường của MOD-07 (CORE-07-10): minh chứng đang bị chặn tính điểm
 * (BR-14-04, BR-09-02) và cảnh báo minh chứng trùng còn mở của kỳ cho saga chốt kỳ (QT-08).
 */
public interface AnomalyAlertQueryApi {

  /** Các minh chứng trong {@code evidenceIds} đang có cờ "tạm chưa tính điểm". */
  Set<UUID> blockedEvidence(TenantId tenant, Collection<UUID> evidenceIds);

  /** Cảnh báo dấu hiệu 4 (minh chứng trùng) chưa đóng của kỳ, tối đa {@code limit} dòng. */
  List<OpenAlert> openDuplicateEvidenceAlerts(TenantId tenant, PeriodId period, int limit);

  record OpenAlert(
      UUID alertId,
      String severity,
      String subjectType,
      UUID subjectId,
      String status,
      Instant detectedAt,
      List<UUID> evidenceIds) {}
}
