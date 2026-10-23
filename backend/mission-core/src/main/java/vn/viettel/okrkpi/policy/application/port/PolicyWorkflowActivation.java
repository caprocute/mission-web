package vn.viettel.okrkpi.policy.application.port;

import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bước 4 của saga phát hành: kích hoạt tham số luồng duyệt ({@code flowable.wf_binding}) của phiên
 * bản ở workflow-service (SVC-06, DV-06-08), và gỡ kích hoạt khi bù trừ hoặc thu hồi.
 */
public interface PolicyWorkflowActivation {

  void activate(TenantId tenant, PolicyPackVersion version);

  void deactivate(TenantId tenant, PolicyPackVersion version);
}
