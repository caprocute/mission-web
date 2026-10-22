package vn.viettel.okrkpi.config.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.PeriodConflictException;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho kỳ trong bộ nhớ cho kiểm thử đơn vị (không có RLS, không có ràng buộc CSDL). */
public class InMemoryPeriodRepository implements PeriodRepository {

  private final Map<UUID, Period> rows = new LinkedHashMap<>();
  private final Map<UUID, Integer> versions = new LinkedHashMap<>();
  private final Set<UUID> announced = new HashSet<>();
  public final List<PeriodTransition> statusLog = new ArrayList<>();

  @Override
  public void insert(Period p) {
    if (rows.values().stream().anyMatch(o -> o.tenantId().equals(p.tenantId()) && o.overlaps(p))) {
      throw new PeriodConflictException(PeriodConflictException.Kind.OVERLAP, null);
    }
    rows.put(p.id().value(), p);
    versions.put(p.id().value(), p.rowVersion());
  }

  /** Đặt thẳng một kỳ (bỏ qua kiểm tra), dùng để dựng tình huống. */
  public void put(Period p) {
    rows.put(p.id().value(), p);
    versions.put(p.id().value(), p.rowVersion());
  }

  @Override
  public boolean updateState(Period p, int expectedVersion, UUID actorId) {
    Integer current = versions.get(p.id().value());
    if (current == null || current != expectedVersion) {
      return false;
    }
    rows.put(p.id().value(), p);
    versions.put(p.id().value(), p.rowVersion());
    return true;
  }

  @Override
  public void appendStatusLog(TenantId tenantId, PeriodTransition transition) {
    statusLog.add(transition);
  }

  @Override
  public Optional<Period> findById(TenantId tenantId, PeriodId periodId) {
    return Optional.ofNullable(rows.get(periodId.value()))
        .filter(p -> p.tenantId().equals(tenantId));
  }

  @Override
  public boolean existsById(TenantId tenantId, PeriodId periodId) {
    return findById(tenantId, periodId).isPresent();
  }

  @Override
  public boolean overlaps(
      TenantId tenantId, PeriodKind kind, PeriodFlow flow, LocalDate start, LocalDate end) {
    return of(tenantId).stream()
        .anyMatch(
            p ->
                p.kind() == kind
                    && p.flow() == flow
                    && !p.startDate().isAfter(end)
                    && !start.isAfter(p.endDate()));
  }

  @Override
  public List<Period> covering(TenantId tenantId, PeriodKind kind, LocalDate day) {
    return of(tenantId).stream()
        .filter(p -> (kind == null || p.kind() == kind) && p.covers(day))
        .toList();
  }

  @Override
  public List<Period> startingBetween(TenantId tenantId, LocalDate from, LocalDate to) {
    return of(tenantId).stream()
        .filter(p -> !p.startDate().isBefore(from) && !p.startDate().isAfter(to))
        .toList();
  }

  @Override
  public List<Period> list(TenantId tenantId, Filter f, Keyset after, int fetchSize) {
    Comparator<Period> order =
        Comparator.comparing(Period::startDate).thenComparing(p -> p.id().value()).reversed();
    return of(tenantId).stream()
        .filter(p -> f == null || f.kind() == null || p.kind() == f.kind())
        .filter(p -> f == null || f.flow() == null || p.flow() == f.flow())
        .filter(p -> f == null || f.state() == null || p.state() == f.state())
        .filter(p -> f == null || f.year() == null || p.bucket() / 10 == f.year())
        .filter(
            p ->
                after == null
                    || p.startDate().isBefore(after.startDate())
                    || (p.startDate().equals(after.startDate())
                        && p.id().value().compareTo(after.id()) < 0))
        .sorted(order)
        .limit(fetchSize)
        .toList();
  }

  @Override
  public boolean markOpeningAnnounced(TenantId tenantId, PeriodId periodId) {
    return announced.add(periodId.value());
  }

  private List<Period> of(TenantId tenantId) {
    return rows.values().stream().filter(p -> p.tenantId().equals(tenantId)).toList();
  }
}
