package vn.viettel.okrkpi.config.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Vai trò đã cấp ({@code config.role_binding}, TL-03; cột bổ sung theo WEB-058). Là nguồn sự thật
 * của vai trò (phương án A). Trạng thái không lưu thành cột mà suy ra từ {@code approved_by},
 * {@code end_kind}, {@code valid_from}, {@code valid_to} (quyết định WEB-058 điểm 2).
 */
public record RoleBinding(
    UUID id,
    UUID personId,
    String roleCode,
    UUID orgUnitId,
    Scope scope,
    String flow,
    Instant validFrom,
    Instant validTo,
    UUID grantedBy,
    Instant grantedAt,
    String reason,
    UUID approvedBy,
    Instant approvedAt,
    UUID endedBy,
    Instant endedAt,
    EndKind endKind,
    String endReason,
    int rowVersion) {

  /** Chín vai trò hệ thống của CHECK {@code ck_role_binding__role_code}. */
  public static final Set<String> ROLES =
      Set.of(
          "tenant_admin",
          "policy_author",
          "leader",
          "manager",
          "member",
          "appraiser",
          "decider",
          "second_approver",
          "inspector");

  /** Ba vai trò chỉ hiệu lực sau duyệt thứ hai (BR-01-14). */
  public static final Set<String> SENSITIVE = Set.of("decider", "second_approver", "policy_author");

  /** Cửa sổ "sắp hết hạn" của thẻ lọc nhanh SCR-0105. */
  public static final Duration EXPIRING_SOON = Duration.ofDays(30);

  public enum Scope {
    DON_VI,
    DON_VI_VA_CAP_DUOI
  }

  public enum EndKind {
    THU_HOI,
    TU_CHOI
  }

  public enum Status {
    CHO_DUYET,
    SAP_HIEU_LUC,
    HIEU_LUC,
    HET_HAN,
    DA_THU_HOI,
    TU_CHOI
  }

  public RoleBinding {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(roleCode, "roleCode");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(validFrom, "validFrom");
    Objects.requireNonNull(validTo, "validTo");
    Objects.requireNonNull(grantedBy, "grantedBy");
  }

  public boolean sensitive() {
    return SENSITIVE.contains(roleCode);
  }

  public boolean pending() {
    return endKind == null && sensitive() && approvedBy == null;
  }

  public boolean ended() {
    return endKind != null;
  }

  public Status status(Instant now) {
    if (endKind == EndKind.TU_CHOI) {
      return Status.TU_CHOI;
    }
    if (endKind == EndKind.THU_HOI) {
      return Status.DA_THU_HOI;
    }
    if (pending()) {
      return Status.CHO_DUYET;
    }
    if (now.isBefore(validFrom)) {
      return Status.SAP_HIEU_LUC;
    }
    return now.isBefore(validTo) ? Status.HIEU_LUC : Status.HET_HAN;
  }

  /** Bản ghi đang trao quyền tại {@code now} (OPA, đồng bộ Keycloak). */
  public boolean effective(Instant now) {
    return status(now) == Status.HIEU_LUC;
  }

  /** Bản sau tác vụ hết hạn (chỉ tăng {@code row_version}, để consumer không coi là sự kiện cũ). */
  public RoleBinding expiredMarked() {
    return new RoleBinding(
        id,
        personId,
        roleCode,
        orgUnitId,
        scope,
        flow,
        validFrom,
        validTo,
        grantedBy,
        grantedAt,
        reason,
        approvedBy,
        approvedAt,
        endedBy,
        endedAt,
        endKind,
        endReason,
        rowVersion + 1);
  }

  public RoleBinding approved(UUID approver, Instant at) {
    return new RoleBinding(
        id,
        personId,
        roleCode,
        orgUnitId,
        scope,
        flow,
        validFrom,
        validTo,
        grantedBy,
        grantedAt,
        reason,
        approver,
        at,
        null,
        null,
        null,
        null,
        rowVersion + 1);
  }

  public RoleBinding endedBy(UUID actor, Instant at, EndKind kind, String why) {
    Instant to = kind == EndKind.THU_HOI && at.isBefore(validTo) ? at : validTo;
    Instant from = to.isAfter(validFrom) ? validFrom : to.minusMillis(1);
    return new RoleBinding(
        id,
        personId,
        roleCode,
        orgUnitId,
        scope,
        flow,
        from,
        to,
        grantedBy,
        grantedAt,
        reason,
        approvedBy,
        approvedAt,
        actor,
        at,
        kind,
        why,
        rowVersion + 1);
  }
}
