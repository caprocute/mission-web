package vn.viettel.okrkpi.shared.authz;

/**
 * Ngoại lệ ném ra khi kết quả đánh giá từng phần ({@code POST /v1/compile}) từ OPA chứa điều kiện
 * AST không nằm trong tập hỗ trợ hoặc khi OPA lỗi; truy vấn phải bị từ chối thay vì bỏ qua điều
 * kiện (fail closed, NEN-014, TL-01).
 */
public final class UntranslatablePolicyException extends RuntimeException {

  public UntranslatablePolicyException(String message) {
    super(message);
  }

  public UntranslatablePolicyException(String message, Throwable cause) {
    super(message, cause);
  }
}
