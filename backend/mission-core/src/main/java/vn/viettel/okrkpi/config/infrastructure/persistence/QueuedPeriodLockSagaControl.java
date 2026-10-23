package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.infrastructure.client.PeriodLockCommandRelay;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hiện thực mặc định của {@link PeriodLockSagaControl}: ghi lệnh vào hộp lệnh {@code
 * config.period_lock_command} trong giao dịch đang mở của MOD-08, trạng thái {@code CHO}, mã lệnh
 * là mã tác vụ trả trong {@code Location}. Không gọi HTTP SVC-06 trong giao dịch đang mở (TL-03):
 * sau khi commit, {@link PeriodLockCommandRelay} chuyển dòng tới {@code controlPeriodCloseSaga};
 * dòng chưa chuyển được do lỗi tạm thời chờ tác vụ định kỳ chuyển lại.
 */
public class QueuedPeriodLockSagaControl implements PeriodLockSagaControl {

  private static final Logger log = LoggerFactory.getLogger(QueuedPeriodLockSagaControl.class);

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final PeriodLockCommandRelay relay;

  public QueuedPeriodLockSagaControl(JdbcClient jdbc, ObjectMapper json) {
    this(jdbc, json, null);
  }

  /**
   * @param relay bộ chuyển tiếp gọi sau khi giao dịch commit; {@code null} thì dòng chờ tác vụ định
   *     kỳ
   */
  public QueuedPeriodLockSagaControl(
      JdbcClient jdbc, ObjectMapper json, PeriodLockCommandRelay relay) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
    this.relay = relay;
  }

  @Override
  public UUID send(
      PeriodLockOrder order,
      LockCommand command,
      List<UUID> profileIds,
      String comment,
      UUID requestedBy) {
    Objects.requireNonNull(order.sagaId(), "sagaId");
    UUID id = Ids.newUuidV7();
    Map<String, Object> payload = new LinkedHashMap<>();
    if (profileIds != null && !profileIds.isEmpty()) {
      payload.put("profileIds", profileIds);
    }
    if (comment != null && !comment.isBlank()) {
      payload.put("comment", comment);
    }
    if (command == LockCommand.BU_TRU && order.cancelRequest() != null) {
      // SVC-06 kiểm lại bốn mắt: người đề nghị huỷ đi cùng người duyệt (created_by).
      payload.put("cancelRequestedBy", order.cancelRequest().requestedBy());
    }
    payload.put("idempotencyKey", order.idempotencyKey());
    jdbc.sql(
            """
            INSERT INTO config.period_lock_command
              (id, tenant_id, created_by, lock_order_id, saga_id, command, payload, status)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'CHO')
            """)
        .param(id)
        .param(order.tenantId().value())
        .param(requestedBy)
        .param(order.id())
        .param(order.sagaId())
        .param(command.name())
        .param(write(payload))
        .update();
    afterCommit(order.tenantId());
    return id;
  }

  /** Chuyển hộp lệnh của tenant ngay sau khi giao dịch ghi dòng commit; lỗi chỉ ghi nhật ký. */
  private void afterCommit(TenantId tenant) {
    if (relay == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              TenantContext.run(tenant, () -> relay.relay(tenant));
            } catch (RuntimeException e) {
              log.warn("Chưa chuyển được hộp lệnh khoá kỳ của tenant {}", tenant, e);
            }
          }
        });
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được tham số lệnh điều khiển", e);
    }
  }
}
