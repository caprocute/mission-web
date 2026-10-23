package vn.viettel.okrkpi.policy.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.policy.application.port.PolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kích hoạt {@code wf_binding} khi chưa có cổng của workflow-service (DV-06-08) và lớp công bố
 * CORE-05-11: chỉ ghi nhật ký, saga vẫn đi tiếp (suy luận, wiki trang 14).
 */
public class PendingPolicyWorkflowActivation implements PolicyWorkflowActivation {

  private static final Logger LOG = LoggerFactory.getLogger(PendingPolicyWorkflowActivation.class);

  @Override
  public void activate(TenantId tenant, PolicyPackVersion version) {
    LOG.info("Chưa có cổng kích hoạt luồng duyệt; bỏ qua cho phiên bản {}", version.id());
  }

  @Override
  public void deactivate(TenantId tenant, PolicyPackVersion version) {
    LOG.info("Chưa có cổng gỡ kích hoạt luồng duyệt; bỏ qua cho phiên bản {}", version.id());
  }
}
