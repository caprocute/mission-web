package vn.viettel.okrkpi.appraisal.application.prospect;

import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * BR-10-09 bằng CEL: định nghĩa {@code phan_nhom_hop_le} trong khối {@code phan_nhom} của Policy
 * Pack (PL 5). Phiên bản chưa có quy tắc này thì dùng biểu thức mặc định giống gói mẫu.
 */
public class ProspectEligibility {

  static final String RULE_KEY = "phan_nhom_hop_le";
  static final String DEFAULT_EXPRESSION =
      "!(nhom_chon in ['NHOM_1', 'NHOM_2'] && (xep_loai_nam == 'KHTNV' || co_bo_tri_lai))";
  static final Map<String, CelValueType> TYPES =
      Map.of(
          "nhom_chon", CelValueType.STRING,
          "xep_loai_nam", CelValueType.STRING,
          "co_bo_tri_lai", CelValueType.BOOL);

  private final CelEngine cel;
  private final PolicyQueryApi policies;

  public ProspectEligibility(CelEngine cel, PolicyQueryApi policies) {
    this.cel = cel == null ? new CelEngine() : cel;
    this.policies = policies;
  }

  /** Nhóm {@code group} có hợp lệ với người có mức năm {@code rating} và cờ bố trí lại không. */
  public boolean eligible(
      TenantId tenant, UUID policyVersionId, String group, String rating, boolean reassign) {
    return cel.evaluateBoolean(
        expression(tenant, policyVersionId),
        TYPES,
        Map.of(
            "nhom_chon", group,
            "xep_loai_nam", rating == null ? "" : rating,
            "co_bo_tri_lai", reassign));
  }

  String expression(TenantId tenant, UUID policyVersionId) {
    if (policies == null || policyVersionId == null) {
      return DEFAULT_EXPRESSION;
    }
    return policies
        .content(tenant, policyVersionId)
        .flatMap(
            c ->
                c.rules().stream()
                    .filter(r -> r.ruleKey() != null && r.ruleKey().endsWith(RULE_KEY))
                    .map(PolicyQueryApi.RuleView::expression)
                    .findFirst())
        .orElse(DEFAULT_EXPRESSION);
  }
}
