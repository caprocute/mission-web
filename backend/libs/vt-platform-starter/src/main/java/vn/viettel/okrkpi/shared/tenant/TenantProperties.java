package vn.viettel.okrkpi.shared.tenant;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình ngữ cảnh tenant ({@code okrkpi.tenant.*}, TL-01 cấu hình mẫu SVC-05).
 *
 * <p>Tên claim lệch giữa các nguồn: TL-01 dùng {@code tenant_id}, TL-03 dùng {@code tid}, realm dev
 * dùng {@code tenant}; thư viện không tự chọn mà để cấu hình, mặc định {@code tenant_id} theo TL-01
 * (điểm lệch ghi ở wiki trang 14).
 *
 * @param claim tên claim JWT chứa tenant
 * @param dbSetting tên tham số PostgreSQL mà chính sách RLS đọc
 */
@ConfigurationProperties("okrkpi.tenant")
public record TenantProperties(String claim, String dbSetting) {

  /** Tham số PostgreSQL tuỳ biến dạng {@code <tiền tố>.<tên>}, chỉ chữ thường và gạch dưới. */
  private static final Pattern SETTING = Pattern.compile("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");

  public TenantProperties {
    if (claim == null || claim.isBlank()) {
      claim = "tenant_id";
    }
    if (dbSetting == null || dbSetting.isBlank()) {
      dbSetting = TenantSql.DEFAULT_SETTING;
    }
    if (!SETTING.matcher(dbSetting).matches()) {
      throw new IllegalArgumentException("okrkpi.tenant.db-setting không hợp lệ: " + dbSetting);
    }
  }

  /** Giá trị mặc định khi không cấu hình. */
  public static TenantProperties defaults() {
    return new TenantProperties(null, null);
  }
}
