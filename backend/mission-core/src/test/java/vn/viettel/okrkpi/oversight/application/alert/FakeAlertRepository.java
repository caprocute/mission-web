package vn.viettel.okrkpi.oversight.application.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AlertWorkflow;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho cảnh báo trong bộ nhớ theo hành vi của {@code JdbcAnomalyAlertRepository}. */
final class FakeAlertRepository implements AnomalyAlertRepository {

  record Action(
      UUID alertId,
      String action,
      String note,
      boolean active,
      UUID actor,
      UUID assignedTo,
      String verdict) {
    Action(UUID alertId, String action, String note, boolean active) {
      this(alertId, action, note, active, null, null, null);
    }
  }

  final Map<UUID, AnomalyAlert> rows = new LinkedHashMap<>();
  final List<Action> actions = new ArrayList<>();
  final List<String> locks = new ArrayList<>();

  List<AnomalyAlert> all() {
    return List.copyOf(rows.values());
  }

  @Override
  public void lock(String key) {
    locks.add(key);
  }

  @Override
  public Optional<AnomalyAlert> findByKey(
      TenantId tenant, int bucket, UUID periodId, String signal, String type, UUID subject) {
    return rows.values().stream()
        .filter(
            a ->
                a.periodId().equals(periodId)
                    && a.signalCode().equals(signal)
                    && a.subjectType().equals(type)
                    && a.subjectId().equals(subject))
        .findFirst();
  }

  @Override
  public boolean existsBySource(TenantId tenant, int bucket, UUID sourceEventId) {
    return rows.values().stream().anyMatch(a -> a.sourceEventId().equals(sourceEventId));
  }

  @Override
  public void insert(TenantId tenant, AnomalyAlert alert, UUID actor) {
    rows.put(alert.id(), alert);
  }

  @Override
  public void updateEvidence(TenantId tenant, AnomalyAlert alert, UUID actor) {
    rows.put(
        alert.id(),
        new AnomalyAlert(
            alert.id(),
            alert.periodId(),
            alert.periodBucket(),
            alert.signalCode(),
            alert.severity(),
            alert.subjectType(),
            alert.subjectId(),
            alert.evidence(),
            alert.detectedAt(),
            alert.sourceEventId(),
            alert.modelVersion(),
            alert.status(),
            alert.rowVersion() + 1));
  }

  @Override
  public boolean updateStatus(TenantId tenant, AnomalyAlert alert, String status, UUID actor) {
    AnomalyAlert cur = rows.get(alert.id());
    if (cur == null || cur.rowVersion() != alert.rowVersion()) {
      return false;
    }
    rows.put(alert.id(), cur.withStatus(status));
    return true;
  }

  @Override
  public void insertAction(
      TenantId tenant,
      AnomalyAlert alert,
      String action,
      String note,
      boolean active,
      UUID actor,
      Instant at) {
    actions.add(new Action(alert.id(), action, note, active, actor, null, null));
  }

  @Override
  public int deactivateBlocks(TenantId tenant, AnomalyAlert alert, UUID actor) {
    int n = 0;
    for (int i = 0; i < actions.size(); i++) {
      Action a = actions.get(i);
      if (a.alertId().equals(alert.id()) && a.active()) {
        actions.set(
            i,
            new Action(
                a.alertId(), a.action(), a.note(), false, a.actor(), a.assignedTo(), a.verdict()));
        n++;
      }
    }
    return n;
  }

  @Override
  public List<AnomalyAlert> openUnitAlerts(
      TenantId tenant, int bucket, UUID periodId, String signal, Collection<UUID> units) {
    return rows.values().stream()
        .filter(
            a ->
                a.periodId().equals(periodId)
                    && a.signalCode().equals(signal)
                    && a.subjectType().equals(AlertRules.UNIT)
                    && !Set.of(AlertRules.DONG, AlertRules.TU_DONG_DONG).contains(a.status())
                    && units.contains(a.subjectId()))
        .toList();
  }

  @Override
  public Set<UUID> blockedEvidence(TenantId tenant, Collection<UUID> evidenceIds) {
    Set<UUID> out = new HashSet<>();
    for (Action x : actions) {
      if (x.active()) {
        for (UUID id : AlertRules.evidenceIds(rows.get(x.alertId()).evidence())) {
          if (evidenceIds.contains(id)) {
            out.add(id);
          }
        }
      }
    }
    return out;
  }

