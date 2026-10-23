package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.Objects;
import java.util.UUID;

/** Bên được yêu cầu giải trình (SCR-1202 trường 4): một người hoặc một đơn vị. */
public record ExplanationParty(Kind kind, UUID id) {

  public enum Kind {
    NGUOI,
    DON_VI
  }

  public ExplanationParty {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
  }
}
