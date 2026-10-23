package vn.viettel.okrkpi.config.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient.ControlOutcome;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Bộ chuyển tiếp hộp lệnh {@code config.period_lock_command} tới saga {@code CHOT_KY} của SVC-06
 * ({@code controlPeriodCloseSaga}, CORE-08-05, wiki trang 14 mục DV-06-07 điểm 8): đọc các dòng
 * {@code delivered_at IS NULL} theo {@code created_at} sau khi giao dịch ghi dòng đã commit, gọi
 * HTTP ngoài giao dịch với {@code Idempotency-Key} là mã dòng. 202 thì đặt {@code delivered_at} và
 * {@code DANG_CHAY} ({@code TAM_DUNG}, {@code TIEP_TUC} xong ngay nên {@code HOAN_TAT}); lỗi nghiệp
 * vụ 4xx thì {@code THAT_BAI}, ghi nhật ký khoá kỳ và không thử lại; lỗi mạng hoặc 5xx để nguyên
 * dòng cho lần chạy sau. Chạy lại nhiều lần, nhiều pod vẫn chỉ có tác dụng một lần nhờ khoá chống
 * lặp của SVC-06.
 */
public class PeriodLockCommandRelay {

  /** Cổng gửi một lệnh; {@link #available()} sai khi chưa cấu hình địa chỉ SVC-06. */
  public interface Sender {

    boolean available();

    ControlOutcome send(TenantId tenant, UUID sagaId, UUID commandId, Map<String, Object> body);
  }

  /** Loại sự kiện nhật ký khoá kỳ khi tiến trình khoá kỳ không nhận lệnh. */
  static final String KIND_REJECTED = "LENH_BI_TU_CHOI";

  static final int BATCH = 50;

  private static final Logger log = LoggerFactory.getLogger(PeriodLockCommandRelay.class);

  private final JdbcClient jdbc;
  private final TransactionOperations tx;
  private final Sender sender;
  private final ObjectMapper json;
  private final Clock clock;

