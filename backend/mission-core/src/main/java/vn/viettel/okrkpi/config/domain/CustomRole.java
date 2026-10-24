package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Vai trò tuỳ chỉnh của tenant ({@code config.custom_role}, CORE-08-18, BS-04 ADR-34 mục 4): một
 * vai trò hệ thống gốc bị thu hẹp còn một tập chức năng, mỗi chức năng một phạm vi không rộng hơn
 * của vai trò gốc (BR-01-23).
 *
 * @param permissions khoá chức năng của ma trận → phạm vi theo hợp đồng ({@code N}, {@code Tn},
 *     {@code Cd}, {@code Dv}, {@code Gn}, {@code B})
 */
public record CustomRole(
    java.util.UUID id,
    String code,
    String name,
    String description,
    String baseRole,
    Map<String, String> permissions,
    Status status,
    Instant createdAt,
    Instant updatedAt,
    Instant retiredAt,
    int rowVersion) {

  /** Tiền tố mã, cũng là tiền tố realm role Keycloak mà OPA nhận ra. */
  public static final String PREFIX = "tc_";

  /** Độ dài tối đa của mã (cột {@code config.role_binding.role_code}). */
  public static final int MAX_CODE = 32;

  /**
   * Vai trò hệ thống dùng được làm vai trò gốc. Ngoài {@code tenant_admin}, {@code platform_admin}
   * (BS-04 mục giới hạn), ba vai trò cần duyệt thứ hai khi cấp (BR-01-14) cũng chưa dùng được trong
   * đợt này.
   */
  public static final Set<String> BASE_ROLES =
      Set.of("leader", "manager", "member", "appraiser", "inspector");

  public enum Status {
    HIEU_LUC,
    NGUNG
  }

  public CustomRole {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(baseRole, "baseRole");
    Objects.requireNonNull(status, "status");
    permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
  }

  public static boolean isCustomCode(String roleCode) {
    return roleCode != null && roleCode.startsWith(PREFIX);
  }

  public boolean active() {
    return status == Status.HIEU_LUC;
  }

  public CustomRole withContent(
      String newName, String newDescription, Map<String, String> newPermissions, Instant now) {
    return new CustomRole(
        id,
        code,
        newName,
        newDescription,
        baseRole,
        newPermissions,
        status,
        createdAt,
        now,
        retiredAt,
        rowVersion);
  }

  public CustomRole retired(Instant now) {
    return new CustomRole(
        id,
        code,
        name,
        description,
        baseRole,
        permissions,
        Status.NGUNG,
        createdAt,
        now,
        now,
        rowVersion);
  }
}
