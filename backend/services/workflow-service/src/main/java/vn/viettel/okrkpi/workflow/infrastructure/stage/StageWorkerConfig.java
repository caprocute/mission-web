package vn.viettel.okrkpi.workflow.infrastructure.stage;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.workflow.application.stage.AssigneeSelectionService;

/**
 * Bật worker chọn người ({@code wf-chon-nguoi}) ở Deployment worker, cùng cờ với worker gọi lại
 * lõi.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "mission.workflow.core-callback.enabled", havingValue = "true")
public class StageWorkerConfig {

  private static final Logger log = LoggerFactory.getLogger(StageWorkerConfig.class);

  private final AssigneeSelectionService selection;
  private final String workerId = "workflow-chon-nguoi-" + UUID.randomUUID();

  public StageWorkerConfig(AssigneeSelectionService selection) {
    this.selection = selection;
  }

  /** Một lượt chọn người; lỗi một lượt không dừng lịch. */
  @Scheduled(fixedDelayString = "${mission.workflow.core-callback.poll-interval:PT2S}")
  public void poll() {
    try {
      selection.runOnce(workerId);
    } catch (RuntimeException e) {
      log.warn("Lượt chọn người lỗi, thử lại ở lượt sau", e);
    }
  }
}
