package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bảng chuyển trạng thái theo khoá {@code from:command} kèm {@code policyVersion} để ghi vào {@code
 * state_machine_version} và truy lại bảng đã dùng.
 */
public final class TransitionTable {

  private final Map<String, Transition> byKey;
  private final String policyVersion;

  public TransitionTable(Collection<Transition> transitions, String policyVersion) {
    Objects.requireNonNull(transitions, "transitions");
    this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
    Map<String, Transition> map = new LinkedHashMap<>();
    for (Transition t : transitions) {
      if (t.from().isTerminal()) {
        throw new IllegalArgumentException("Trạng thái kết thúc không có chuyển tiếp: " + t.key());
      }
      if (map.putIfAbsent(t.key(), t) != null) {
        throw new IllegalArgumentException("Trùng dòng chuyển trạng thái " + t.key());
      }
    }
    this.byKey = Map.copyOf(map);
  }

  public Optional<Transition> find(TaskStatus from, String command) {
    return Optional.ofNullable(byKey.get(Transition.key(from, command)));
  }

  public Collection<Transition> transitions() {
    return byKey.values();
  }

  public String policyVersion() {
    return policyVersion;
  }
}
