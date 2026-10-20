package vn.viettel.okrkpi.shared.authz;

import java.util.Optional;
import java.util.Set;

/**
 * Ngữ cảnh giữ quyết định uỷ quyền hiện hành ({@link OpaDecision}) trong phạm vi lời gọi {@link
 * RequiresPermission} để tầng ứng dụng đọc các cờ nghĩa vụ như {@code exclude_c2_fields}, {@code
 * inferred_grant}, {@code sod_small_unit_exception} (NEN-014).
 */
public final class AuthzDecisionContext {

  private static final ThreadLocal<OpaDecision> CURRENT = new ThreadLocal<>();

  private AuthzDecisionContext() {}

  public static Optional<OpaDecision> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static Set<String> currentFlags() {
    return current().map(OpaDecision::flags).orElse(Set.of());
  }

  public static boolean hasFlag(String flag) {
    return currentFlags().contains(flag);
  }

  public static <T> T callWith(OpaDecision decision, ScopedCallable<T> callable) throws Throwable {
    OpaDecision prev = CURRENT.get();
    CURRENT.set(decision);
    try {
      return callable.call();
    } finally {
      if (prev == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(prev);
      }
    }
  }

  @FunctionalInterface
  public interface ScopedCallable<T> {
    T call() throws Throwable;
  }
}
