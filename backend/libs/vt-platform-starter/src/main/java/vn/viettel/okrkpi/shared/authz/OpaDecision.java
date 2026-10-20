package vn.viettel.okrkpi.shared.authz;

import java.util.Set;

/**
 * Kết quả uỷ quyền trả về từ OPA {@code data.okrkpi.authz.decision}: {@code allow} chỉ đúng khi có
 * quyền và {@code deny} rỗng; {@code flags} chứa các nghĩa vụ ghi vết (NEN-014, NEN-040).
 */
public record OpaDecision(
    boolean allow, Set<String> deny, Set<String> flags, String bundleRevision, boolean cached) {

  public OpaDecision {
    deny = deny == null ? Set.of() : Set.copyOf(deny);
    flags = flags == null ? Set.of() : Set.copyOf(flags);
    bundleRevision =
        (bundleRevision == null || bundleRevision.isBlank()) ? "default" : bundleRevision;
  }

  /** Chỉ cho phép đi tiếp khi {@code allow == true} và tập {@code deny} rỗng. */
  public boolean isPermitted() {
    return allow && deny.isEmpty();
  }

  public OpaDecision asCached() {
    return new OpaDecision(allow, deny, flags, bundleRevision, true);
  }

  public static OpaDecision denyClosed(String reason) {
    return new OpaDecision(
        false,
        Set.of(reason == null || reason.isBlank() ? "opa_unavailable" : reason),
        Set.of(),
        "error",
        false);
  }
}
