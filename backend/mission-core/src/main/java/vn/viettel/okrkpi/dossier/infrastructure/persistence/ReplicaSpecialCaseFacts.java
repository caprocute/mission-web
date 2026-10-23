package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseFactsSource;
import vn.viettel.okrkpi.dossier.domain.specialcase.AssignmentFact;
import vn.viettel.okrkpi.dossier.domain.specialcase.PersonPeriodFacts;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dữ kiện phân công từ bản sao tổ chức ({@code replica :: api}). Cổng chỉ cho phân công hiệu lực
 * tại một ngày nên bộ gom đọc ngày đầu, ngày cuối kỳ, rồi lần ngược ngày trước mỗi phân công mới
 * bắt đầu trong vòng 12 tháng và ngày sau mỗi phân công kết thúc trong kỳ, tối đa {@value
 * #MAX_LOOKUPS} lần đọc. Dữ kiện nhân sự (nghỉ dài ngày, kỷ luật, xác minh, tổ chức lại đơn vị,
 * đảng viên ngoài hệ thống chính trị, đơn vị phụ trách KHTNV) chưa có nguồn trong mission-core nên
 * để trống; bean khác thay bean này khi có nguồn.
 */
public class ReplicaSpecialCaseFacts implements SpecialCaseFactsSource {

  static final int MAX_LOOKUPS = 12;

  private final Supplier<OrgDirectoryApi> directory;

  public ReplicaSpecialCaseFacts(Supplier<OrgDirectoryApi> directory) {
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  @Override
  public PersonPeriodFacts collect(
      TenantId tenant,
      UUID personId,
      LocalDate start,
      LocalDate end,
      List<AssignmentFact> overlay) {
    Map<UUID, AssignmentFact> byId = new LinkedHashMap<>();
    OrgDirectoryApi org = directory.get();
    if (org != null) {
      Deque<LocalDate> days = new ArrayDeque<>(List.of(start, end));
      Set<LocalDate> seen = new HashSet<>();
      LocalDate horizon = end.minusMonths(12);
      while (!days.isEmpty() && seen.size() < MAX_LOOKUPS) {
        LocalDate day = days.poll();
        if (!seen.add(day)) {
          continue;
        }
        for (AssignmentView v : org.assignmentsOf(tenant, personId, day)) {
          AssignmentFact a = fact(v);
          if (byId.putIfAbsent(a.id(), a) != null) {
            continue;
          }
          if (a.validFrom().isAfter(horizon) && !a.validFrom().isAfter(end)) {
            days.add(a.validFrom().minusDays(1));
          }
          if (a.validTo() != null && !a.validTo().isBefore(start) && a.validTo().isBefore(end)) {
            days.add(a.validTo().plusDays(1));
          }
        }
      }
    }
    for (AssignmentFact a : overlay) {
      byId.put(a.id(), a);
    }
    return new PersonPeriodFacts(
        personId, start, end, List.copyOf(byId.values()), PersonPeriodFacts.Supplement.NONE);
  }

  static AssignmentFact fact(AssignmentView v) {
    return new AssignmentFact(
        v.id(), v.orgUnitId(), v.kind(), v.head(), v.validFrom(), v.validTo());
  }
}
