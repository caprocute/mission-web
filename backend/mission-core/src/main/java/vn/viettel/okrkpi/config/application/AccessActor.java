package vn.viettel.okrkpi.config.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * Người gọi thao tác quản trị vai trò và uỷ quyền (CORE-08-16): mã, vai trò của ngữ cảnh đang chọn,
 * {@code acr} và {@code auth_time} (giây epoch). Kiểm vai trò và bước lên ở đây chỉ là lớp phòng
 * thủ thứ hai sau OPA, vì {@code @RequiresPermission} chưa có hiệu lực trong mission-core.
 */
public record AccessActor(UUID userId, List<String> roles, String acr, Long authTime) {

  /** BR-01-15: thao tác thay đổi quyền cần phiên {@code aal2} trong 15 phút. */
  public static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);

  public AccessActor {
    Objects.requireNonNull(userId, "userId");
    roles = roles == null ? List.of() : List.copyOf(roles);
  }

  public boolean has(String role) {
    return roles.contains(role);
  }

  /** 403 {@code COMMON-403-OUT-OF-SCOPE} khi không giữ vai trò nào trong {@code allowed}. */
  public void requireAny(String... allowed) {
    for (String r : allowed) {
      if (has(r)) {
        return;
      }
    }
    throw new BusinessException(
        "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền thực hiện thao tác này.");
  }

  /** 401 bước lên khi phiên chưa đạt {@code aal2} hoặc đã quá 15 phút (BR-01-15). */
  public void requireStepUp(String operationId, Clock clock) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(AuthzSubjectResolver.normalizeAcr(acr))) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Thao tác thay đổi quyền cần xác thực nhiều lớp.");
    }
    long age = clock.instant().getEpochSecond() - (authTime == null ? 0 : authTime);
    if (authTime == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }
}
