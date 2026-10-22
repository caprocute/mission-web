package vn.viettel.okrkpi.product.application.evidence;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Người thực hiện thao tác minh chứng: định danh cán bộ và vai trò realm của phiên. */
public record EvidenceActor(UUID personId, Set<String> roles) {

  public EvidenceActor {
    Objects.requireNonNull(personId, "personId");
    roles = Set.copyOf(roles == null ? Set.of() : roles);
  }
}
