package vn.viettel.okrkpi.shared.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * NEN-035, ADR-18, TL-03 "Bộ nhớ đệm Valkey" mục "Quy ước khoá": khoá đệm luôn có dạng {@code
 * t:<tenant>:<module>:<loại>:<mã>[:<phụ>]}, tiền tố tenant do thư viện tự thêm từ {@link
 * TenantContext}; mã nguồn không tự ghép chuỗi khoá và không thể tạo khoá thiếu tenant.
 *
 * <p>Thẻ băm chỉ dùng cho nhóm khoá nhỏ cần thao tác nguyên tử nhiều khoá ({@link #grouped}, ví dụ
 * {@code t:<tenant>:{rl:<user>}:minute}); không bao giờ đặt thẻ băm trên toàn bộ tiền tố tenant
 * (TL-03; TL-01 "Bộ đệm phân tán Valkey" ghi {@code {<tenant>}:...}, task theo TL-03).
 */
public final class TenantCacheKey {

  static final String PREFIX = "t";
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.\\-]+");

  private final TenantId tenant;
  private final String value;

  private TenantCacheKey(TenantId tenant, String value) {
    this.tenant = tenant;
    this.value = value;
  }

  /** Khoá của tenant đang trong ngữ cảnh; ném {@code MissingTenantException} khi chưa đặt. */
  public static TenantCacheKey of(String module, String type, String id, String... sub) {
    return forTenant(TenantContext.require(), module, type, id, sub);
  }

  /**
   * Khoá cho tenant chỉ định, dành cho consumer vô hiệu hoá đọc tenant từ phong bì sự kiện; vẫn
   * không cho tenant rỗng.
   */
  public static TenantCacheKey forTenant(
      TenantId tenant, String module, String type, String id, String... sub) {
    Objects.requireNonNull(tenant, "tenant");
    List<String> parts = new ArrayList<>();
    parts.add(segment("module", module));
    parts.add(segment("loại", type));
    parts.add(segment("mã", id));
    for (String s : sub) {
      parts.add(segment("phụ", s));
    }
    return new TenantCacheKey(tenant, tenantPrefix(tenant) + String.join(":", parts));
  }

  /**
   * Khoá trong một nhóm thẻ băm nhỏ: {@code t:<tenant>:{<nhóm>}:<hậu tố>}, ví dụ {@code
   * grouped("rl:" + userId, "minute")} cho bộ giới hạn tần suất.
   */
  public static TenantCacheKey grouped(String group, String suffix) {
    TenantId tenant = TenantContext.require();
    String[] groupParts = Objects.requireNonNull(group, "group").split(":", -1);
    for (String g : groupParts) {
      segment("nhóm", g);
    }
    return new TenantCacheKey(
        tenant, tenantPrefix(tenant) + "{" + group + "}:" + segment("hậu tố", suffix));
  }

  /** Mẫu {@code SCAN} chỉ khớp khoá của một tenant: {@code t:<tenant>:*}. */
  public static String scanPattern(TenantId tenant) {
    return tenantPrefix(Objects.requireNonNull(tenant, "tenant")) + "*";
  }

  static String tenantPrefix(TenantId tenant) {
    return PREFIX + ":" + tenant.value() + ":";
  }

  private static String segment(String name, String value) {
    if (value == null || !SEGMENT.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Thành phần khoá đệm '" + name + "' không hợp lệ (chỉ chữ, số, '_', '.', '-')");
    }
    return value;
  }

  public TenantId tenant() {
    return tenant;
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TenantCacheKey k && k.value.equals(value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
