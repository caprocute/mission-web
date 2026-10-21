package vn.viettel.okrkpi.shared.error;

import java.io.Serial;

/**
 * Lệch phiên bản khi cập nhật ({@code If-Match} khác {@code row_version} hiện tại): 409 mã {@code
 * <MODULE>-409-VERSION-CONFLICT}, {@code type} {@code .../concurrent-modification} (BS-02 HD-01).
 *
 * <p>Khi không truyền module, bộ xử lý lỗi xác định module từ gói của controller ({@code
 * vn.viettel.okrkpi.<module>..}), rồi tới thuộc tính {@code okrkpi.problem.module}, cuối cùng là
 * {@code COMMON}.
 */
public class VersionConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String module;
  private final Long expectedVersion;
  private final Long currentVersion;

  /**
   * @param module phần MODULE của mã ({@code TASK}…), {@code null} để bộ xử lý tự xác định
   * @param expectedVersion phiên bản người gọi gửi trong {@code If-Match}, có thể {@code null}
   * @param currentVersion phiên bản hiện tại, có thể {@code null}
   */
  public VersionConflictException(String module, Long expectedVersion, Long currentVersion) {
    super("VERSION-CONFLICT", null, false, false);
    this.module = module;
    this.expectedVersion = expectedVersion;
    this.currentVersion = currentVersion;
  }

  public static VersionConflictException of(String module, long expected, long current) {
    return new VersionConflictException(module, expected, current);
  }

  public String module() {
    return module;
  }

  public Long expectedVersion() {
    return expectedVersion;
  }

  public Long currentVersion() {
    return currentVersion;
  }

  /** Mô tả tiếng Việt theo ví dụ của {@code common.yaml}. */
  public String detail() {
    if (expectedVersion != null && currentVersion != null) {
      return "Phiên bản bạn sửa là "
          + expectedVersion
          + ", phiên bản hiện tại là "
          + currentVersion
          + ".";
    }
    return DEFAULT_DETAIL;
  }

  static final String DEFAULT_DETAIL =
      "Vui lòng tải lại dữ liệu mới nhất, đối chiếu thay đổi rồi lưu lại.";
}