  @Override
  public List<AnomalyAlert> openAlerts(TenantId tenant, UUID periodId, String signal, int limit) {
    return rows.values().stream()
        .filter(a -> a.periodId().equals(periodId) && a.signalCode().equals(signal))
        .filter(a -> !Set.of(AlertRules.DONG, AlertRules.TU_DONG_DONG).contains(a.status()))
        .limit(limit)
        .toList();
  }

  @Override
  public List<AnomalyAlert> pendingHighDigest(TenantId tenant, String note, int limit) {
    return rows.values().stream()
        .filter(a -> a.severity().equals("CAO") && a.status().equals(AlertRules.MOI))
        .filter(
            a ->
                actions.stream()
                    .noneMatch(x -> x.alertId().equals(a.id()) && note.equals(x.note())))
        .limit(limit)
        .toList();
  }

  final Set<UUID> reopened = new HashSet<>();
  int viewed;

  @Override
  public Optional<AnomalyAlert> find(TenantId tenant, UUID id) {
    return Optional.ofNullable(rows.get(id));
  }

  @Override
  public List<AnomalyAlert> page(TenantId tenant, Filter f, After after, int limit) {
    return rows.values().stream()
        .filter(a -> f.periodId() == null || a.periodId().equals(f.periodId()))
        .filter(a -> f.signals().isEmpty() || f.signals().contains(a.signalCode()))
        .filter(a -> f.severities().isEmpty() || f.severities().contains(a.severity()))
        .filter(a -> f.statuses().isEmpty() || f.statuses().contains(a.status()))
        .sorted(
            java.util.Comparator.comparingInt((AnomalyAlert a) -> AlertWorkflow.order(a.severity()))
                .thenComparing(AnomalyAlert::detectedAt, java.util.Comparator.reverseOrder())
                .thenComparing(AnomalyAlert::id, java.util.Comparator.reverseOrder()))
        .filter(a -> after == null || afterKey(a, after))
        .limit(limit)
        .toList();
  }

  private static boolean afterKey(AnomalyAlert a, After k) {
    int r = AlertWorkflow.order(a.severity());
    if (r != k.rank()) {
      return r > k.rank();
    }
    int c = a.detectedAt().compareTo(k.detectedAt());
    return c < 0 || (c == 0 && a.id().compareTo(k.id()) < 0);
  }

  @Override
  public List<Tally> window(TenantId tenant, Instant since, int limit) {
    return rows.values().stream()
        .filter(a -> !a.detectedAt().isBefore(since))
        .map(
            a ->
                new Tally(
                    a,
                    has(a, "XAC_NHAN_HOP_LE", null),
                    has(a, "GIAO_KIEM_TRA", null),
                    has(a, "KET_LUAN", "CO_VI_PHAM")))
        .limit(limit)
        .toList();
  }

  private boolean has(AnomalyAlert a, String action, String verdict) {
    return actions.stream()
        .anyMatch(
            x ->
                x.alertId().equals(a.id())
                    && x.action().equals(action)
                    && (verdict == null || verdict.equals(x.verdict())));
  }

  @Override
  public List<AnomalyAlertRepository.Action> actions(TenantId tenant, AnomalyAlert a) {
    return actions.stream()
        .filter(x -> x.alertId().equals(a.id()))
        .map(
            x ->
                new AnomalyAlertRepository.Action(
                    UUID.randomUUID(),
                    x.action(),
                    x.actor(),
                    x.assignedTo(),
                    x.verdict(),
                    x.note(),
                    x.active(),
                    Instant.parse("2026-11-03T19:05:00Z")))
        .toList();
  }

  @Override
  public boolean markViewed(TenantId tenant, AnomalyAlert a, UUID actor) {
    AnomalyAlert cur = rows.get(a.id());
    if (!AlertRules.MOI.equals(cur.status())) {
      return false;
    }
    viewed++;
    rows.put(
        a.id(),
        new AnomalyAlert(
            cur.id(),
            cur.periodId(),
            cur.periodBucket(),
            cur.signalCode(),
            cur.severity(),
            cur.subjectType(),
            cur.subjectId(),
            cur.evidence(),
            cur.detectedAt(),
            cur.sourceEventId(),
            cur.modelVersion(),
            "DANG_XEM",
            cur.rowVersion()));
    return true;
  }

  @Override
  public void insertAction(
      TenantId tenant,
      AnomalyAlert alert,
      String action,
      UUID assignedTo,
      String verdict,
      String note,
      UUID actor,
      Instant at) {
    actions.add(new Action(alert.id(), action, note, false, actor, assignedTo, verdict));
  }

  @Override
  public boolean reopenFiled(TenantId tenant, UUID alertId) {
    return reopened.contains(alertId);
  }
}
