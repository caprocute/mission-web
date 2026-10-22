package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/** Kiểm thử Flowable của mẫu {@code duyet-ke-hoach-quy} (QT-04, SCR-0403). */
class DuyetKeHoachQuyFlowTest extends ApprovalTemplateFlowTest {

  @Override
  ApprovalTemplate template() {
    return ApprovalTemplate.DUYET_KE_HOACH_QUY;
  }

  /** DMN r6: {@code leader} của chính đơn vị. */
  @Override
  UUID defaultUnit(ApprovalEngineHarness.Tree tree) {
    return tree.banA();
  }

  @Override
  int defaultWorkingDays() {
    return 3;
  }

  @Override
  void assertTemplateFields(CoreApprovalGateway.Request request) {
    assertThat(request.planVersionNo()).isEqualTo(2);
    assertThat(request.signMethod()).isEqualTo("USB_TOKEN");
  }
}
