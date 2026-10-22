package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Gói Policy Pack ({@code policy.policy_pack}, ADR-19): gói gốc toàn cục hoặc gói của tenant kế
 * thừa gói cha qua {@code parentPackId} (BR-15-13).
 */
public record PolicyPack(
    UUID id,
    String code,
    String name,
    String sector,
    String flow,
    PackOrigin origin,
    UUID parentPackId,
    String legalBasis,
    Instant createdAt,
    UUID createdBy,
    long rowVersion) {

  public PolicyPack {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(origin, "origin");
  }

  /** Gói kế thừa: phiên bản của nó chỉ được sửa quy tắc {@code overridable} của gói cha. */
  public boolean inherits() {
    return parentPackId != null;
  }
}
