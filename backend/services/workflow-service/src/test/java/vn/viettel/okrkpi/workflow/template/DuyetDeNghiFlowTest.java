package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/** Kiểm thử Flowable của mẫu {@code duyet-de-nghi} (QT-06, SCR-0601, đề nghị vượt ngưỡng). */
class DuyetDeNghiFlowTest extends ApprovalTemplateFlowTest {

  @Override
  ApprovalTemplate template() {
    return ApprovalTemplate.DUYET_DE_NGHI;
  }

  /** DMN r8: {@code leader} (AC-04) của chính đơn vị, xử lý trong 2 ngày làm việc. */
  @Override
  UUID defaultUnit(ApprovalEngineHarness.Tree tree) {
    return tree.banA();
  }

  @Override
  int defaultWorkingDays() {
    return 2;
  }

  @Override
  void assertTemplateFields(CoreApprovalGateway.Request request) {
    assertThat(request.taskId()).isEqualTo(taskId);
    assertThat(request.cause()).isEqualTo("KHACH_QUAN");
  }
}
