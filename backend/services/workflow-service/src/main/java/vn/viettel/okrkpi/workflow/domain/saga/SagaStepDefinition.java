package vn.viettel.okrkpi.workflow.domain.saga;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Khai báo một bước saga: mã bước, lệnh phát đi, sự kiện trả lời mong đợi, thời hạn chờ trả lời,
 * chính sách thử lại, hành động bù trừ (có thể không có) và cờ điểm không quay lại.
 *
 * @param stepCode mã bước, ví dụ {@code DAT_DANG_KHOA} (TL-03 {@code saga_step.step_code})
 * @param command tên lệnh hoặc topic lệnh mà bước phát, để truy vết
 * @param expectedReply tên sự kiện trả lời mong đợi; rỗng khi bước xong ngay trong lần thực hiện
 * @param replyTimeout thời hạn chờ trả lời, quá hạn tính là một lần thử thất bại
 * @param retry chính sách thử lại
 * @param compensationCode mã hành động bù trừ; {@code null} khi bước không cần bù trừ
 * @param pointOfNoReturn bước này xong thì saga qua điểm không quay lại
 */
public record SagaStepDefinition(
    String stepCode,
    String command,
    String expectedReply,
    Duration replyTimeout,
    RetryPolicy retry,
    String compensationCode,
    boolean pointOfNoReturn) {

  public SagaStepDefinition {
    requireCode(stepCode, "stepCode");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(replyTimeout, "replyTimeout");
    Objects.requireNonNull(retry, "retry");
    if (compensationCode != null) {
      requireCode(compensationCode, "compensationCode");
    }
    if (replyTimeout.isNegative() || replyTimeout.isZero()) {
      throw new IllegalArgumentException("replyTimeout phải dương");
    }
  }

  public Optional<String> compensation() {
    return Optional.ofNullable(compensationCode);
  }

  public boolean awaitsReply() {
    return expectedReply != null && !expectedReply.isBlank();
  }

  private static void requireCode(String code, String field) {
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,47}")) {
      throw new IllegalArgumentException(
          field + " sai dạng mã (chữ hoa, tối đa 48 ký tự): " + code);
    }
  }
}
