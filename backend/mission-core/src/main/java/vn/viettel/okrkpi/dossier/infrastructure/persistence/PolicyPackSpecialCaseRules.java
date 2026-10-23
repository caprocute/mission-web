package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort.SpecialCaseRuleEntry;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRuleSource;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseRule;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng trường hợp đặc thù của gói áp cho kỳ, đọc qua cổng {@link SpecialCaseRulesPort} của {@code
 * config :: api} (MOD-05 hiện thực). Bảng {@code special_case} không có cột luồng nên đọc gói luồng
 * Đảng trước, không có thì luồng Nhà nước (suy luận); không phân giải được gói hoặc gói không khai
 * bảng thì dùng bảng mặc định của gói khối Đảng v2.0.
 */
public class PolicyPackSpecialCaseRules implements SpecialCaseRuleSource {

  static final List<String> FLOWS = List.of("DANG", "NHA_NUOC");

  private final Supplier<SpecialCaseRulesPort> port;
  private final SpecialCaseRuleSource fallback;

  public PolicyPackSpecialCaseRules(
      Supplier<SpecialCaseRulesPort> port, SpecialCaseRuleSource fallback) {
    this.port = Objects.requireNonNull(port, "port");
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  @Override
  public List<SpecialCaseRule> rules(TenantId tenant, UUID periodId) {
    SpecialCaseRulesPort p = port.get();
    if (p != null) {
      for (String flow : FLOWS) {
        Optional<List<SpecialCaseRuleEntry>> table = p.rules(tenant, PeriodId.of(periodId), flow);
        if (table.isPresent() && !table.get().isEmpty()) {
          return table.get().stream().map(PolicyPackSpecialCaseRules::rule).toList();
        }
      }
    }
    return fallback.rules(tenant, periodId);
  }

  static SpecialCaseRule rule(SpecialCaseRuleEntry e) {
    return new SpecialCaseRule(
        e.code(),
        e.section(),
        e.legalRef(),
        e.handling(),
        e.effect(),
        e.effectWhenInsufficient(),
        e.params());
  }
}
