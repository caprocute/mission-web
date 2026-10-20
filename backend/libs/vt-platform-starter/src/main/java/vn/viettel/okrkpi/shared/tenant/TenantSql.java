package vn.viettel.okrkpi.shared.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đặt ngữ cảnh tenant cho giao dịch CSDL đang mở (TL-03 "Đặt ngữ cảnh tenant an toàn qua
 * PgBouncer"): luôn {@code set_config(<tham số>, ?, true)} cục bộ giao dịch, không bao giờ {@code
 * SET} mức phiên, vì PgBouncer chế độ giao dịch trao kết nối máy chủ cho tenant khác ngay sau đó.
 * Không có tenant thì đặt chuỗi rỗng để RLS ({@code nullif(..., '')::uuid}) trả không dòng nào.
 */
public final class TenantSql {

  /** Tham số mà chính sách {@code rls_<bảng>__tenant} đọc (NEN-033). */
  public static final String DEFAULT_SETTING = "app.tenant_id";

  /**
   * {@code SET [SESSION] app.tenant_id ...}, {@code RESET app.tenant_id} hoặc {@code
   * set_config('app.tenant_id', ..., false)}: ngữ cảnh tenant sống quá giao dịch, rò sang tenant
   * khác qua PgBouncer. {@code SET LOCAL} và {@code set_config(..., true)} được phép.
   */
  private static final Pattern SESSION_LEVEL =
      Pattern.compile(
          "(?is)(?:\\b(?:set|reset)\\s+(?!local\\b)(?:session\\s+)?\"?app\\.tenant_id\\b)"
              + "|(?:set_config\\s*\\(\\s*'app\\.tenant_id'\\s*,[^,]*,\\s*false\\s*\\))");

  private TenantSql() {}

  /** Câu SQL đặt hay xoá {@code app.tenant_id} ở mức phiên (bị cấm). */
  public static boolean isSessionLevelTenantStatement(String sql) {
    return sql != null && SESSION_LEVEL.matcher(sql).find();
  }

  /** Ném lỗi nếu {@code sql} đặt ngữ cảnh tenant mức phiên; trả lại {@code sql} khi hợp lệ. */
  public static String requireTransactionLocal(String sql) {
    if (isSessionLevelTenantStatement(sql)) {
      throw new IllegalStateException(
          "Cấm đặt app.tenant_id mức phiên; dùng TenantSql/trình quản lý giao dịch của thư viện nền");
    }
    return sql;
  }

  /** Câu lệnh đặt tham số, cục bộ giao dịch. */
  static String setLocalStatement() {
    return "select set_config(?, ?, true)";
  }

  /**
   * Đặt tenant hiện hành ({@link TenantContext}) cho giao dịch của {@code connection}; kết nối phải
   * đang ở chế độ không tự cam kết.
   */
  public static void apply(Connection connection, String setting) throws SQLException {
    if (connection.getAutoCommit()) {
      throw new IllegalStateException(
          "Chỉ đặt ngữ cảnh tenant trong giao dịch tường minh (autocommit đang bật)");
    }
    String value = TenantContext.current().map(TenantId::toString).orElse("");
    try (PreparedStatement ps = connection.prepareStatement(setLocalStatement())) {
      ps.setString(1, setting);
      ps.setString(2, value);
      ps.execute();
    }
  }
}
