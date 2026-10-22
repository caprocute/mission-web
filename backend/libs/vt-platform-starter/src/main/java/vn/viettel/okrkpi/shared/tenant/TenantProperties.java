package vn.viettel.okrkpi.shared.tenant;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Cấu hình ngữ cảnh tenant ({@code okrkpi.tenant.*}, TL-01 cấu hình mẫu SVC-05).
 *
 * <p>Tên claim lệch giữa các nguồn: TL-01 dùng {@code tenant_id}, TL-03 dùng {@code tid}, realm dev
 * dùng {@code tenant}; thư viện không tự chọn mà để cấu hình, mặc định {@code tenant_id} theo TL-01
 * (điểm lệch ghi ở wiki trang 14).
 *
 * @param claim tên claim JWT chứa tenant
 * @param dbSetting tên tham số PostgreSQL mà chính sách RLS đọc
 * @param delegatingClients client dịch vụ (claim {@code azp}) được gọi thay cho một tenant: token
 *     client credentials của chúng không mang claim tenant, tenant đích đọc từ {@code
 *     delegationHeader}. Rỗng (mặc định) thì không client nào được phép, header luôn bị bỏ qua
 * @param delegationHeader header mang tenant đích của lời gọi dịch vụ tới dịch vụ
 */
@ConfigurationProperties("okrkpi.tenant")
public record TenantProperties(
    String claim, String dbSetting, List<String> delegatingClients, String delegationHeader) {

  /** Header mặc định mang tenant đích (cùng tên với saga cấp phát, wiki trang 14 mục 28). */
  public static final String DEFAULT_DELEGATION_HEADER = "X-Tenant-Id";

  /** Tham số PostgreSQL tuỳ biến dạng {@code <tiền tố>.<tên>}, chỉ chữ thường và gạch dưới. */
  private static final Pattern SETTING = Pattern.compile("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");

  @ConstructorBinding
  public TenantProperties {
    delegatingClients =
        delegatingClients == null
            ? List.of()
            : delegatingClients.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
    if (delegationHeader == null || delegationHeader.isBlank()) {
      delegationHeader = DEFAULT_DELEGATION_HEADER;
    }
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

  public TenantProperties(String claim, String dbSetting) {
    this(claim, dbSetting, null, null);
  }

  /** Giá trị mặc định khi không cấu hình. */
  public static TenantProperties defaults() {
    return new TenantProperties(null, null);
  }
}
