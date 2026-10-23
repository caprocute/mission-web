package vn.viettel.okrkpi.policy.infrastructure.web;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.contract.policies.model.Flow;
import vn.viettel.okrkpi.contract.policies.model.ResolvedPolicyPack;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.application.CustomerProfileService;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code resolvePolicyPack} của {@code policies.yaml} cho client dịch vụ (SVC-07, SVC-06;
 * CORE-05-03): phân giải phiên bản hiệu lực và trả băm nội dung, khoá gói đã ký để bên gọi kiểm
 * toàn vẹn; kèm phiên bản hồ sơ tập khách hàng ghim cùng gắn nếu có (CORE-05-12).
 */
@RestController
public class PolicyResolveController {

  private final PolicyResolutionService resolution;
  private final ObjectProvider<CustomerProfileService> profiles;

  public PolicyResolveController(
      PolicyResolutionService resolution, ObjectProvider<CustomerProfileService> profiles) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
  }

  @GetMapping("/api/v1/policies/resolve")
  @RequiresPermission(operationId = "resolvePolicyPack", module = "policies")
  public ResolvedPolicyPack resolvePolicyPack(
      @RequestParam("tenant") UUID tenant,
      @RequestParam("period") UUID period,
      @RequestParam("flow") String flow,
      @RequestParam(value = "subjectGroup", required = false) UUID subjectGroup,
      @RequestParam(value = "unit", required = false) UUID unit) {
    String flowName;
    try {
      flowName = Flow.fromValue(flow).getValue();
    } catch (IllegalArgumentException e) {
      String message = "Luồng đánh giá không hợp lệ";
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          message,
          java.util.List.of(new ProblemFieldError("flow", message, null)));
    }
    TenantId tenantId = TenantId.of(tenant);
    AtomicReference<Optional<ResolvedPack>> found = new AtomicReference<>(Optional.empty());
    AtomicReference<UUID> profileVersion = new AtomicReference<>();
    CustomerProfileService profileService = profiles.getIfAvailable();
    TenantContext.run(
        tenantId,
        () -> {
          found.set(
              resolution.resolve(tenantId, PeriodId.of(period), flowName, subjectGroup, unit));
          found
              .get()
              .filter(r -> profileService != null)
              .flatMap(r -> profileService.pinnedVersion(tenantId, r.bindingId()))
              .ifPresent(profileVersion::set);
        });
    ResolvedPack r =
        found
            .get()
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-404-NO-BINDING",
                        "Chưa có bộ quy tắc đánh giá nào áp cho kỳ và luồng này"));
    return new ResolvedPolicyPack(
            r.policyPackId(),
            r.code(),
            r.versionId(),
            r.version(),
            r.contentHash(),
            r.bindingId(),
            r.frozen())
        .bundleObjectKey(r.bundleObjectKey())
        .customerProfileVersionId(profileVersion.get());
  }
}
