package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Hiện thực mặc định của {@link PeriodLockSagaControl}: ghi lệnh vào hộp lệnh {@code
 * config.period_lock_command} trong giao dịch đang mở của MOD-08, trạng thái {@code CHO}, mã lệnh
 * là mã tác vụ trả trong {@code Location}. {@code platform.yaml} chưa có thao tác điều khiển saga
 * nên không gọi HTTP SVC-06; saga chốt kỳ (DV-06-06, DV-06-07) nhận lệnh từ hộp lệnh (qua bộ chuyển
 * tiếp hoặc thao tác điều khiển khi hợp đồng bổ sung), xử lý rồi cập nhật trạng thái lệnh.
 */
public class QueuedPeriodLockSagaControl implements PeriodLockSagaControl {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public QueuedPeriodLockSagaControl(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
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
    return id;
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được tham số lệnh điều khiển", e);
    }
  }
}
