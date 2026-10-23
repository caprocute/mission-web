package vn.viettel.okrkpi.workflow.application.template;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;

/**
 * Ghi tiến độ cell trong giao dịch riêng: bước saga ném lỗi thì giao dịch của bước bị huỷ, nhưng
 * trạng thái {@code LOI} và lý do vẫn phải hiện trên SCR-1505.
 */
public class RolloutProgress {

  private static final int MAX_ERROR = 500;

  private final TemplateRolloutStore rollouts;

  public RolloutProgress(TemplateRolloutStore rollouts) {
    this.rollouts = Objects.requireNonNull(rollouts, "rollouts");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID templateId, String cellCode, String reason) {
    String text = reason == null ? null : reason.substring(0, Math.min(reason.length(), MAX_ERROR));
    rollouts.mark(templateId, cellCode, RolloutStatus.LOI, text);
  }
}
