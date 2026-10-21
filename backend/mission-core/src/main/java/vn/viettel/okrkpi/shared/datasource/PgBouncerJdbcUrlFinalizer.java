package vn.viettel.okrkpi.shared.datasource;

import java.util.Map;

/**
 * Chuẩn hoá URL JDBC PostgreSQL của từng mô-đun để tương thích PgBouncer {@code pool_mode =
 * transaction} (ADR-24, TL-01 L863–894) và kiểm tra tổng ngân sách kết nối của pod.
 *
 * <ul>
 *   <li>Bắt buộc {@code prepareThreshold=0} để tắt prepared statement phía máy chủ.
 *   <li>Bắt buộc {@code ApplicationName=mission-core-<module>} để truy vết trên {@code
 *       pg_stat_activity}.
 * </ul>
 */
public final class PgBouncerJdbcUrlFinalizer {

  private PgBouncerJdbcUrlFinalizer() {}

  /**
   * Đảm bảo URL JDBC có {@code prepareThreshold=0} và {@code ApplicationName=<applicationName>}.
   * Nếu URL đã khai báo {@code prepareThreshold} khác {@code 0} thì thay bằng {@code 0}.
   */
  public static String ensurePgBouncerParams(String rawUrl, String applicationName) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new IllegalArgumentException("URL JDBC không được để trống cho " + applicationName);
    }
    String url = rawUrl.trim();
    url = upsertQueryParam(url, "prepareThreshold", "0");
    url = upsertQueryParam(url, "ApplicationName", applicationName);
    return url;
  }

  /**
   * Kiểm tra tổng {@code maxPoolSize} của 8 mô-đun nghiệp vụ không vượt trần {@code 35} kết nối/pod
   * theo TL-04.
   */
  public static void validateTotalPoolBudget(Map<ModuleName, Integer> poolSizesByModule) {
    int total = poolSizesByModule.values().stream().mapToInt(Integer::intValue).sum();
    if (total > ModuleName.MAX_TOTAL_POOL_PER_POD) {
      throw new IllegalStateException(
          "Tổng maximumPoolSize của 8 mô-đun nghiệp vụ ("
              + total
              + ") vượt trần "
              + ModuleName.MAX_TOTAL_POOL_PER_POD
              + " kết nối/pod theo TL-04: "
              + poolSizesByModule);
    }
  }

  private static String upsertQueryParam(String url, String key, String value) {
    int queryIdx = url.indexOf('?');
    if (queryIdx < 0) {
      return url + "?" + key + "=" + value;
    }
    String base = url.substring(0, queryIdx);
    String query = url.substring(queryIdx + 1);
    if (query.isEmpty()) {
      return base + "?" + key + "=" + value;
    }
    String[] parts = query.split("&");
    StringBuilder rebuilt = new StringBuilder(base).append('?');
    boolean found = false;
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        rebuilt.append('&');
      }
      String part = parts[i];
      int eq = part.indexOf('=');
      String paramName = eq >= 0 ? part.substring(0, eq) : part;
      if (paramName.equals(key)) {
        rebuilt.append(key).append('=').append(value);
        found = true;
      } else {
        rebuilt.append(part);
      }
    }
    if (!found) {
      rebuilt.append('&').append(key).append('=').append(value);
    }
    return rebuilt.toString();
  }
}
