package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import java.util.ArrayList;
import java.util.List;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;

/**
 * Sinh các câu SQL idempotent tạo vai trò và quyền cho một CSDL theo TL-03 "Không truy cập chéo và
 * quyền tối thiểu" (L149-179) và ADR-22.
 *
 * <p>Mỗi schema có bốn vai trò:
 *
 * <ul>
 *   <li>{@code mig_<schema>}: chủ sở hữu schema và mọi đối tượng, chỉ Flyway dùng ({@code
 *       NOINHERIT});
 *   <li>{@code svc_<schema>}: tài khoản ứng dụng, chỉ DML, {@code NOINHERIT NOBYPASSRLS}, {@code
 *       statement_timeout = 5s}, {@code idle_in_transaction_session_timeout = 10s}, {@code
 *       search_path} cố định;
 *   <li>{@code ro_<schema>}: chỉ đọc, {@code NOBYPASSRLS};
 *   <li>{@code cdc_<schema>}: Debezium, {@code REPLICATION}, chỉ đọc {@code outbox_event} và bảng
 *       khai báo cho CDC phân tích.
 * </ul>
 *
 * <p>Ngoại lệ (TL-03): mọi {@code svc_<mô-đun>} của mission-core đọc được {@code org_replica} (chỉ
 * {@code svc_org_replica} ghi) và có DML trên {@code modulith}; mọi {@code svc_} của CSDL dữ liệu
 * tenant chỉ SELECT, INSERT trên {@code keyring}, chỉ {@code svc_keyring} đổi trạng thái DEK.
 *
 * <p>Mật khẩu không nằm trong các câu này; {@link DatabaseBootstrap} đặt riêng khi được cấp.
 */
public final class RoleBootstrapPlan {

  /** Bảng outbox mà {@code cdc_<schema>} được đọc (TL-03; tạo ở NEN-032). */
  public static final String OUTBOX_TABLE = "outbox_event";

  static final String STATEMENT_TIMEOUT = "5s";
  static final String IDLE_IN_TRANSACTION_TIMEOUT = "10s";

  private RoleBootstrapPlan() {}

  /** Bốn loại vai trò theo thứ tự tạo. */
  public static final List<String> ROLE_KINDS = List.of("mig", "svc", "ro", "cdc");

  /** Tên vai trò {@code <kind>_<schema>}. */
  public static String role(String kind, String schema) {
    return kind + "_" + SchemaNames.require(schema);
  }

  /** Mọi vai trò của bố trí, dùng để cấp CONNECT trên CSDL. */
  public static List<String> roles(DatabaseLayout layout) {
    List<String> roles = new ArrayList<>();
    for (String schema : layout.schemas()) {
      for (String kind : ROLE_KINDS) {
        roles.add(role(kind, schema));
      }
    }
    return roles;
  }

