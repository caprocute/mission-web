package vn.viettel.okrkpi.integ.application;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lỗi chuyển đổi hoặc phát một thông điệp vào đã lưu: mang mã tenant, kết nối và dòng {@code
 * inbox_message} để thư chết trỏ về đúng thông điệp. Thông điệp lỗi không chứa nội dung.
 */
public class InboundProcessingException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final transient TenantId tenant;
  private final UUID connectorId;
  private final UUID inboxId;

  public InboundProcessingException(
      TenantId tenant, UUID connectorId, UUID inboxId, Throwable cause) {
    super("Không xử lý được thông điệp vào " + inboxId, cause);
    this.tenant = tenant;
    this.connectorId = connectorId;
    this.inboxId = inboxId;
  }

  public TenantId tenant() {
    return tenant;
  }

  public UUID connectorId() {
    return connectorId;
  }

  public UUID inboxId() {
    return inboxId;
  }
}
