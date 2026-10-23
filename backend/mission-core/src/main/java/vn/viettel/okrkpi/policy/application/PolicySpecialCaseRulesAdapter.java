package vn.viettel.okrkpi.policy.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.config.api.SpecialCaseRulesPort;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực cổng {@link SpecialCaseRulesPort} của MOD-08 (đảo phụ thuộc, CORE-06-05): phân giải
 * phiên bản gói cho kỳ và luồng ở gắn toàn tenant rồi đọc khối {@code truong_hop_dac_thu} của
 * manifest. Dòng thiếu {@code ma}, {@code legal_ref} hoặc {@code hieu_ung} bị bỏ qua.
 */
public class PolicySpecialCaseRulesAdapter implements SpecialCaseRulesPort {

  static final String KEY = "truong_hop_dac_thu";

  private final PolicyResolutionService resolution;

  public PolicySpecialCaseRulesAdapter(PolicyResolutionService resolution) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
  }

  @Override
  public Optional<List<SpecialCaseRuleEntry>> rules(
      TenantId tenantId, PeriodId periodId, String flow) {
    return resolution
        .resolve(tenantId, periodId, flow, null, null)
        .flatMap(p -> resolution.manifest(tenantId, p.versionId()))
        .map(m -> m.get(KEY))
        .filter(List.class::isInstance)
        .map(t -> entries((List<?>) t));
  }

  static List<SpecialCaseRuleEntry> entries(List<?> table) {
    List<SpecialCaseRuleEntry> out = new ArrayList<>();
    for (Object row : table) {
      if (!(row instanceof Map<?, ?> m)
          || m.get("ma") == null
          || m.get("legal_ref") == null
          || m.get("hieu_ung") == null) {
        continue;
      }
      Map<String, Object> params = new LinkedHashMap<>();
      if (m.get("tham_so") instanceof Map<?, ?> p) {
        p.forEach((k, v) -> params.put(String.valueOf(k), v));
      }
      out.add(
          new SpecialCaseRuleEntry(
              String.valueOf(m.get("ma")),
              m.get("muc") instanceof Number n ? n.intValue() : 0,
              String.valueOf(m.get("legal_ref")),
              m.get("cach_xu_ly") == null ? null : String.valueOf(m.get("cach_xu_ly")),
              String.valueOf(m.get("hieu_ung")),
              m.get("hieu_ung_khi_khong_du") == null
                  ? null
                  : String.valueOf(m.get("hieu_ung_khi_khong_du")),
              params));
    }
    return out;
  }
}
