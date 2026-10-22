package vn.viettel.okrkpi.oversight.domain.reopen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trạng thái bốn mắt của yêu cầu (CORE-07-08), lưu trong {@code scope_json}: đơn vị của người lập,
 * hai lượt duyệt với người được chỉ định và quyết định đang chờ chữ ký số. {@code reopen_approval}
 * chỉ được ghi khi chữ ký hoàn tất (BR-13-04).
 */
public record ReopenWorkflow(UUID requesterUnit, List<Slot> slots, Pending pending) {

  public static final String DECIDER = "decider";
  public static final String SECOND_APPROVER = "second_approver";

  public ReopenWorkflow {
    slots = slots == null ? List.of() : List.copyOf(slots);
  }

  public static ReopenWorkflow empty(UUID requesterUnit) {
    return new ReopenWorkflow(requesterUnit, List.of(), null);
  }

  /** Lượt 1 vai trò {@code decider}, lượt 2 vai trò {@code second_approver}. */
  public static String roleOf(int round) {
    return switch (round) {
      case 1 -> DECIDER;
      case 2 -> SECOND_APPROVER;
      default -> throw new IllegalArgumentException("round");
    };
  }

  /** Người được chỉ định cho lượt, {@code null} khi chưa chọn được. */
  public UUID assignee(int round) {
    return slots.stream()
        .filter(s -> s.round() == round)
        .map(Slot::assignee)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  public ReopenWorkflow withSlots(List<Slot> newSlots) {
    return new ReopenWorkflow(requesterUnit, newSlots, pending);
  }

  public ReopenWorkflow withPending(Pending p) {
    return new ReopenWorkflow(requesterUnit, slots, p);
  }

  /** Thay người được chỉ định của một lượt. */
  public ReopenWorkflow assign(int round, UUID assignee) {
    List<Slot> out = new ArrayList<>();
    boolean found = false;
    for (Slot s : slots) {
      if (s.round() == round) {
        out.add(new Slot(round, s.role(), assignee));
        found = true;
      } else {
        out.add(s);
      }
    }
    if (!found) {
      out.add(new Slot(round, roleOf(round), assignee));
    }
    out.sort(java.util.Comparator.comparingInt(Slot::round));
    return withSlots(out);
  }

  public record Slot(int round, String role, UUID assignee) {}

  /** Quyết định đã gửi, đang chờ SVC-10 báo ký xong (EVT-18). */
  public record Pending(
      int round,
      UUID approverId,
      String role,
      String decision,
      String comment,
      String acr,
      UUID signRequestId,
      Instant requestedAt) {}
}
