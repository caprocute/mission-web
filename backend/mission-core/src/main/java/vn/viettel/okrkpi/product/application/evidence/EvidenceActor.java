package vn.viettel.okrkpi.product.application.evidence;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Người thực hiện thao tác minh chứng: định danh cán bộ, vai trò realm của phiên, mức xác thực
 * ({@code acr}) và thời điểm xác thực ({@code auth_time}, giây epoch) để kiểm AAL2 (BR-07-11).
 */
public record EvidenceActor(UUID personId, Set<String> roles, String acr, Long authTime) {

  public EvidenceActor {
    Objects.requireNonNull(personId, "personId");
    roles = Set.copyOf(roles == null ? Set.of() : roles);
  }

  /** Người thực hiện không mang thông tin xác thực bước lên. */
  public EvidenceActor(UUID personId, Set<String> roles) {
    this(personId, roles, null, null);
  }
}
