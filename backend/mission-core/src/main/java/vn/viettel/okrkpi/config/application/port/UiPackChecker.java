package vn.viettel.okrkpi.config.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điểm mở cho bộ kiểm tra tự động của UI Pack khi lưu nháp và khi gửi duyệt (QT-18): danh mục thành
 * phần (CORE-08-09), tương phản và chế độ cao tuổi (CORE-08-10), kích thước, cel-js = cel-java.
 * Kiểm lược đồ UI Pack 1.0 do dịch vụ làm trước, nên bộ kiểm nhận tài liệu đã đúng lược đồ.
 */
public interface UiPackChecker {

  /** Mã kiểm tra theo {@code UiPackCheck.code} của {@code config.yaml}. */
  enum Code {
    SCHEMA,
    REGISTRY,
    CONTRAST,
    SIZE,
    CEL_PARITY,
    ELDER_MODE
  }

  /**
   * Một mục kiểm tra.
   *
   * @param msg mã thông điệp TL-02b nếu có, ví dụ {@code MSG-18-01}
   * @param errors lỗi theo trường của tài liệu UI Pack
   */
  record Result(
      Code code, boolean passed, String message, String msg, List<ProblemFieldError> errors) {
    public Result {
      Objects.requireNonNull(code, "code");
      errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static Result ok(Code code, String message) {
      return new Result(code, true, message, null, List.of());
    }
  }

  /**
   * @param document tài liệu UI Pack 1.0 dựng từ phiên bản ({@code pack}, {@code version}, {@code
   *     requires}, {@code tokens}, {@code modes}, {@code layouts}, {@code assets})
   */
  List<Result> check(TenantId tenantId, JsonNode document);

  /** Chưa có bộ kiểm nào gắn vào. */
  UiPackChecker NONE = (tenant, document) -> List.of();
}
