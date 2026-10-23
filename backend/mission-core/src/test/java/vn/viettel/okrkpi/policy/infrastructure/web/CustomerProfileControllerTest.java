package vn.viettel.okrkpi.policy.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.OperationPolicyCatalog;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Mười ba thao tác của {@link CustomerProfileController} gắn đúng {@code operationId} của {@code
 * policies.yaml} (CORE-05-12); vai trò lấy từ danh mục sinh từ hợp đồng: chỉ {@code policy_author}
 * soạn, chỉ {@code decider} ký duyệt.
 */
class CustomerProfileControllerTest {

  @Test
  void moiThaoTacGanDungOperationIdVaVaiTroCuaHopDong() {
    Set<String> operations = new TreeSet<>();
    for (Method m : CustomerProfileController.class.getDeclaredMethods()) {
      RequiresPermission p = m.getAnnotation(RequiresPermission.class);
      if (p != null) {
        assertThat(p.module()).isEqualTo("policies");
        operations.add(p.operationId());
      }
    }
    assertThat(operations)
        .containsExactlyInAnyOrder(
            "listCustomerProfiles",
            "createCustomerProfile",
            "getCustomerProfile",
            "listCustomerProfileVersions",
            "createCustomerProfileVersion",
            "getCustomerProfileVersion",
            "updateCustomerProfileVersion",
            "listParamConstraints",
            "replaceParamConstraints",
            "validateCustomerProfileVersion",
            "submitCustomerProfileVersion",
            "approveCustomerProfileVersion",
            "rejectCustomerProfileVersion");
    OperationPolicyCatalog catalog = OperationPolicyCatalog.getDefault();
    for (String op :
        Set.of(
            "createCustomerProfile",
            "createCustomerProfileVersion",
            "updateCustomerProfileVersion",
            "replaceParamConstraints",
            "validateCustomerProfileVersion",
            "submitCustomerProfileVersion")) {
      assertThat(catalog.require(op).roles()).containsExactly("policy_author");
    }
    assertThat(catalog.require("approveCustomerProfileVersion").roles()).containsExactly("decider");
    assertThat(catalog.require("listCustomerProfiles").roles())
        .containsExactlyInAnyOrder("policy_author", "tenant_admin", "decider");
  }
}
