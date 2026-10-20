package vn.viettel.okrkpi.shared.error;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Một mã lỗi của danh mục: {@code <MODULE>-<HTTP>-<TÊN>} (TL-01 "Định dạng lỗi chuẩn RFC 9457",
 * {@code contracts/openapi/README.md} mục "Lỗi").
 *
 * @param code mã đầy đủ, ví dụ {@code TASK-409-PERIOD-LOCKED}
 * @param module phần MODULE, ví dụ {@code TASK}
 * @param status HTTP status, bằng phần số trong mã
 * @param name phần TÊN, ví dụ {@code PERIOD-LOCKED}
 * @param type URI ổn định {@code https://okrkpi.vn/problems/<slug>}
 * @param title tiêu đề tiếng Việt
 * @param msg mã thông điệp TL-02 ({@code MSG-xx-yy}) nếu hợp đồng ghi, có thể {@code null}
 */
public record ErrorCode(
    String code, String module, int status, String name, String type, String title, String msg)
    implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  /** Dạng mã lỗi; phần HTTP là mã 4xx hoặc 5xx. */
  public static final Pattern CODE_PATTERN =
      Pattern.compile("([A-Z][A-Z0-9]*)-([45][0-9]{2})-([A-Z0-9]+(?:-[A-Z0-9]+)*)");

  /** Dạng mã thông điệp TL-02. */
  public static final Pattern MSG_PATTERN = Pattern.compile("MSG-[0-9]{2}-[0-9]{2}");

  public ErrorCode {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(module, "module");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    var m = CODE_PATTERN.matcher(code);
    if (!m.matches()) {
      throw new IllegalArgumentException("Mã lỗi sai dạng <MODULE>-<HTTP>-<TÊN>: " + code);
    }
    if (!m.group(1).equals(module) || !m.group(3).equals(name)) {
      throw new IllegalArgumentException("MODULE/TÊN không khớp mã " + code);
    }
    if (Integer.parseInt(m.group(2)) != status) {
      throw new IllegalArgumentException("HTTP status " + status + " không khớp mã " + code);
    }
    if (!type.startsWith(ProblemTypes.BASE_URI)) {
      throw new IllegalArgumentException("type phải bắt đầu bằng " + ProblemTypes.BASE_URI);
    }
    if (msg != null && !MSG_PATTERN.matcher(msg).matches()) {
      throw new IllegalArgumentException("msg sai dạng MSG-xx-yy: " + msg);
    }
  }
}
