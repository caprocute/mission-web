package vn.viettel.okrkpi.workflow.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.TaskProjectionStore;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.internal.TaskTitles;

/**
 * Cập nhật bảng chiếu hộp việc theo sự kiện user task của engine ({@code TASK_CREATED}, {@code
 * TASK_ASSIGNED}, {@code TASK_COMPLETED}, xoá task). Chạy trong giao dịch của engine, nên dòng
 * chiếu thay đổi cùng {@code ACT_RU_TASK} và huỷ cùng khi giao dịch huỷ (TL-03 "Schema flowable và
 * saga").
 *
 * <p>Task không có tenant dạng UUID hoặc khoá nghiệp vụ hợp lệ (tiến trình kỹ thuật) không vào hộp
 * việc.
 */
@Service
public class TaskProjectionService {

  private static final Logger log = LoggerFactory.getLogger(TaskProjectionService.class);

  private final TaskProjectionStore store;

  public TaskProjectionService(TaskProjectionStore store) {
    this.store = store;
  }

  /** User task vừa tạo: chèn dòng chiếu. */
  public void onCreated(EngineTaskEvent event) {
    Optional<UUID> tenant = uuid(event.tenantId());
    Optional<BusinessKey> key = BusinessKey.parse(event.businessKey());
    if (tenant.isEmpty() || key.isEmpty()) {
      log.debug("Bỏ qua chiếu user task {} không thuộc đối tượng nghiệp vụ", event.taskId());
      return;
    }
    Optional<CandidateGroup> candidate =
        event.candidateGroups().stream()
            .map(CandidateGroup::parse)
            .flatMap(Optional::stream)
            .filter(g -> g.tenantId().equals(tenant.get()))
            .findFirst();
    store.upsert(
        new TaskProjection(
            event.taskId(),
            tenant.get(),
            uuid(event.assignee()).orElse(null),
            candidate.map(CandidateGroup::role).orElse(null),
            candidate.map(CandidateGroup::unitId).orElse(null),
            key.get(),
            event.templateCode(),
            TaskTitles.normalize(event.name(), TaskProjection.MAX_TITLE),
            uuid(event.periodId()).orElse(null),
            event.dueAt(),
            event.createdAt() == null ? Instant.EPOCH : event.createdAt(),
            Math.max(1, event.revision())));
  }

  /** User task được nhận hoặc giao lại. */
  public void onAssigned(EngineTaskEvent event) {
    uuid(event.tenantId())
        .ifPresent(
            tenant ->
                store.reassign(
                    tenant,
                    event.taskId(),
                    uuid(event.assignee()).orElse(null),
                    Math.max(1, event.revision())));
  }

  /** User task hoàn thành hoặc bị huỷ: bỏ khỏi hộp việc. */
  public void onRemoved(EngineTaskEvent event) {
    uuid(event.tenantId()).ifPresent(tenant -> store.delete(tenant, event.taskId()));
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