  public PeriodLockCommandRelay(
      JdbcClient jdbc, TransactionOperations tx, Sender sender, ObjectMapper json, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.sender = Objects.requireNonNull(sender, "sender");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một dòng hộp lệnh chưa chuyển. */
  record Pending(
      UUID id,
      UUID lockOrderId,
      UUID sagaId,
      LockCommand command,
      UUID createdBy,
      JsonNode payload) {}

  /**
   * Chuyển các lệnh chưa chuyển của tenant (gọi trong {@code TenantContext} của tenant đó). Dừng ở
   * lệnh đầu tiên gặp lỗi tạm thời để giữ thứ tự lệnh của cùng saga.
   *
   * @return số lệnh đã chuyển xong (nhận hoặc bị từ chối)
   */
  public int relay(TenantId tenant) {
    if (!sender.available()) {
      return 0;
    }
    List<Pending> pending = tx.execute(s -> pending(tenant));
    int done = 0;
    for (Pending p : pending == null ? List.<Pending>of() : pending) {
      ControlOutcome outcome;
      try {
        outcome = sender.send(tenant, p.sagaId(), p.id(), body(p));
      } catch (DependencyUnavailableException e) {
        log.warn("Chưa chuyển được lệnh {} tới saga chốt kỳ, sẽ thử lại", p.id(), e);
        break;
      }
      ControlOutcome result = outcome;
      tx.executeWithoutResult(s -> record(tenant, p, result));
      done++;
    }
    return done;
  }

  private List<Pending> pending(TenantId tenant) {
    return jdbc.sql(
            """
            SELECT id, lock_order_id, saga_id, command, created_by, payload::text AS payload
              FROM config.period_lock_command
             WHERE tenant_id = ? AND delivered_at IS NULL AND status = 'CHO'
             ORDER BY created_at, id
             LIMIT ?
            """)
        .param(tenant.value())
        .param(BATCH)
        .query(
            (rs, i) ->
                new Pending(
                    rs.getObject("id", UUID.class),
                    rs.getObject("lock_order_id", UUID.class),
                    rs.getObject("saga_id", UUID.class),
                    LockCommand.valueOf(rs.getString("command")),
                    rs.getObject("created_by", UUID.class),
                    read(rs.getString("payload"))))
        .list();
  }

  /** Thân {@code PeriodCloseSagaCommand} theo ánh xạ cột của wiki trang 14. */
  static Map<String, Object> body(Pending p) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("command", p.command().name());
    body.put("commandId", p.id());
    JsonNode payload = p.payload();
    if (p.command() == LockCommand.BU_TRU) {
      String requester = payload.path("cancelRequestedBy").asText(null);
      body.put("requestedBy", requester == null ? p.createdBy() : UUID.fromString(requester));
      body.put("approvedBy", p.createdBy());
    } else {
      body.put("requestedBy", p.createdBy());
    }
    JsonNode profiles = payload.path("profileIds");
    if (profiles.isArray() && !profiles.isEmpty()) {
      body.put("profileIds", texts(profiles));
    }
    String comment = payload.path("comment").asText(null);
    if (comment != null && !comment.isBlank()) {
      body.put("reason", comment);
    }
    return body;
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  private void record(TenantId tenant, Pending p, ControlOutcome outcome) {
    Instant now = clock.instant();
    if (outcome.accepted()) {
      boolean immediate =
          p.command() == LockCommand.TAM_DUNG || p.command() == LockCommand.TIEP_TUC;
      jdbc.sql(
              """
              UPDATE config.period_lock_command
                 SET delivered_at = ?, status = ?, finished_at = ?, updated_at = ?
               WHERE tenant_id = ? AND id = ? AND delivered_at IS NULL
              """)
          .param(Timestamp.from(now))
          .param(immediate ? "HOAN_TAT" : "DANG_CHAY")
          .param(immediate ? Timestamp.from(now) : null)
          .param(Timestamp.from(now))
          .param(tenant.value())
          .param(p.id())
          .update();
      return;
    }
    log.warn(
        "Saga chốt kỳ từ chối lệnh {} {}: HTTP {} {}",
        p.command(),
        p.id(),
        outcome.status(),
        outcome.code());
    int changed =
        jdbc.sql(
                """
                UPDATE config.period_lock_command
                   SET delivered_at = ?, status = 'THAT_BAI', finished_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND delivered_at IS NULL
                """)
            .param(Timestamp.from(now))
            .param(Timestamp.from(now))
            .param(Timestamp.from(now))
            .param(tenant.value())
            .param(p.id())
            .update();
    if (changed == 0) {
      return;
    }
    jdbc.sql(
            """
            INSERT INTO config.period_lock_event
              (id, tenant_id, lock_order_id, occurred_at, kind, event, evt_code, actor_id)
            VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
            """)
        .param(Ids.newUuidV7())
        .param(tenant.value())
        .param(p.lockOrderId())
        .param(Timestamp.from(now))
        .param(KIND_REJECTED)
        .param(rejectedText(p.command(), outcome.code()))
        .update();
  }

  /** Câu nhật ký cho người dùng; mã lỗi chỉ ghi vào nhật ký hệ thống (AGENTS.md quy tắc 9). */
  static String rejectedText(LockCommand command, String code) {
    String reason =
        switch (code == null ? "" : code) {
          case "PERIOD-409-POINT-OF-NO-RETURN" ->
              "tiến trình đã qua bước ký số lô nên không huỷ được nữa";
          case "PERIOD-403-SAME-PERSON" -> "người duyệt huỷ trùng người đề nghị huỷ";
          case "WORKFLOW-409-VERSION-CONFLICT" -> "tiến trình không ở trạng thái nhận lệnh này";
          default -> "tiến trình khoá kỳ không nhận lệnh";
        };
    return "Không thực hiện được: " + lower(command.label()) + ", vì " + reason + ".";
  }

  private static String lower(String label) {
    return label.isEmpty() ? label : Character.toLowerCase(label.charAt(0)) + label.substring(1);
  }

  private JsonNode read(String payload) {
    try {
      return json.readTree(payload == null ? "{}" : payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Tham số lệnh điều khiển hỏng", e);
    }
  }
}
