package vn.viettel.okrkpi.search.domain;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tên chỉ mục và bí danh của SVC-16 (TL-03 "Đánh chỉ mục tìm kiếm OpenSearch"):
 *
 * <ul>
 *   <li>silo, bridge: chỉ mục riêng {@code t_<tenant>_task_v<n>};
 *   <li>pool: chỉ mục chung {@code pool_task_v<n>}, bí danh ghi {@code pool_task} trỏ tới phiên bản
 *       hiện hành;
 *   <li>mọi tenant có bí danh {@code t_<tenant>_task}: ở silo trỏ thẳng chỉ mục riêng, ở pool trỏ
 *       chỉ mục chung kèm bộ lọc {@code tenant_id} và định tuyến theo tenant. Indexer và API tìm
 *       kiếm chỉ dùng bí danh này.
 * </ul>
 *
 * {@code <tenant>} là mã UUID của tenant viết thường (suy luận: sự kiện nghiệp vụ chỉ mang {@code
 * tenantid} dạng UUID).
 */
public final class IndexNames {

  public static final String POOL_ALIAS = "pool_task";
  private static final Pattern VERSIONED = Pattern.compile("^(.+)_v(\\d+)$");

  private IndexNames() {}

  /**
   * Chuẩn hoá mã tenant; ném {@link IllegalArgumentException} nếu không phải UUID (chặn chèn tên
   * chỉ mục).
   */
  public static String tenant(String tenantId) {
    return UUID.fromString(tenantId).toString().toLowerCase(Locale.ROOT);
  }

  public static String tenantAlias(String tenantId) {
    return "t_" + tenant(tenantId) + "_task";
  }

  public static String tenantIndex(String tenantId, int version) {
    return tenantAlias(tenantId) + "_v" + version;
  }

  public static String poolIndex(int version) {
    return POOL_ALIAS + "_v" + version;
  }

  /** Phiên bản kế tiếp của một chỉ mục có hậu tố {@code _v<n>}. */
  public static String next(String concreteIndex) {
    Matcher m = VERSIONED.matcher(concreteIndex);
    if (!m.matches()) {
      throw new IllegalArgumentException("Tên chỉ mục không có hậu tố phiên bản: " + concreteIndex);
    }
    return m.group(1) + "_v" + (Integer.parseInt(m.group(2)) + 1);
  }

  public static boolean isPoolIndex(String concreteIndex) {
    return concreteIndex.startsWith(POOL_ALIAS + "_v");
  }
}
