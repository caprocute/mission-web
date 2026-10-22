package vn.viettel.okrkpi.workflow.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lịch sử duyệt của một đối tượng nghiệp vụ, đọc từ lịch sử engine mức {@code audit}.
 *
 * @param businessKey đối tượng nghiệp vụ
 * @param templateCode mã mẫu luồng (khoá định nghĩa BPMN)
 * @param templateVersion phiên bản định nghĩa
 * @param ended tiến trình đã kết thúc
 * @param initiatorId người khởi tạo tiến trình, có thể {@code null}
 * @param entries các bước theo thứ tự thời gian
 */
public record ProcessHistory(
    BusinessKey businessKey,
    String templateCode,
    Integer templateVersion,
    boolean ended,
    UUID initiatorId,
    List<Entry> entries) {

  public ProcessHistory {
    Objects.requireNonNull(businessKey, "businessKey");
    entries =
        entries == null
            ? List.of()
            : entries.stream().sorted(Comparator.comparing(Entry::at)).toList();
  }

  /** Người đã tham gia (khởi tạo hoặc xử lý một bước), dùng để kiểm quyền đọc. */
  public List<UUID> participants() {
    return entries.stream().map(Entry::actorId).filter(Objects::nonNull).distinct().toList();
  }

  /**
   * Một bước của lịch sử.
   *
   * @param at thời điểm
   * @param action loại bước
   * @param actorId người thực hiện, có thể {@code null}
   * @param activityName tên bước trong mẫu, có thể {@code null}
   * @param comment ý kiến kèm theo, có thể {@code null}
   */
  public record Entry(
      Instant at, HistoryAction action, UUID actorId, String activityName, String comment) {

    public Entry {
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(action, "action");
    }
  }
}
