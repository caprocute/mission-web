package vn.viettel.okrkpi.workflow.infrastructure.web;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Người gọi hiện tại theo {@code sub} của token (ADR-08); không tin giá trị client tự khai. */
public final class CurrentCaller {

  /** Chuỗi bảo mật đã chặn yêu cầu chưa xác thực; tới đây mà thiếu {@code sub} là token sai. */
  private static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";

  private CurrentCaller() {}

  public static UUID actor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwt) {
      try {
        return UUID.fromString(jwt.getToken().getSubject());
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new BusinessException(OUT_OF_SCOPE);
      }
    }
    throw new BusinessException(OUT_OF_SCOPE);
  }
}
