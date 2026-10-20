package vn.viettel.okrkpi.platform.db.migration;

/**
 * Tuỳ chọn chạy Flyway cho một schema.
 *
 * @param commonLocation thư mục migration dùng chung mọi schema (dải số V0_x, dùng placeholder
 *     {@code ${flyway:defaultSchema}})
 * @param schemaLocationPrefix tiền tố thư mục migration của từng schema; tên schema được nối vào
 *     sau
 * @param createSchemas cho Flyway tự tạo schema; production để {@code false} vì schema do NEN-031
 *     tạo sẵn với chủ sở hữu {@code mig_<schema>}, chỉ kiểm thử lint mới bật
 */
public record MigrationOptions(
    String commonLocation, String schemaLocationPrefix, boolean createSchemas) {

  /** Quy ước mặc định: {@code classpath:db/common} và {@code classpath:db/migration/<schema>}. */
  public static MigrationOptions defaults() {
    return new MigrationOptions("classpath:db/common", "classpath:db/migration/", false);
  }

  /** Bản sao cho phép Flyway tự tạo schema (chỉ dùng trong kiểm thử). */
  public MigrationOptions withCreateSchemas() {
    return new MigrationOptions(commonLocation, schemaLocationPrefix, true);
  }

  /**
   * Phần dùng chung chỉ cho schema nghiệp vụ ({@code <commonLocation>-business}: outbox, inbox,
   * idempotency – NEN-032); schema kỹ thuật {@code modulith}, {@code keyring} không nhận.
   */
  public String businessCommonLocation() {
    return commonLocation + "-business";
  }

  public String schemaLocation(String schema) {
    return schemaLocationPrefix + SchemaNames.require(schema);
  }
}
