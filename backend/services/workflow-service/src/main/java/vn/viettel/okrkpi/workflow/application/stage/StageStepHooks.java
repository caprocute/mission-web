package vn.viettel.okrkpi.workflow.application.stage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;
import vn.viettel.okrkpi.workflow.domain.stage.StageStep;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * Phần tính toán của listener {@code ${lapLichBuoc}} (tính toán thuần, đọc bản sao cục bộ): khi vào
 * một bước thì đặt nhóm ứng viên, người được giao, hạn và mốc nhắc; khi đi qua nhánh quá hạn {@code
 * quaHan_<bước>} thì tăng số lần quá hạn của bước để lần vào sau chuyển cấp.
 */
@Component
public class StageStepHooks {

  /** Tiền tố của sự kiện biên quá hạn. */
  public static final String OVERDUE_PREFIX = "quaHan_";

  private final StageScheduler scheduler;

  public StageStepHooks(StageScheduler scheduler) {
    this.scheduler = scheduler;
  }

  /**
   * Biến cần ghi khi vào bước {@code elementId} của mẫu {@code templateCode}.
   *
   * @param variables biến tiến trình hiện có
   */
  public Map<String, Object> enterStep(
      String templateCode, String elementId, Map<String, Object> variables) {
    StageTemplate template =
        StageTemplate.byCode(templateCode)
            .orElseThrow(() -> new ApprovalSetupException("Mẫu không thuộc phần 2"));
    StageStep step =
        template
            .step(elementId)
            .orElseThrow(() -> new ApprovalSetupException("Bước không thuộc mẫu"));
    UUID tenant =
        uuid(variables.get(ApprovalVariables.TENANT_ID))
            .orElseThrow(() -> new ApprovalSetupException("Thiếu tenant của tiến trình"));
    ApprovalFlow flow =
        ApprovalFlow.parse(String.valueOf(variables.get(ApprovalVariables.KHOI)))
            .orElseThrow(() -> new ApprovalSetupException("Thiếu luồng của tiến trình"));
    StagePlan plan = StagePlan.decode(String.valueOf(variables.get(StageVariables.KE_HOACH_BUOC)));
    UUID chosen =
        StageTemplate.assigneeVariable(elementId)
            .flatMap(name -> uuid(variables.get(name)))
            .orElse(null);
    Instant overall =
        Optional.ofNullable(variables.get(StageVariables.HAN_TONG_THE))
            .map(Object::toString)
            .filter(s -> !s.isBlank())
            .map(Instant::parse)
            .orElse(null);
    StageScheduler.Assignment a =
        scheduler.schedule(
            new StageScheduler.Inputs(
                tenant,
                template,
                elementId,
                plan,
                flow,
                uuid(variables.get(ApprovalVariables.DON_VI_ID))
                    .orElseThrow(() -> new ApprovalSetupException("Thiếu đơn vị")),
                uuid(variables.get(StageVariables.DON_VI_CHON)).orElse(null),
                uuid(variables.get(StageVariables.NGUOI_DUOC_DANH_GIA)).orElse(null),
                chosen,
                OverdueCounts.of(variables.get(StageVariables.SO_LAN_QUA_HAN), elementId),
                overall));
    Map<String, Object> out = new HashMap<>(StageScheduler.variables(a));
    if (step.signs() && !template.signCompletesStep()) {
      out.put(StageVariables.signedFlag(elementId), false);
    }
    out.put(StageVariables.KET_QUA, "");
    return out;
  }

  /** Giá trị mới của {@code soLanQuaHan} khi đi qua nhánh quá hạn từ sự kiện {@code boundaryId}. */
  public String overdue(String boundaryId, Object counts) {
    if (!boundaryId.startsWith(OVERDUE_PREFIX)) {
      throw new ApprovalSetupException("Nhánh quá hạn phải xuất phát từ sự kiện quaHan_<bước>");
    }
    return OverdueCounts.increment(counts, boundaryId.substring(OVERDUE_PREFIX.length()));
  }

  private static Optional<UUID> uuid(Object value) {
    if (value == null || value.toString().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value.toString()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
