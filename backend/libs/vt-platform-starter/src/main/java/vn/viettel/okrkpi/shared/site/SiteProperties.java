package vn.viettel.okrkpi.shared.site;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * INF-28 · Vai trò và mã TTDL của pod ({@code okrkpi.site.*}), đã được {@link
 * SiteRoleEnvironmentPostProcessor} chuẩn hoá.
 *
 * @param role {@code primary} hoặc {@code standby}
 * @param name mã TTDL ({@code hl}, {@code sn}); rỗng ở môi trường dev
 */
@ConfigurationProperties(prefix = "okrkpi.site")
public record SiteProperties(String role, String name) {

  public SiteProperties {
    role = SiteRole.parse(role).value();
    name = name == null ? "" : name;
  }

  public SiteRole siteRole() {
    return SiteRole.parse(role);
  }

  public boolean standby() {
    return siteRole() == SiteRole.STANDBY;
  }
}
