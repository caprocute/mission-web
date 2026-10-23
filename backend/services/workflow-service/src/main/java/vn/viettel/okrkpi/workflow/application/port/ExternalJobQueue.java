package vn.viettel.okrkpi.workflow.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;

/** Cổng tới hàng việc external worker của engine (TL-01 G2: engine chỉ ghi "bước X cần làm"). */
public interface ExternalJobQueue {

  /** Lấy và khoá tối đa {@code max} việc của các chủ đề cho {@code workerId}. */
  List<CallbackJob> acquire(Set<String> topics, int max, Duration lock, String workerId);

  /** Báo việc đã xong; tiến trình đi tiếp. */
  void complete(CallbackJob job, String workerId);

  /** Báo việc đã xong kèm biến kết quả (worker chọn người ghi người được giao). */
  void complete(CallbackJob job, String workerId, Map<String, Object> variables);

  /**
   * Báo việc lỗi; {@code retriesLeft = 0} đưa việc vào hàng thư chết để vận hành xử lý.
   *
   * @param retryAfter thời gian chờ trước lần thử lại
   */
  void fail(CallbackJob job, String workerId, String message, int retriesLeft, Duration retryAfter);

  /**
   * Một việc external worker đã khoá, kèm dữ liệu cần để gọi lại lõi.
   *
   * @param jobId mã việc
   * @param topic chủ đề
   * @param tenantId tenant của tiến trình
   * @param processInstanceId tiến trình
   * @param elementId bước BPMN
   * @param businessKey đối tượng nghiệp vụ
   * @param variables biến tiến trình (chỉ id, mã, tham số nhỏ)
   * @param deciderId người hoàn thành user task cuối, có thể {@code null}
   * @param comment ý kiến của user task cuối, có thể {@code null}
   * @param retries số lần thử còn lại
   */
  record CallbackJob(
      String jobId,
      String topic,
      UUID tenantId,
      String processInstanceId,
      String elementId,
      BusinessKey businessKey,
      Map<String, Object> variables,
      UUID deciderId,
      String comment,
      int retries) {

    public CallbackJob {
      Objects.requireNonNull(jobId, "jobId");
      Objects.requireNonNull(topic, "topic");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(processInstanceId, "processInstanceId");
      Objects.requireNonNull(elementId, "elementId");
      Objects.requireNonNull(businessKey, "businessKey");
      variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
  }
}
