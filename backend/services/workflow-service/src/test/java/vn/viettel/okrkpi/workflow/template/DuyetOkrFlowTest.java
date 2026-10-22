package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/** Kiểm thử Flowable của mẫu {@code duyet-okr} (QT-02, SCR-0203). */
class DuyetOkrFlowTest extends ApprovalTemplateFlowTest {

  @Override
  ApprovalTemplate template() {
    return ApprovalTemplate.DUYET_OKR;
  }

  /** DMN r5: {@code leader} của đơn vị cấp trên trực tiếp (cây Đảng: chi bộ). */
  @Override
  UUID defaultUnit(ApprovalEngineHarness.Tree tree) {
    return tree.chiBo();
  }

  @Override
  int defaultWorkingDays() {
    return 3;
  }

  @Override
  void assertTemplateFields(CoreApprovalGateway.Request request) {
    assertThat(request.planVersionNo()).isNull();
    assertThat(request.taskId()).isNull();
  }
}
