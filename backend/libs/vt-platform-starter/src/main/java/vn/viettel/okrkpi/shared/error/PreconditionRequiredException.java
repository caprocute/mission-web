package vn.viettel.okrkpi.shared.error;

import java.io.Serial;

/**
 * Thao tác cập nhật thiếu header {@code If-Match}: 428 (RFC 6585; BS-02 HD-01, {@code common.yaml}
 * response PreconditionRequired). Hợp đồng chưa cấp mã {@code code} cho lỗi này nên Problem chỉ có
 * {@code type} {@code .../precondition-required}.
 */
public class PreconditionRequiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PreconditionRequiredException() {
    super("PRECONDITION-REQUIRED", null, false, false);
  }
}
