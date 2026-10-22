package vn.viettel.okrkpi.policy.domain;

import java.util.Objects;

/**
 * Chuyển trạng thái phiên bản không hợp lệ hoặc chưa đủ điều kiện; {@link #code()} là mã lỗi của
 * hợp đồng {@code policies.yaml} (ví dụ {@code POLICY-409-NOT-TESTED}).
 */
public class PolicyTransitionException extends IllegalStateException {

  private final String code;

  public PolicyTransitionException(String code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  public String code() {
    return code;
  }
}
