package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.EngineTaskEvent;
import vn.viettel.okrkpi.workflow.application.TaskProjectionService;

/**
 * Listener sự kiện user task của engine (TL-01 SVC-06, TL-03 "Schema flowable và saga"): {@code
 * TASK_CREATED}, {@code TASK_ASSIGNED}, {@code TASK_COMPLETED} và xoá task cập nhật {@code
 * flowable.wf_task_projection} trong cùng giao dịch engine. {@link #isFailOnException()} đúng để
 * lỗi ghi bảng chiếu huỷ luôn thao tác engine, giữ hai bảng nhất quán.
 *
 * <p>Đọc khoá nghiệp vụ và biến {@code periodId} qua thực thể execution trong bộ đệm của lệnh (chưa
 * ghi xuống CSDL lúc tạo tiến trình), nên dùng {@link CommandContextUtil} thay cho truy vấn.
 */
@Component
public class TaskProjectionListener implements FlowableEventListener {

  /** Biến tiến trình mang kỳ đánh giá (DV-06-03 đặt khi khởi tạo). */
  public static final String PERIOD_VARIABLE = "periodId";

  private static final Set<FlowableEngineEventType> TYPES =
      Set.of(
          FlowableEngineEventType.TASK_CREATED,
          FlowableEngineEventType.TASK_ASSIGNED,
          FlowableEngineEventType.TASK_COMPLETED,
          FlowableEngineEventType.ENTITY_DELETED);

  private final TaskProjectionService projections;

  public TaskProjectionListener(TaskProjectionService projections) {
    this.projections = projections;
  }

  @Override
  public void onEvent(FlowableEvent event) {
    if (!(event instanceof FlowableEntityEvent entityEvent)
        || !(entityEvent.getEntity() instanceof TaskEntity task)
        || !(event.getType() instanceof FlowableEngineEventType type)) {
      return;
    }
    switch (type) {
      case TASK_CREATED -> projections.onCreated(snapshot(task, true));
      case TASK_ASSIGNED -> projections.onAssigned(snapshot(task, false));
      case TASK_COMPLETED, ENTITY_DELETED -> projections.onRemoved(light(task));
      default -> {
        // Loại khác không đăng ký.
      }
    }
  }

  private static EngineTaskEvent snapshot(TaskEntity task, boolean created) {
    String businessKey = null;
    String period = null;
    if (task.getProcessInstanceId() != null) {
      ExecutionEntity instance =
          CommandContextUtil.getExecutionEntityManager().findById(task.getProcessInstanceId());
      if (instance != null) {
        businessKey = instance.getBusinessKey();
        Object value = instance.getVariable(PERIOD_VARIABLE);
        period = value == null ? null : value.toString();
      }
    }
    String templateCode = null;
    if (task.getProcessDefinitionId() != null) {
      ProcessDefinition definition =
          ProcessDefinitionUtil.getProcessDefinition(task.getProcessDefinitionId());
      templateCode = definition == null ? null : definition.getKey();
    }
    List<String> groups =
        created
            ? task.getCandidates().stream()
                .map(IdentityLink::getGroupId)
                .filter(Objects::nonNull)
                .toList()
            : List.of();
    return new EngineTaskEvent(
        task.getId(),
        task.getTenantId(),
        task.getAssignee(),
        groups,
        businessKey,
        templateCode,
        task.getName(),
        period,
        instant(task.getDueDate()),
        instant(task.getCreateTime()),
        revisionAfterFlush(task));
  }

  private static EngineTaskEvent light(TaskEntity task) {
    return new EngineTaskEvent(
        task.getId(), task.getTenantId(), null, List.of(), null, null, null, null, null, null, 0);
  }

  /**
   * {@code REV_} sẽ nằm trong CSDL sau khi lệnh ghi xuống: dòng mới chèn giữ phiên bản hiện tại,
   * dòng cập nhật tăng thêm một.
   */
  static int revisionAfterFlush(TaskEntity task) {
    return task.isInserted() ? task.getRevision() : task.getRevisionNext();
  }

  private static Instant instant(Date date) {
    return date == null ? null : date.toInstant();
  }

  @Override
  public boolean isFailOnException() {
    return true;
  }

  @Override
  public boolean isFireOnTransactionLifecycleEvent() {
    return false;
  }

  @Override
  public String getOnTransaction() {
    return null;
  }

  @Override
  public Collection<? extends FlowableEventType> getTypes() {
    return TYPES;
  }
}
