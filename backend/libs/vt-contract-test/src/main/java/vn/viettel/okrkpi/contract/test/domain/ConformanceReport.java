package vn.viettel.okrkpi.contract.test.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.contract.domain.ContractModule;

/**
 * Báo cáo kết quả kiểm thử tuân thủ hợp đồng OpenAPI cho một mô-đun và phạm vi {@code
 * x-mission.owner} (ADR-32, NEN-006).
 *
 * @param module mô-đun hợp đồng
 * @param ownerFilter mã dịch vụ {@code x-mission.owner} (ví dụ {@code SVC-05}) hoặc {@code "*"}
 * @param violations danh sách các sai lệch phát hiện
 */
public record ConformanceReport(
    ContractModule module, String ownerFilter, List<ContractViolation> violations) {

  public ConformanceReport {
    Objects.requireNonNull(module, "module must not be null");
    ownerFilter = (ownerFilter == null || ownerFilter.isBlank()) ? "*" : ownerFilter.trim();
    violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
  }

  public boolean isCompliant() {
    return violations.isEmpty();
  }

  public String summaryMessage() {
    if (violations.isEmpty()) {
      return "Mô-đun "
          + module.moduleId()
          + " (owner="
          + ownerFilter
          + ") tuân thủ hoàn toàn hợp đồng OpenAPI.";
    }
    String details =
        violations.stream()
            .map(v -> "  - " + v.format())
            .collect(Collectors.joining(System.lineSeparator()));
    return "Phát hiện "
        + violations.size()
        + " sai lệch hợp đồng OpenAPI ở mô-đun "
        + module.moduleId()
        + " (owner="
        + ownerFilter
        + "):"
        + System.lineSeparator()
        + details;
  }

  public void assertCompliant() {
    if (!isCompliant()) {
      throw new AssertionError(summaryMessage());
    }
  }
}
