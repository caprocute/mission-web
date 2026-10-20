package vn.viettel.okrkpi.shared.paging;

import java.io.Serial;

/**
 * Tham số phân trang sai: {@code limit} ngoài 1..200, con trỏ sai, bị sửa, hết hạn hoặc dùng cho
 * danh sách khác. {@link PagingProblemHandler} trả 400 kèm {@code errors[]} trỏ vào tham số.
 */
public class InvalidPageRequestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Tên tham số truy vấn của hợp đồng. */
  public static final String LIMIT = "limit";

  public static final String CURSOR = "cursor";

  private final String parameter;
  private final String userMessage;

  public InvalidPageRequestException(String parameter, String userMessage) {
    super("INVALID-PAGE-REQUEST", null, false, false);
    this.parameter = parameter;
    this.userMessage = userMessage;
  }

  public String parameter() {
    return parameter;
  }

  /** Câu tiếng Việt cho người dùng, không chứa nội dung con trỏ. */
  public String userMessage() {
    return userMessage;
  }
}
