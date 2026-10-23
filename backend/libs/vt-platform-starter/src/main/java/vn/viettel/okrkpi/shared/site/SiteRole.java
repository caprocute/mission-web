package vn.viettel.okrkpi.shared.site;

import java.util.Locale;

/**
 * INF-28 · QA-15 · Vai trò của trung tâm dữ liệu (TTDL) nơi pod chạy, do ApplicationSet suy ra từ
 * {@code cells/<cell>/cell.yaml} (primarySite) và truyền qua {@code global.siteRole} thành biến
 * {@code VT_SITE_ROLE}. Ở TTDL chờ, CSDL là bản sao chỉ đọc và Kafka là đích của MirrorMaker 2, nên
 * ứng dụng không được tiêu thụ sự kiện, chạy job định kỳ hay migration.
 */
public enum SiteRole {
  PRIMARY,
  STANDBY;

  /**
   * Đọc giá trị {@code primary}/{@code standby}; rỗng là primary, giá trị lạ thì dừng khởi động.
   */
  public static SiteRole parse(String value) {
    if (value == null || value.isBlank()) {
      return PRIMARY;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "primary" -> PRIMARY;
      case "standby" -> STANDBY;
      default ->
          throw new IllegalStateException(
              "VT_SITE_ROLE/okrkpi.site.role phải là primary hoặc standby, nhận: " + value);
    };
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
