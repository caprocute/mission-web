package vn.viettel.okrkpi.oversight.application.alert;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AlertSignal;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * QT-14 bước 2, 3 và 10 (CORE-07-10): nhận EVT-20, tạo {@code anomaly_alert} {@code MOI} không
 * trùng, áp hành động tự động, gộp cấp đơn vị, tự đóng khi hết dấu hiệu. Chạy trong giao dịch inbox
 * của schema {@code oversight}, nên trùng theo mã sự kiện đã bị inbox chặn trước khi tới đây.
 */
public class AnomalyAlertIntake {

  private static final Logger log = LoggerFactory.getLogger(AnomalyAlertIntake.class);

  /** Người ghi của các dòng hệ thống tự tạo. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  /** Khoá cấu hình ngưỡng gộp trong {@code config.tenant_setting} (suy luận). */
  public static final String GROUP_THRESHOLD_KEY = "oversight.anomaly.group-threshold";

  public static final int DEFAULT_GROUP_THRESHOLD = 5;

  static final String NOTE_BLOCK =
      "Tạm chưa tính điểm các minh chứng liên quan cho tới khi được xác nhận hợp lệ";
  static final String NOTE_RESOLVED = "Dữ liệu nguồn không còn dấu hiệu, hệ thống tự đóng cảnh báo";
  static final String ACTION_AUTO = "TU_DONG";

  public enum Outcome {
    CREATED,
    DUPLICATE,
    RESOLVED,
    IGNORED
  }

  private final AnomalyAlertRepository alerts;
  private final PeriodApi periods;
  private final AlertHandlers handlers;
  private final TenantSettingApi settings;
  private final Clock clock;

