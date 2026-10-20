package vn.viettel.okrkpi.platform.db.migration.guard;

/** Lược đồ thấp hơn phiên bản tối thiểu mà mã yêu cầu; ứng dụng phải dừng khởi động. */
public class SchemaVersionTooLowException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String schema;
  private final String currentVersion;
  private final String requiredVersion;

  public SchemaVersionTooLowException(
      String schema, String currentVersion, String requiredVersion) {
    super(
        "Lược đồ "
            + schema
            + " đang ở phiên bản "
            + (currentVersion == null ? "(chưa chạy migration)" : currentVersion)
            + ", thấp hơn mức tối thiểu "
            + requiredVersion
            + ". Chạy Job di trú (mig_"
            + schema
            + ") trước khi khởi động ứng dụng.");
    this.schema = schema;
    this.currentVersion = currentVersion;
    this.requiredVersion = requiredVersion;
  }

  public String schema() {
    return schema;
  }

  public String currentVersion() {
    return currentVersion;
  }

  public String requiredVersion() {
    return requiredVersion;
  }
}
