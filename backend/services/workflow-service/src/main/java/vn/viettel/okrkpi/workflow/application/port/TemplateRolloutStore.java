package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;

/** Kho tiến độ triển khai {@code flowable.wf_template_rollout} (bổ sung BS-01). */
public interface TemplateRolloutStore {

  /** Tạo dòng {@code CHO} cho mỗi cell theo thứ tự; gọi lại không tạo trùng. */
  void plan(UUID templateId, List<String> cells, UUID sagaId, UUID actor);

  List<CellRollout> list(UUID templateId);

  Optional<CellRollout> find(UUID templateId, String cellCode);

  void deployed(
      UUID templateId, String cellCode, String deploymentId, Integer previousVersionNo, Instant at);

  void mark(UUID templateId, String cellCode, RolloutStatus status, String error);
}