  /**
   * Toàn bộ câu SQL cho một CSDL. Chạy lại nhiều lần cho cùng kết quả.
   *
   * @param database tên CSDL hiện tại (để thu hồi CONNECT của PUBLIC)
   */
  public static List<String> statements(String database, DatabaseLayout layout, RoleLimits limits) {
    SchemaNames.require(database);
    List<String> sql = new ArrayList<>();
    for (String schema : layout.schemas()) {
      sql.addAll(rolesFor(schema, limits));
    }
    sql.add("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
    sql.add("GRANT CONNECT ON DATABASE " + database + " TO " + String.join(", ", roles(layout)));
    sql.add("REVOKE ALL ON SCHEMA public FROM PUBLIC");
    for (String schema : layout.schemas()) {
      sql.addAll(schemaGrants(schema));
    }
    if (layout == DatabaseLayout.TENANT_DATA) {
      sql.addAll(tenantDataExceptions(layout));
    }
    return sql;
  }

  static List<String> rolesFor(String schema, RoleLimits limits) {
    List<String> sql = new ArrayList<>();
    for (String kind : ROLE_KINDS) {
      String role = role(kind, schema);
      sql.add(
          "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '"
              + role
              + "') THEN CREATE ROLE "
              + role
              + "; END IF; END $$");
      String replication = "cdc".equals(kind) ? "REPLICATION" : "NOREPLICATION";
      sql.add(
          "ALTER ROLE "
              + role
              + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS "
              + replication
              + " CONNECTION LIMIT "
              + limits.limit(kind, schema));
      sql.add("ALTER ROLE " + role + " SET search_path = " + schema);
    }
    String svc = role("svc", schema);
    sql.add("ALTER ROLE " + svc + " SET statement_timeout = '" + STATEMENT_TIMEOUT + "'");
    sql.add(
        "ALTER ROLE "
            + svc
            + " SET idle_in_transaction_session_timeout = '"
            + IDLE_IN_TRANSACTION_TIMEOUT
            + "'");
    return sql;
  }

  static List<String> schemaGrants(String schema) {
    String mig = role("mig", schema);
    String svc = role("svc", schema);
    String ro = role("ro", schema);
    String cdc = role("cdc", schema);
    String defaults = "ALTER DEFAULT PRIVILEGES FOR ROLE " + mig + " IN SCHEMA " + schema + " ";
    List<String> sql = new ArrayList<>();
    sql.add("CREATE SCHEMA IF NOT EXISTS " + schema + " AUTHORIZATION " + mig);
    sql.add("ALTER SCHEMA " + schema + " OWNER TO " + mig);
    sql.add("REVOKE ALL ON SCHEMA " + schema + " FROM PUBLIC");
    sql.add("GRANT USAGE ON SCHEMA " + schema + " TO " + svc + ", " + ro + ", " + cdc);
    // Quyền mặc định cho đối tượng mig_<schema> tạo về sau.
    sql.add(defaults + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + svc);
    sql.add(defaults + "GRANT USAGE, SELECT ON SEQUENCES TO " + svc);
    sql.add(defaults + "GRANT SELECT ON TABLES TO " + ro);
    // Hàm (gồm SECURITY DEFINER tạo phân vùng) chỉ svc_<schema> gọi được (suy luận: TL-03 L185).
    sql.add(defaults + "REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
    sql.add(defaults + "GRANT EXECUTE ON FUNCTIONS TO " + svc);
    // Bắt kịp đối tượng đã có khi chạy lại sau migration.
    sql.add(
        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO " + svc);
    sql.add("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + svc);
    sql.add("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + ro);
    sql.add("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " FROM PUBLIC");
    sql.add("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " TO " + svc);
    sql.add(
        "DO $$ BEGIN IF to_regclass('"
            + schema
            + "."
            + OUTBOX_TABLE
            + "') IS NOT NULL THEN GRANT SELECT ON "
            + schema
            + "."
            + OUTBOX_TABLE
            + " TO "
            + cdc
            + "; END IF; END $$");
    return sql;
  }

  static List<String> tenantDataExceptions(DatabaseLayout layout) {
    List<String> sql = new ArrayList<>();
    String replica = DatabaseLayout.ORG_REPLICA;
    String modulith = DatabaseLayout.MODULITH;
    String keyring = DatabaseLayout.KEYRING;
    for (String module : DatabaseLayout.MISSION_CORE_MODULES) {
      String svc = role("svc", module);
      // org_replica: mọi mô-đun chỉ đọc (TL-03 L464).
      sql.add("GRANT USAGE ON SCHEMA " + replica + " TO " + svc);
      sql.add(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + role("mig", replica)
              + " IN SCHEMA "
              + replica
              + " GRANT SELECT ON TABLES TO "
              + svc);
      sql.add("GRANT SELECT ON ALL TABLES IN SCHEMA " + replica + " TO " + svc);
      // modulith.event_publication: DML cho mọi mô-đun mission-core (TL-03 L896).
      sql.add("GRANT USAGE ON SCHEMA " + modulith + " TO " + svc);
      sql.add(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + role("mig", modulith)
              + " IN SCHEMA "
              + modulith
              + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "
              + svc);
      sql.add(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
              + modulith
              + " TO "
              + svc);
    }
    // keyring.dek: svc_ của mọi schema nghiệp vụ chỉ SELECT, INSERT (TL-03 L913).
    for (String schema : layout.schemas()) {
      if (schema.equals(keyring) || schema.equals(modulith)) {
        continue;
      }
      String svc = role("svc", schema);
      sql.add("GRANT USAGE ON SCHEMA " + keyring + " TO " + svc);
      sql.add(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + role("mig", keyring)
              + " IN SCHEMA "
              + keyring
              + " GRANT SELECT, INSERT ON TABLES TO "
              + svc);
      sql.add("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA " + keyring + " TO " + svc);
    }
    return sql;
  }
}
