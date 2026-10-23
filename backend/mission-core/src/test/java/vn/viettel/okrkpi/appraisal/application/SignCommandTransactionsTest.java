package vn.viettel.okrkpi.appraisal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;

/**
 * Mẫu "chốt trước, gọi sau" (wiki trang 14) cho các lệnh ký của MOD-04: tự đánh giá (CORE-04-04),
 * thẩm định (CORE-04-05) và quyết định lô (CORE-04-07). API ký chạy trong giao dịch khoá lặp của
 * schema {@code appraisal}; bước chuẩn bị, gắn mã yêu cầu ký và huỷ phải commit riêng ({@code
 * REQUIRES_NEW}) để SVC-10 đọc được tài liệu cần ký bằng kết nối khác. Đổi lại {@code REQUIRED} thì
 * kiểm thử này đỏ.
 */
class SignCommandTransactionsTest {

  static List<Arguments> phases() {
    return List.of(
        Arguments.of(SelfAssessmentService.class, "prepareSubmit"),
        Arguments.of(SelfAssessmentService.class, "attachSignRequest"),
        Arguments.of(SelfAssessmentService.class, "abortSubmit"),
        Arguments.of(ReviewService.class, "prepareAppraisal"),
        Arguments.of(ReviewService.class, "attachSignRequest"),
        Arguments.of(BatchDecisionService.class, "prepare"),
        Arguments.of(BatchDecisionService.class, "attachSignRequest"));
  }

  @ParameterizedTest(name = "{0}.{1}")
  @MethodSource("phases")
  void buocChotVaGanMaKyCommitRieng(Class<?> service, String name) {
    List<Method> methods =
        Arrays.stream(service.getMethods()).filter(m -> m.getName().equals(name)).toList();
    assertThat(methods).isNotEmpty();
    for (Method m : methods) {
      Transactional tx = m.getAnnotation(Transactional.class);
      assertThat(tx).as(m.toString()).isNotNull();
      assertThat(tx.propagation()).as(m.toString()).isEqualTo(Propagation.REQUIRES_NEW);
      assertThat(tx.transactionManager()).isEqualTo("appraisalTransactionManager");
    }
  }
}
