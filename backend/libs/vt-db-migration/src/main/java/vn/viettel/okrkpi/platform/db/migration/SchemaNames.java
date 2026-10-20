package vn.viettel.okrkpi.platform.db.migration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kiểm và chuẩn hoá tên schema (TL-03 "Quy ước đặt tên": chữ thường, snake_case, không trích dẫn).
 * Mọi chỗ ghép tên schema vào câu SQL hoặc tên vai trò đều phải đi qua lớp này, vì định danh SQL
 * không truyền được bằng tham số ràng buộc.
 */
public final class SchemaNames {

  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private SchemaNames() {}

  /**
   * Trả lại chính tên schema nếu hợp lệ.
   *
   * @throws IllegalArgumentException khi tên có chữ hoa, ký tự lạ hoặc dài quá 63 ký tự
   */
  public static String require(String schema) {
    if (schema == null || !VALID.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "Tên schema không hợp lệ (chỉ gồm chữ thường, số, gạch dưới, bắt đầu bằng chữ): "
              + schema);
    }
    return schema;
  }

  /** Tài khoản Flyway của schema: {@code mig_<schema>} (TL-03 "Không truy cập chéo"). */
  public static String migrationRole(String schema) {
    return "mig_" + require(schema);
  }

  /** Tên biến môi trường chứa mật khẩu của {@code mig_<schema>}: {@code MIG_<SCHEMA>_PASSWORD}. */
  public static String passwordEnvVar(String schema) {
    return "MIG_" + require(schema).toUpperCase(Locale.ROOT) + "_PASSWORD";
  }
}
