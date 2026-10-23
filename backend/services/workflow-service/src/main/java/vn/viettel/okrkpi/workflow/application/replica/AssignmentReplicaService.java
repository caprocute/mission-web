package vn.viettel.okrkpi.workflow.application.replica;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.AssignmentReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;

/**
 * Áp EVT-15 {@code org.assignment.changed.v1} và phần phân công của {@code org.bootstrap.v1} vào
 * bản sao {@code org_replica.assignment_v} của workflow-service, nguồn để kiểm người nhận khi
 * chuyển người xử lý (BS-01 SCR-1506, DV-06-10). Sự kiện mang trạng thái đầy đủ (ECST) nên chỉ cần
 * upsert có điều kiện theo {@code source_version}; chống trùng, thứ tự phiên bản, thử lại và thư
 * chết do inbox của thư viện nền lo. Không mở giao dịch riêng: chạy trong giao dịch của consumer
 * inbox, nên dấu vết {@code inbox_processed} và bản sao commit hoặc huỷ cùng nhau.
 */
@Service
public class AssignmentReplicaService {

  private static final Logger log = LoggerFactory.getLogger(AssignmentReplicaService.class);

  private final AssignmentReplicaStore store;

  public AssignmentReplicaService(AssignmentReplicaStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * Áp một lát hiệu lực phân công.
   *
   * @param tenantId tenant của sự kiện
   * @param assignment trạng thái phân công
   * @param eventId mã sự kiện đã áp (cột {@code source_event_id})
   * @return {@code true} khi bản sao đổi, {@code false} khi sự kiện cũ hơn bản đang có
   */
  public boolean apply(UUID tenantId, AssignmentReplica assignment, UUID eventId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(assignment, "assignment");
    Objects.requireNonNull(eventId, "eventId");
    boolean applied = store.upsert(tenantId, assignment, eventId);
    if (!applied) {
      log.debug(
          "Bỏ qua phân công {} phiên bản nguồn {}: bản sao đã mới hơn",
          assignment.id(),
          assignment.sourceVersion());
    }
    return applied;
  }
}
