package vn.viettel.okrkpi.workflow.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tác vụ duyệt hàng loạt ({@code flowable.wf_task_batch}): trạng thái theo lược đồ {@code Job} và
 * kết quả từng mục theo lược đồ {@code BatchItemResult} của {@code common.yaml}.
 *
 * @param id mã tác vụ
 * @param tenantId tenant
 * @param requestedBy người khởi tạo, chỉ người này xem được tác vụ
 * @param decision quyết định áp cho mọi mục
 * @param status trạng thái
 * @param totalItems số mục
 * @param doneItems số mục đã hoàn thành
 * @param failedItems số mục lỗi
 * @param results kết quả từng mục đã xử lý
 * @param updatedAt thời điểm cập nhật gần nhất
 */
public record BatchJob(
    UUID id,
    UUID tenantId,
    UUID requestedBy,
    Decision decision,
    Status status,
    int totalItems,
    int doneItems,
    int failedItems,
    List<ItemResult> results,
    Instant updatedAt) {

  /** Loại tác vụ ghi ở {@code Job.type} (suy luận, hợp đồng chỉ nêu ví dụ). */
  public static final String JOB_TYPE = "WORKFLOW_BATCH_COMPLETE";

  /**
   * Tác vụ chưa kết thúc mà không cập nhật quá lâu coi là hỏng (pod dừng giữa chừng): báo thất bại
   * để người dùng gửi lại các mục còn lại.
   */
  public static final Duration STALE_AFTER = Duration.ofMinutes(10);

  public BatchJob {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(updatedAt, "updatedAt");
    results = results == null ? List.of() : List.copyOf(results);
    if (totalItems < 1 || totalItems > CompletionRules.MAX_BATCH_ITEMS) {
      throw new IllegalArgumentException("Số mục của lô ngoài 1..200");
    }
  }

  /** Tác vụ vừa nhận. */
  public static BatchJob accepted(
      UUID id, UUID tenantId, UUID requestedBy, Decision decision, int totalItems, Instant at) {
    return new BatchJob(
        id, tenantId, requestedBy, decision, Status.CHO, totalItems, 0, 0, List.of(), at);
  }

  /** Trạng thái hiển thị tại thời điểm {@code now}: tác vụ treo quá lâu được báo thất bại. */
  public Status effectiveStatus(Instant now) {
    if (!status.terminal() && updatedAt.plus(STALE_AFTER).isBefore(now)) {
      return Status.THAT_BAI;
    }
    return status;
  }

  /** Phần trăm tiến độ 0..100. */
  public int progress() {
    return (doneItems + failedItems) * 100 / totalItems;
  }

  /** Trạng thái theo {@code JobStatus} của hợp đồng (bỏ {@code DA_HUY}: lô không huỷ được). */
  public enum Status {
    CHO,
    DANG_CHAY,
    HOAN_TAT,
    THAT_BAI;

    public boolean terminal() {
      return this == HOAN_TAT || this == THAT_BAI;
    }
  }

  /**
   * Kết quả một mục ({@code BatchItemResult}).
   *
   * @param taskId mã user task
   * @param status mã HTTP tương đương
   * @param code mã lỗi của danh mục khi lỗi, {@code null} khi thành công
   */
  public record ItemResult(String taskId, int status, String code) {

    public ItemResult {
      Objects.requireNonNull(taskId, "taskId");
    }

    public boolean succeeded() {
      return status >= 200 && status < 300;
    }
  }
}
