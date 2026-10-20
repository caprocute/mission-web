package vn.viettel.okrkpi.shared.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.TenantIdProvider;

/**
 * Xác định (tenant, người gọi) cho {@link Idempotent}. Mặc định: tenant từ {@link TenantIdProvider}
 * (ngữ cảnh tenant đã kiểm của NEN-010), người gọi là tên {@link Principal} của yêu cầu, tức claim
 * {@code sub} của JWT khi dùng Spring Security resource server. Không đọc giá trị client tự khai
 * trong header.
 */
@FunctionalInterface
public interface IdempotencyCallerResolver {

  /** Người gọi đã xác thực, rỗng khi chưa đủ thông tin. */
  Optional<Caller> resolve(HttpServletRequest request);

  /** Tenant và người gọi. */
  record Caller(TenantId tenant, ActorId actor) {}

  /** Bộ xác định mặc định từ ngữ cảnh tenant và {@link Principal}. */
  static IdempotencyCallerResolver fromPrincipal(TenantIdProvider tenants) {
    return request -> {
      Optional<TenantId> tenant = tenants.currentTenant(request);
      Principal principal = request.getUserPrincipal();
      if (tenant.isEmpty() || principal == null || principal.getName() == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new Caller(tenant.get(), ActorId.of(Ids.parse(principal.getName().trim()))));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    };
  }
}
