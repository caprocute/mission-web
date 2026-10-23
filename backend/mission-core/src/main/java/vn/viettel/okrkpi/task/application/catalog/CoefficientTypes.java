package vn.viettel.okrkpi.task.application.catalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Loại hệ số mở và tập mức do Policy Pack áp cho kỳ khai báo (BS-03 DC-01), tham số {@code
 * loai_he_so}: bảng từ mã loại sang danh sách mức, hoặc danh sách mã loại (mức không giới hạn). Gói
 * không khai báo thì không có loại nào, nên mọi mức hệ số đều là lạ. Lõi không viết cứng mã loại.
 */
public class CoefficientTypes {

  public static final String KEY = "loai_he_so";
  static final java.util.List<String> FLOWS = java.util.List.of("NHA_NUOC", "DANG");

  private final PolicyQueryApi policies;

  public CoefficientTypes(PolicyQueryApi policies) {
    this.policies = policies;
  }

  /** Loại hệ số và tập mức (tập rỗng là không giới hạn) của gói áp cho kỳ. */
  public Map<String, Set<String>> declared(TenantId tenant, UUID periodId) {
    if (policies == null || periodId == null) {
      return Map.of();
    }
    for (String flow : FLOWS) {
      Optional<ResolvedPack> pack =
          policies.resolve(tenant, PeriodId.of(periodId), flow, null, null);
      if (pack.isPresent()) {
        return parse(policies.parameter(tenant, pack.get().versionId(), KEY).orElse(null));
      }
    }
    return Map.of();
  }

  /** Đọc giá trị tham số; dạng lạ là không khai báo. */
  static Map<String, Set<String>> parse(Object value) {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach(
          (k, v) -> {
            Set<String> levels = new LinkedHashSet<>();
            if (v instanceof Collection<?> list) {
              list.forEach(l -> levels.add(String.valueOf(l)));
            }
            out.put(String.valueOf(k), Set.copyOf(levels));
          });
    } else if (value instanceof Collection<?> list) {
      list.forEach(k -> out.put(String.valueOf(k), Set.of()));
    }
    return Map.copyOf(out);
  }
}
