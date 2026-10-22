package vn.viettel.okrkpi.oversight.application.reopen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho yêu cầu mở lại kỳ trong bộ nhớ cho kiểm thử đơn vị. */
final class FakeReopenRepository implements ReopenRequestRepository {
  final Map<UUID, ReopenRequest> rows = new HashMap<>();
  final List<UUID> bases = new ArrayList<>();
  final Map<UUID, List<Approval>> approvals = new HashMap<>();
  final List<Integer> locked = new ArrayList<>();
  List<Adjustment> adjustments = List.of();
  Filter lastFilter;
  int writes;
  boolean failUpdate;

  @Override
  public void lockCodes(TenantId tenant, int year) {
    locked.add(year);
  }

  @Override
  public List<String> codesOfYear(TenantId tenant, int year) {
    return rows.values().stream().map(ReopenRequest::code).toList();
  }

  @Override
  public List<ReopenRequest> ofPeriod(TenantId tenant, UUID id) {
    return rows.values().stream().filter(r -> r.periodId().equals(id)).toList();
  }

  @Override
  public void insert(TenantId tenant, ReopenRequest request, UUID actorId) {
    writes++;
    rows.put(request.id(), request);
  }

  @Override
  public Optional<ReopenRequest> find(TenantId tenant, UUID id) {
    return Optional.ofNullable(rows.get(id));
  }

  @Override
  public Optional<ReopenRequest> lock(TenantId tenant, UUID id) {
    return find(tenant, id);
  }

  @Override
  public boolean update(TenantId tenant, ReopenRequest changed, int expected, UUID actorId) {
    ReopenRequest current = rows.get(changed.id());
    if (failUpdate || current == null || current.rowVersion() != expected) {
      return false;
    }
    writes++;
    rows.put(changed.id(), changed);
    return true;
  }

  @Override
  public void insertApproval(TenantId tenant, ReopenRequest request, Approval approval) {
    approvals.computeIfAbsent(request.id(), k -> new ArrayList<>()).add(approval);
  }

  @Override
  public List<ReopenRequest> approvedNotExecuted(TenantId tenant, int limit) {
    return rows.values().stream()
        .filter(r -> r.status() == ReopenStatus.DA_DUYET && r.executedAt() == null)
        .limit(limit)
        .toList();
  }

  @Override
  public boolean basisExists(TenantId tenant, UUID id, ReopenBasis basis) {
    return bases.contains(basis.id());
  }

  @Override
  public List<Adjustment> complaintAdjustments(TenantId tenant, UUID complaintId) {
    return adjustments;
  }

  @Override
  public List<Approval> approvals(TenantId tenant, int bucket, UUID requestId) {
    return List.copyOf(approvals.getOrDefault(requestId, List.of()));
  }

  @Override
  public List<ReopenRequest> page(TenantId tenant, Filter filter, After after, int fetchSize) {
    lastFilter = filter;
    return rows.values().stream()
        .filter(r -> filter.periodId() == null || r.periodId().equals(filter.periodId()))
        .filter(r -> filter.statuses().isEmpty() || filter.statuses().contains(r.status()))
        .filter(r -> filter.awaitingFor() == null || awaiting(r, filter.awaitingFor()))
        .sorted(Comparator.comparing(ReopenRequest::createdAt).reversed())
        .limit(fetchSize)
        .toList();
  }

  private static boolean awaiting(ReopenRequest r, UUID who) {
    return switch (r.status()) {
      case CHO_DUYET_1 -> who.equals(r.workflow().assignee(1));
      case CHO_DUYET_2 -> who.equals(r.workflow().assignee(2));
      default -> false;
    };
  }
}
