package vn.viettel.okrkpi.policy.infrastructure.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.contract.policies.model.Flow;
import vn.viettel.okrkpi.contract.policies.model.ResolvedPolicyPack;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPolicyPackView;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code resolvePolicyPack} của {@code policies.yaml} cho client nội bộ (SVC-07, SVC-06, MOD-04;
 * CORE-05-03, QT-09, QT-15): phiên bản gói áp cho tenant, kỳ, luồng, khối, đơn vị kèm mã băm và
 * khoá gói đã ký để bên gọi tự kiểm toàn vẹn (X-PP-09). Không có gói trả 404 {@code
 * POLICY-404-NO-BINDING}.
 */
@RestController
public class PolicyResolveController {

  private final PolicyQueryApi policies;

  public PolicyResolveController(PolicyQueryApi policies) {
    this.policies = Objects.requireNonNull(policies, "policies");
  }

  @GetMapping("/api/v1/policies/resolve")
  @RequiresPermission(operationId = "resolvePolicyPack", module = "policies")
  public ResolvedPolicyPack resolvePolicyPack(
      @RequestParam("tenant") UUID tenant,
      @RequestParam("period") UUID period,
      @RequestParam("flow") String flow,
      @RequestParam(value = "subjectGroup", required = false) UUID subjectGroup,
      @RequestParam(value = "unit", required = false) UUID unit) {
    String flowName = flow(flow);
    TenantId tenantId = TenantId.of(tenant);
    List<Optional<ResolvedPolicyPackView>> found = new ArrayList<>(1);
    TenantContext.run(
        tenantId,
        () ->
            found.add(
                policies.resolve(tenantId, PeriodId.of(period), flowName, subjectGroup, unit)));
    ResolvedPolicyPackView v =
        found
            .get(0)
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-404-NO-BINDING",
                        "Chưa có bộ quy tắc đánh giá áp dụng cho kỳ và phạm vi này"));
    return new ResolvedPolicyPack(
            v.policyPackId(),
            v.code(),
            v.versionId(),
            v.version(),
            v.contentHash() == null ? "" : v.contentHash(),
            v.bindingId(),
            v.frozen())
        .bundleObjectKey(v.bundleObjectKey());
  }

  private static String flow(String value) {
    try {
      return Flow.fromValue(value).getValue();
    } catch (IllegalArgumentException e) {
      String message = "Giá trị lọc không hợp lệ";
      throw new BusinessException(
          "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError("flow", message, null)));
    }
  }
}
