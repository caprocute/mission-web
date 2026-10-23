package vn.viettel.okrkpi.oversight.application.alert;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.port.AlertNotices;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * QT-14 bước 2, MSG-14-01: sau đợt job đêm, mỗi người xử lý trong phạm vi nhận một EVT-21 với số
 * cảnh báo mức Cao mới, không nhận từng cảnh báo. Cảnh báo đã tính được đánh dấu bằng một hành động
 * tự động nên lần chạy sau không đếm lại.
 */
public class AnomalyDigest {

  static final String NOTE_NOTIFIED = "Đã gửi thông báo tổng hợp cho người xử lý";
  static final int BATCH = 2000;

  private final AnomalyAlertRepository alerts;
  private final AlertHandlers handlers;
  private final AlertNotices notices;
  private final Clock clock;

  public AnomalyDigest(
      AnomalyAlertRepository alerts, AlertHandlers handlers, AlertNotices notices, Clock clock) {
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.handlers = handlers;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Trả số người được thông báo. Gọi trong giao dịch của tenant. */
  public int run(TenantId tenant, UUID batchId) {
    List<AnomalyAlert> pending = alerts.pendingHighDigest(tenant, NOTE_NOTIFIED, BATCH);
    Map<UUID, Integer> counts = new TreeMap<>();
    for (AnomalyAlert a : pending) {
      for (UUID r : recipients(tenant, a)) {
        counts.merge(r, 1, Integer::sum);
      }
      alerts.insertAction(
          tenant,
          a,
          AnomalyAlertIntake.ACTION_AUTO,
          NOTE_NOTIFIED,
          false,
          AnomalyAlertIntake.SYSTEM_ACTOR,
          clock.instant());
    }
    counts.forEach((r, n) -> notices.newHighAlerts(tenant, r, n, batchId));
    return counts.size();
  }

  /**
   * Người thẩm định của hồ sơ (cảnh báo cá nhân) hoặc của đơn vị (cảnh báo cấp đơn vị). Cảnh báo về
   * người duyệt chưa tra được phạm vi nên không có người nhận ở đây (suy luận, wiki trang 14).
   */
  private Set<UUID> recipients(TenantId tenant, AnomalyAlert a) {
    if (handlers == null) {
      return Set.of();
    }
    PeriodId period = PeriodId.of(a.periodId());
    return switch (a.subjectType()) {
      case "CA_NHAN" -> handlers.ofProfiles(tenant, period, List.of(a.subjectId()));
      case AlertRules.UNIT -> handlers.ofUnits(tenant, period, List.of(a.subjectId()));
      default -> Set.of();
    };
  }
}
