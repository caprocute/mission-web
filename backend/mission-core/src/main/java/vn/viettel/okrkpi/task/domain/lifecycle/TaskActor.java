package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Người thực hiện lệnh: định danh cán bộ và vai trò realm của phiên. */
public record TaskActor(UUID personId, Set<String> roles) {

  public TaskActor {
    Objects.requireNonNull(personId, "personId");
    roles = Set.copyOf(roles == null ? Set.of() : roles);
  }
}
