package vn.viettel.okrkpi.workflow.application.approval;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue.CallbackJob;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * External worker gọi lại API duyệt hoặc trả lại của lõi cho ba mẫu phần 1 (chủ đề {@code
 * core-okr-*}, {@code core-plan-*}, {@code core-change-request-*}). Việc chạy ngoài giao dịch của
 * engine: lấy và khoá việc, gọi lõi với {@code Idempotency-Key} cố định theo (tiến trình, bước),
 * rồi báo hoàn thành hoặc lỗi (thử lại có lùi thời gian, hết lượt thì vào thư chết).
 *
 * <p>Chủ đề {@code notify-remind} do notification-service (SVC-12) xử lý, không thuộc lớp này.
 */
@Service
public class CoreCallbackService {

  /** Số việc lấy mỗi lượt. */
  static final int BATCH = 20;

  /** Thời gian khoá một việc. */
  static final Duration LOCK = Duration.ofMinutes(2);

  /** Chờ trước khi thử lại lỗi tạm thời. */
  static final Duration RETRY_AFTER = Duration.ofMinutes(1);

  private static final Logger log = LoggerFactory.getLogger(CoreCallbackService.class);

  private final ExternalJobQueue queue;
  private final CoreApprovalGateway core;

  public CoreCallbackService(ExternalJobQueue queue, CoreApprovalGateway core) {
    this.queue = queue;
    this.core = core;
  }

  /** Các chủ đề lớp này xử lý. */
  public static Set<String> topics() {
    Set<String> topics = new LinkedHashSet<>();
    for (ApprovalTemplate template : ApprovalTemplate.values()) {
      topics.add(template.approveTopic());
      topics.add(template.returnTopic());
    }
    return topics;
  }

  /** Một lượt xử lý; trả số việc đã hoàn thành. */
  public int runOnce(String workerId) {
    List<CallbackJob> jobs = queue.acquire(topics(), BATCH, LOCK, workerId);
    int done = 0;
    for (CallbackJob job : jobs) {
      if (handle(job, workerId)) {
        done++;
      }
    }
    return done;
  }

  private boolean handle(CallbackJob job, String workerId) {
    Optional<ApprovalTemplate.TopicAction> action = ApprovalTemplate.byTopic(job.topic());
    if (action.isEmpty()) {
      queue.fail(job, workerId, "Chủ đề không thuộc luồng duyệt phần 1", 0, RETRY_AFTER);
      return false;
    }
    CoreApprovalGateway.Request request;
    try {
      request = request(job, action.get());
    } catch (IllegalArgumentException e) {
      queue.fail(
          job, workerId, "Biến tiến trình thiếu hoặc sai: " + e.getMessage(), 0, RETRY_AFTER);
      return false;
    }
    CoreApprovalGateway.Outcome outcome = core.apply(request);
    switch (outcome) {
      case APPLIED -> {
        queue.complete(job, workerId);
        return true;
      }
      case RETRY -> {
        int left = Math.max(0, job.retries() - 1);
        queue.fail(job, workerId, "Lõi tạm thời không phản hồi", left, RETRY_AFTER);
      }
      case REJECTED -> {
        log.warn(
            "Lõi từ chối áp kết quả luồng {} của tiến trình {}",
            job.topic(),
            job.processInstanceId());
        queue.fail(job, workerId, "Lõi từ chối áp kết quả duyệt", 0, RETRY_AFTER);
      }
    }
    return false;
  }

  static CoreApprovalGateway.Request request(CallbackJob job, ApprovalTemplate.TopicAction action) {
    Map<String, Object> v = job.variables();
    ApprovalTemplate template = action.template();
    if (!template.businessType().equals(job.businessKey().type())) {
      throw new IllegalArgumentException("loại đối tượng không khớp mẫu");
    }
    Integer planVersion = null;
    String signMethod = null;
    UUID taskId = null;
    String cause = null;
    switch (template) {
      case DUYET_KE_HOACH_QUY -> {
        planVersion = (int) number(v.get(ApprovalVariables.KE_HOACH_PHIEN_BAN));
        signMethod = String.valueOf(v.get(ApprovalVariables.PHUONG_THUC_KY));
      }
      case DUYET_DE_NGHI -> {
        taskId = UUID.fromString(String.valueOf(v.get(ApprovalVariables.NHIEM_VU_ID)));
        cause = String.valueOf(v.get(ApprovalVariables.NGUYEN_NHAN));
      }
      case DUYET_OKR -> {
        // Không có trường riêng.
      }
    }
    return new CoreApprovalGateway.Request(
        job.tenantId(),
        template,
        action.decision(),
        job.businessKey().id(),
        number(v.get(ApprovalVariables.PHIEN_BAN_DOI_TUONG)),
        idempotencyKey(job),
        job.deciderId(),
        job.comment(),
        planVersion,
        signMethod,
        taskId,
        cause);
  }

  /**
   * Khoá cố định theo (tiến trình, bước): lần thử lại dùng lại khoá cũ (quy ước Idempotency-Key).
   */
  static UUID idempotencyKey(CallbackJob job) {
    return UUID.nameUUIDFromBytes(
        ("wf:" + job.processInstanceId() + ':' + job.elementId()).getBytes(StandardCharsets.UTF_8));
  }

  private static long number(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    throw new IllegalArgumentException("thiếu giá trị số");
  }
}
