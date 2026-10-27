package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * QA-03 HT-08: duyệt, duyệt hàng loạt và trả lại OKR gửi kèm khoá chức năng SCR-0203 cho OPA, để
 * quyền POST của module okr (AC-05, AC-06 cũng có) không đủ để duyệt; ca OPA tương ứng nằm ở {@code
 * opa/okrkpi/authz_test.rego} ({@code test_okr_review_needs_scr0203_function}).
 */
class OkrReviewPermissionTest {

  @Test
  void duyetVaTraLaiKiemTheoChucNangScr0203() {
    Map<String, String> expected =
        Map.of(
            "approveOkrObjective", "SCR-0203.duyet",
            "approveOkrObjectivesBatch", "SCR-0203.duyet-lo",
            "returnOkrObjective", "SCR-0203.tra-lai");
    Map<String, String> actual =
        Arrays.stream(OkrComposeController.class.getDeclaredMethods())
            .map(m -> m.getAnnotation(RequiresPermission.class))
            .filter(p -> p != null && expected.containsKey(p.operationId()))
            .collect(Collectors.toMap(RequiresPermission::operationId, RequiresPermission::action));
    assertThat(actual).isEqualTo(expected);
  }
}
