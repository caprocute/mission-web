package vn.viettel.okrkpi.workflow.infrastructure.core;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.workflow.application.approval.CoreCallbackService;

/**
 * Bật external worker gọi lại lõi ở Deployment worker (cùng nơi bật async executor, TL-01 "Chia
 * theo cell"); Deployment API không chạy worker. Mỗi bản sao có mã worker riêng để Flowable khoá
 * việc theo người lấy.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "mission.workflow.core-callback.enabled", havingValue = "true")
public class CoreCallbackWorkerConfig {

  private static final Logger log = LoggerFactory.getLogger(CoreCallbackWorkerConfig.class);

  private final CoreCallbackService callbacks;
  private final String workerId = "workflow-core-callback-" + UUID.randomUUID();

  public CoreCallbackWorkerConfig(CoreCallbackService callbacks) {
    this.callbacks = callbacks;
  }

  /** Một lượt lấy việc; lỗi một lượt không dừng lịch. */
  @Scheduled(fixedDelayString = "${mission.workflow.core-callback.poll-interval:PT2S}")
  public void poll() {
    try {
      callbacks.runOnce(workerId);
    } catch (RuntimeException e) {
      log.warn("Lượt gọi lại lõi lỗi, thử lại ở lượt sau", e);
    }
  }
}
