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
