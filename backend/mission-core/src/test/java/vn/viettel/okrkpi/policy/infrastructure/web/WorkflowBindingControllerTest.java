package vn.viettel.okrkpi.policy.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.OperationPolicyCatalog;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Sáu thao tác của {@link WorkflowBindingController} gắn đúng {@code operationId} của {@code
 * policies.yaml} (CORE-05-11); vai trò lấy từ danh mục thao tác sinh từ hợp đồng nên người không có
 * {@code policy_author} bị OPA từ chối khi lưu, mô phỏng và đọc danh mục mẫu, còn {@code decider}
 * chỉ được đọc tham số (ma trận SCR-1504).
 */
class WorkflowBindingControllerTest {

  @Test
  void moiThaoTacGanDungOperationIdVaVaiTroCuaHopDong() {
    Map<String, String> actions = new TreeMap<>();
    for (Method m : WorkflowBindingController.class.getDeclaredMethods()) {
      RequiresPermission p = m.getAnnotation(RequiresPermission.class);
      if (p != null) {
        assertThat(p.module()).isEqualTo("policies");
        actions.put(p.operationId(), p.action());
      }
    }
    assertThat(actions)
        .containsOnlyKeys(
            "listWorkflowBindings",
            "getWorkflowBinding",
            "saveWorkflowBinding",
            "simulateWorkflowBinding",
            "listPolicyWorkflowTemplates",
            "getPolicyWorkflowTemplateVersion");
    assertThat(actions.get("saveWorkflowBinding")).isEqualTo("SCR-1504.cau-hinh");
    assertThat(actions.get("simulateWorkflowBinding")).isEqualTo("SCR-1504.cau-hinh");

    OperationPolicyCatalog catalog = OperationPolicyCatalog.getDefault();
    for (String op :
        Arrays.asList(
            "saveWorkflowBinding",
            "simulateWorkflowBinding",
            "listPolicyWorkflowTemplates",
            "getPolicyWorkflowTemplateVersion")) {
      assertThat(catalog.require(op).roles()).containsExactly("policy_author");
    }
    for (String op : Arrays.asList("listWorkflowBindings", "getWorkflowBinding")) {
      assertThat(catalog.require(op).roles()).containsExactlyInAnyOrder("policy_author", "decider");
    }
  }
}
