package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/** Lỗi nghiệp vụ của SVC-15 theo mã hợp đồng (error-codes.json). */
public final class AnalyticsErrors {

  public static final String INVALID_COMBINATION = "ANALYTICS-422-INVALID-COMBINATION";
  public static final String DUPLICATE_TITLE = "ANALYTICS-409-DUPLICATE-TITLE";
  public static final String VALIDATION = "COMMON-422-VALIDATION";
  public static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";

  private AnalyticsErrors() {}

  public static BusinessException outOfScope(String detail) {
    return new BusinessException(OUT_OF_SCOPE, detail);
  }

  public static BusinessException validation(String field, String message) {
    return new BusinessException(
        VALIDATION, "Dữ liệu chưa hợp lệ", List.of(ProblemFieldError.of(field, message)));
  }

  /**
   * Vi phạm lớp ngữ nghĩa: có vi phạm tên hoặc tổ hợp thì trả {@code
   * ANALYTICS-422-INVALID-COMBINATION} (BR-16-02), còn lại {@code COMMON-422-VALIDATION}.
   */
  public static void raise(List<SemanticCatalog.Violation> violations) {
    if (violations.isEmpty()) {
      return;
    }
    List<ProblemFieldError> errors =
        violations.stream().map(v -> ProblemFieldError.of(v.field(), v.message())).toList();
    boolean combination = violations.stream().anyMatch(SemanticCatalog.Violation::combination);
    throw combination
        ? new BusinessException(
            INVALID_COMBINATION, "Chỉ số và chiều đã chọn không kết hợp được với nhau", errors)
        : new BusinessException(VALIDATION, "Dữ liệu chưa hợp lệ", errors);
  }
}
