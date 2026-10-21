package vn.viettel.okrkpi.shared.audit;

/** Không ghi được nhật ký truy cập C2; dữ liệu C2 không được trả cho người dùng (CORE-00-05). */
public class C2AccessAuditException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public C2AccessAuditException(String message, Throwable cause) {
    super(message, cause);
  }
}
