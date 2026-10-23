package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import java.util.List;

/**
 * Bố trí schema trong một CSDL theo TL-03 "Bố trí theo mô hình silo/bridge/pool" và {@code
 * _ghi-chu-TL-03.md} mục 1.
 *
 * <ul>
 *   <li>{@link #TENANT_DATA}: CSDL dữ liệu tenant {@code td_<mã>} (silo, bridge) hoặc {@code
 *       td_pool} (pool): 9 schema của mission-core, 4 schema của microservice theo tenant và 2
 *       schema bổ sung {@code modulith}, {@code keyring}.
 *   <li>{@link #CELL_SHARED}: CSDL {@code cell_shared} của cell: {@code notify}, {@code integ}.
 * </ul>
 */
public enum DatabaseLayout {
  TENANT_DATA(
      "td",
      List.of(
          "config",
          "policy",
          "okr",
          "task",
          "product",
          "appraisal",
          "dossier",
          "oversight",
          "org_replica",
          "org",
          "scoring",
          "evidence",
          "docgen",
          "modulith",
          "keyring")),
  CELL_SHARED("cell_shared", List.of("notify", "integ"));

  /** Tám schema mô-đun MOD-01..MOD-08 của mission-core (SVC-05). */
  public static final List<String> MISSION_CORE_MODULES =
      List.of("config", "policy", "okr", "task", "product", "appraisal", "dossier", "oversight");

  /** Schema bản sao tổ chức, chỉ {@code svc_org_replica} được ghi (TL-03 L464). */
  public static final String ORG_REPLICA = "org_replica";

  /** Schema Event Publication Registry của Spring Modulith (TL-03 L894). */
  public static final String MODULITH = "modulith";

  /** Schema khoá dữ liệu DEK (TL-03 L913, ADR-30). */
  public static final String KEYRING = "keyring";

  /**
   * Schema kỹ thuật không phải schema nghiệp vụ: không nhận bảng kỹ thuật dùng chung {@code
   * outbox_event}, {@code inbox_*}, {@code idempotency_key} (TL-03 L766, NEN-032).
   */
  public static final List<String> TECHNICAL_SCHEMAS = List.of(MODULITH, KEYRING);

  /** Schema nghiệp vụ là mọi schema không thuộc {@link #TECHNICAL_SCHEMAS}. */
  public static boolean isBusinessSchema(String schema) {
    return !TECHNICAL_SCHEMAS.contains(schema);
  }

  /**
   * Schema nghiệp vụ tự chứa: migration riêng của service tự tạo {@code outbox_event}, {@code
   * inbox_*}, {@code idempotency_key} (phân vùng DEFAULT, không cần pg_partman) và Flyway của chính
   * service cũng áp được, nên {@code MigrationRunner} không thêm phần dùng chung V0_x cho các
   * schema này; thêm vào thì trùng bảng {@code outbox_event} (org-service V4, workflow-service V3,
   * document-service V1, esign-service V1).
   */
  public static final List<String> SELF_CONTAINED_SCHEMAS =
      List.of("org", "flowable", "docgen", "esign");

  /**
   * {@code true} khi schema tự tạo bảng kỹ thuật dùng chung, xem {@link #SELF_CONTAINED_SCHEMAS}.
   */
  public static boolean isSelfContained(String schema) {
    return SELF_CONTAINED_SCHEMAS.contains(schema);
  }

  private final String code;
  private final List<String> schemas;

  DatabaseLayout(String code, List<String> schemas) {
    this.code = code;
    this.schemas = schemas;
  }

  /** Mã dùng ở tham số dòng lệnh {@code --layout=}. */
  public String code() {
    return code;
  }

  /** Các schema theo thứ tự tạo. */
  public List<String> schemas() {
    return schemas;
  }

  public static DatabaseLayout fromCode(String code) {
    for (DatabaseLayout layout : values()) {
      if (layout.code.equals(code)) {
        return layout;
      }
    }
    throw new IllegalArgumentException("Bố trí CSDL không hợp lệ: " + code + " (td | cell_shared)");
  }
}
