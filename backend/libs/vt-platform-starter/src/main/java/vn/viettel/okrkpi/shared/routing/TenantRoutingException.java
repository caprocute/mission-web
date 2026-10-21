package vn.viettel.okrkpi.shared.routing;

/**
 * Ngoại lệ phát sinh khi không thể định tuyến yêu cầu hoặc kết nối CSDL cho khách thuê hiện hành.
 */
public class TenantRoutingException extends RuntimeException {

  public enum Reason {
    MISSING_TENANT_CONTEXT,
    TENANT_NOT_FOUND,
    WRONG_CELL,
    TENANT_MIGRATING_OR_UNAVAILABLE
  }

  private final Reason reason;
  private final String tenantKey;
  private final String expectedCell;
  private final String actualCell;

  public TenantRoutingException(Reason reason, String tenantKey, String message) {
    this(reason, tenantKey, null, null, message);
  }

  public TenantRoutingException(
      Reason reason, String tenantKey, String expectedCell, String actualCell, String message) {
    super(message);
    this.reason = reason;
    this.tenantKey = tenantKey;
    this.expectedCell = expectedCell;
    this.actualCell = actualCell;
  }

  public Reason reason() {
    return reason;
  }

  public String tenantKey() {
    return tenantKey;
  }

  public String expectedCell() {
    return expectedCell;
  }

  public String actualCell() {
    return actualCell;
  }
}
