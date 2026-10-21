package vn.viettel.okrkpi.shared.crypto;

import java.io.Serial;
import java.util.Objects;
import java.util.UUID;

/**
 * Ném ra khi bản mã tham chiếu tới DEK đã ở trạng thái {@link DekStatus#DESTROYED} (xoá dữ liệu
 * bằng mật mã theo ADR-06, TL-03).
 */
public final class DekDestroyedException extends CryptoException {

  @Serial private static final long serialVersionUID = 1L;

  private final UUID tenantId;
  private final UUID dekId;

  public DekDestroyedException(UUID tenantId, UUID dekId) {
    super(
        "Khoá DEK "
            + Objects.requireNonNull(dekId, "dekId")
            + " của tenant "
            + Objects.requireNonNull(tenantId, "tenantId")
            + " đã bị huỷ (DESTROYED); dữ liệu đã xoá bằng mật mã");
    this.tenantId = tenantId;
    this.dekId = dekId;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID dekId() {
    return dekId;
  }
}