  public AnomalyAlertIntake(
      AnomalyAlertRepository alerts,
      PeriodApi periods,
      AlertHandlers handlers,
      TenantSettingApi settings,
      Clock clock) {
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.handlers = handlers;
    this.settings = settings;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Outcome receive(TenantId tenant, AlertSignal signal) {
    AlertRules.validate(signal);
    List<String> evidence = AlertRules.sanitize(signal.evidence());
    if (evidence.size() < signal.evidence().size()) {
      log.info(
          "EVT-20 {}: bỏ {} mục chuỗi bằng chứng không phải mã",
          signal.sourceEventId(),
          signal.evidence().size() - evidence.size());
    }
    PeriodSnapshot period = period(tenant, evidence);
    int bucket = AlertRules.bucket(period.startDate(), period.endDate());
    UUID periodId = period.id().value();
    alerts.lock(
        AlertRules.dedupKey(
            tenant.value(),
            signal.signalCode(),
            signal.subjectType(),
            signal.subjectId(),
            periodId));
    Optional<AnomalyAlert> existing =
        alerts.findByKey(
            tenant,
            bucket,
            periodId,
            signal.signalCode(),
            signal.subjectType(),
            signal.subjectId());

    if (AlertRules.resolution(signal)) {
      return existing
          .filter(a -> AlertRules.MOI.equals(a.status()))
          .map(a -> close(tenant, a))
          .orElse(Outcome.IGNORED);
    }
    if (existing.isPresent() || alerts.existsBySource(tenant, bucket, signal.sourceEventId())) {
      return Outcome.DUPLICATE;
    }
    AnomalyAlert alert =
        new AnomalyAlert(
            Ids.newUuidV7(),
            periodId,
            bucket,
            signal.signalCode(),
            AlertRules.severity(signal, evidence),
            signal.subjectType(),
            signal.subjectId(),
            evidence,
            signal.detectedAt(),
            signal.sourceEventId(),
            signal.modelVersion(),
            AlertRules.MOI,
            0);
    alerts.insert(tenant, alert, SYSTEM_ACTOR);
    if (AlertRules.blocksEvidence(alert.signalCode())
        && !AlertRules.evidenceIds(evidence).isEmpty()) {
      alerts.insertAction(
          tenant, alert, ACTION_AUTO, NOTE_BLOCK, true, SYSTEM_ACTOR, clock.instant());
    }
    if (AlertRules.UNIT.equals(alert.subjectType())) {
      group(tenant, alert);
    }
    return Outcome.CREATED;
  }

  /** {@code MOI} → {@code TU_DONG_DONG} → {@code DONG}, gỡ cờ chặn (QT-14 bước 10). */
  private Outcome close(TenantId tenant, AnomalyAlert a) {
    if (!alerts.updateStatus(tenant, a, AlertRules.TU_DONG_DONG, SYSTEM_ACTOR)) {
      return Outcome.IGNORED;
    }
    alerts.insertAction(
        tenant, a, ACTION_AUTO, NOTE_RESOLVED, false, SYSTEM_ACTOR, clock.instant());
    alerts.deactivateBlocks(tenant, a, SYSTEM_ACTOR);
    alerts.updateStatus(
        tenant, a.withStatus(AlertRules.TU_DONG_DONG), AlertRules.DONG, SYSTEM_ACTOR);
    return Outcome.RESOLVED;
  }

  /**
   * Gộp: khi số cảnh báo cấp đơn vị đang mở cùng dấu hiệu, kỳ thuộc các đơn vị cấp dưới của cùng
   * một đơn vị cấp trên đạt ngưỡng, tạo (hoặc bổ sung) một cảnh báo cho đơn vị cấp trên, chuỗi bằng
   * chứng là mã các cảnh báo con (suy luận).
   */
  private void group(TenantId tenant, AnomalyAlert child) {
    if (handlers == null) {
      return;
    }
    int threshold = threshold(tenant);
    UUID parent = handlers.parentOf(tenant, child.subjectId());
    if (threshold < 2 || parent == null) {
      return;
    }
    alerts.lock(
        AlertRules.dedupKey(
            tenant.value(), child.signalCode(), AlertRules.UNIT, parent, child.periodId()));
    Collection<UUID> units = handlers.subunitsOf(tenant, parent);
    List<AnomalyAlert> open =
        alerts.openUnitAlerts(
            tenant, child.periodBucket(), child.periodId(), child.signalCode(), units);
    if (open.size() < threshold) {
      return;
    }
    List<String> codes = new ArrayList<>();
    String severity = AlertRules.SEVERITIES.contains(child.severity()) ? child.severity() : "THAP";
    for (AnomalyAlert a : open) {
      codes.add(AlertRules.CHILD_PREFIX + a.id());
      if (AlertRules.rank(a.severity()) > AlertRules.rank(severity)) {
        severity = a.severity();
      }
    }
    Optional<AnomalyAlert> group =
        alerts.findByKey(
            tenant,
            child.periodBucket(),
            child.periodId(),
            child.signalCode(),
            AlertRules.UNIT,
            parent);
    if (group.isEmpty()) {
      List<String> evidence = new ArrayList<>(codes);
      evidence.add(AlertRules.PERIOD_PREFIX + child.periodId());
      alerts.insert(
          tenant,
          new AnomalyAlert(
              Ids.newUuidV7(),
              child.periodId(),
              child.periodBucket(),
              child.signalCode(),
              severity,
              AlertRules.UNIT,
              parent,
              AlertRules.sanitize(evidence),
              clock.instant(),
              AlertRules.groupSourceId(
                  tenant.value(), child.signalCode(), parent, child.periodId()),
              child.modelVersion(),
              AlertRules.MOI,
              0),
          SYSTEM_ACTOR);
      return;
    }
    AnomalyAlert g = group.get();
    if (AlertRules.DONG.equals(g.status())) {
      return;
    }
    List<String> merged = AlertRules.sanitize(merge(g.evidence(), codes));
    String top =
        AlertRules.rank(severity) > AlertRules.rank(g.severity()) ? severity : g.severity();
    if (!merged.equals(g.evidence()) || !top.equals(g.severity())) {
      alerts.updateEvidence(
          tenant,
          new AnomalyAlert(
              g.id(),
              g.periodId(),
              g.periodBucket(),
              g.signalCode(),
              top,
              g.subjectType(),
              g.subjectId(),
              merged,
              g.detectedAt(),
              g.sourceEventId(),
              g.modelVersion(),
              g.status(),
              g.rowVersion()),
          SYSTEM_ACTOR);
    }
  }

  private int threshold(TenantId tenant) {
    if (settings == null) {
      return DEFAULT_GROUP_THRESHOLD;
    }
    return settings
        .get(tenant, GROUP_THRESHOLD_KEY, null)
        .filter(JsonNode::canConvertToInt)
        .map(JsonNode::asInt)
        .orElse(DEFAULT_GROUP_THRESHOLD);
  }

  private PeriodSnapshot period(TenantId tenant, List<String> evidence) {
    Optional<UUID> fromChain = AlertRules.period(evidence);
    Optional<PeriodSnapshot> p =
        fromChain.isPresent()
            ? periods.findById(tenant, PeriodId.of(fromChain.get()))
            : periods.findCurrent(tenant);
    return p.orElseThrow(() -> new AlertRules.InvalidSignal("Không xác định được kỳ của cảnh báo"));
  }

  static List<String> merge(List<String> a, Collection<String> b) {
    Set<String> out = new LinkedHashSet<>(a);
    out.addAll(b);
    return new ArrayList<>(out);
  }
}
