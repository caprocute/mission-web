package vn.viettel.okrkpi.workflow.infrastructure.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStep;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * {@link SagaRepository} trên JDBC ({@code flowable.saga_instance}, {@code flowable.saga_step}).
 *
 * <p>Cột {@code context} giữ tham số của saga ở khoá {@code thamSo} và cờ điều khiển của khung ở
 * khoá {@code _dieuKhien} (khoá độc quyền, người khởi tạo, tạm dừng, đang bù trừ). TL-03 chỉ ghi
 * {@code context} là "tham số"; việc đặt cờ điều khiển vào đây thay vì thêm cột là suy luận để giữ
 * đúng từ điển dữ liệu (wiki trang 14).
 */
public class JdbcSagaRepository implements SagaRepository {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcSagaRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<SagaInstance> findById(UUID sagaId) {
    return jdbc.sql("SELECT * FROM flowable.saga_instance WHERE id = ?")
        .param(sagaId)
        .query((rs, i) -> instance(rs))
        .optional();
  }

  @Override
  public Optional<SagaInstance> findByCorrelation(SagaType type, UUID correlationId) {
    return jdbc.sql(
            "SELECT * FROM flowable.saga_instance WHERE saga_type = ? AND correlation_id = ?")
        .param(type.name())
        .param(correlationId)
        .query((rs, i) -> instance(rs))
        .optional();
  }

  @Override
  public void lockExclusiveKey(SagaType type, String exclusiveKey) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("saga:" + type.name() + ":" + exclusiveKey)
        .query((rs, i) -> 1)
        .single();
  }

  @Override
  public boolean existsBlockingFor(SagaType type, String exclusiveKey) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM flowable.saga_instance
               WHERE saga_type = ?
                 AND context -> '_dieuKhien' ->> 'khoaDocQuyen' = ?
                 AND status <> 'DA_BU_TRU')
            """)
        .param(type.name())
        .param(exclusiveKey)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void insert(SagaInstance saga) {
    jdbc.sql(
            """
            INSERT INTO flowable.saga_instance
              (id, tenant_id, saga_type, correlation_id, status, current_step_no,
               point_of_no_return_passed, context, started_at, finished_at,
               created_by, updated_by, row_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 1)
            """)
        .param(saga.id())
        .param(saga.tenantId())
        .param(saga.type().name())
        .param(saga.correlationId())
        .param(saga.status().name())
        .param(saga.currentStepNo())
        .param(saga.pointOfNoReturnPassed())
        .param(context(saga))
        .param(ts(saga.startedAt()))
        .param(ts(saga.finishedAt()))
        .param(saga.initiator())
        .param(saga.initiator())
        .update();
    for (SagaStep step : saga.steps()) {
      jdbc.sql(
              """
              INSERT INTO flowable.saga_step
                (id, tenant_id, saga_instance_id, step_no, step_code, compensation_code, status,
                 attempts)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .param(step.id())
          .param(saga.tenantId())
          .param(saga.id())
          .param(step.stepNo())
          .param(step.stepCode())
          .param(step.compensationCode())
          .param(step.status().name())
          .param(step.attempts())
          .update();
    }
  }

  @Override
  public void update(SagaInstance saga) {
    int rows =
        jdbc.sql(
                """
                UPDATE flowable.saga_instance
                   SET status = ?, current_step_no = ?, point_of_no_return_passed = ?,
                       context = ?::jsonb, finished_at = ?, updated_at = now(),
                       updated_by = ?, row_version = row_version + 1
                 WHERE id = ? AND row_version = ?
                """)
            .param(saga.status().name())
            .param(saga.currentStepNo())
            .param(saga.pointOfNoReturnPassed())
            .param(context(saga))
            .param(ts(saga.finishedAt()))
            .param(saga.initiator())
            .param(saga.id())
            .param(saga.rowVersion())
            .update();
    if (rows != 1) {
      throw new ConcurrentSagaUpdateException(saga.id());
    }
    for (SagaStep step : saga.steps()) {
      jdbc.sql(
              """
              UPDATE flowable.saga_step
                 SET status = ?, attempts = ?, command_event_id = ?, reply_event_id = ?,
                     last_error = ?, started_at = ?, finished_at = ?
               WHERE id = ?
              """)
          .param(step.status().name())
          .param(step.attempts())
          .param(step.commandEventId())
          .param(step.replyEventId())
          .param(step.lastError())
          .param(ts(step.startedAt()))
          .param(ts(step.finishedAt()))
          .param(step.id())
          .update();
    }
  }

  private SagaInstance instance(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    Map<String, Object> context = parse(rs.getString("context"));
    @SuppressWarnings("unchecked")
    Map<String, Object> control =
        (Map<String, Object>) context.getOrDefault("_dieuKhien", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> parameters = (Map<String, Object>) context.getOrDefault("thamSo", Map.of());
    List<SagaStep> steps =
        jdbc.sql("SELECT * FROM flowable.saga_step WHERE saga_instance_id = ? ORDER BY step_no")
            .param(id)
            .query((r, i) -> step(r))
            .list();
    SagaInstance saga =
        new SagaInstance(
            id,
            rs.getObject("tenant_id", UUID.class),
            SagaType.valueOf(rs.getString("saga_type")),
            rs.getObject("correlation_id", UUID.class),
            (String) control.get("khoaDocQuyen"),
            UUID.fromString((String) control.get("nguoiKhoiTao")),
            parameters,
            steps,
            SagaStatus.valueOf(rs.getString("status")),
            rs.getInt("current_step_no"),
            rs.getBoolean("point_of_no_return_passed"),
            Boolean.TRUE.equals(control.get("yeuCauTamDung")),
            Boolean.TRUE.equals(control.get("tamDung")),
            Boolean.TRUE.equals(control.get("buTru")),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getLong("row_version"));
    @SuppressWarnings("unchecked")
    Map<String, Object> notes = (Map<String, Object>) context.get("ghiChu");
    saga.restoreNotes(notes);
    return saga;
  }

  private static SagaStep step(ResultSet rs) throws SQLException {
    return new SagaStep(
        rs.getObject("id", UUID.class),
        rs.getInt("step_no"),
        rs.getString("step_code"),
        rs.getString("compensation_code"),
        SagaStepStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        rs.getObject("command_event_id", UUID.class),
        rs.getObject("reply_event_id", UUID.class),
        rs.getString("last_error"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"));
  }

  private String context(SagaInstance saga) {
    Map<String, Object> control = new LinkedHashMap<>();
    control.put("khoaDocQuyen", saga.exclusiveKey());
    control.put("nguoiKhoiTao", saga.initiator().toString());
    control.put("yeuCauTamDung", saga.pauseRequested());
    control.put("tamDung", saga.paused());
    control.put("buTru", saga.compensating());
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("thamSo", saga.parameters());
    context.put("_dieuKhien", control);
    if (!saga.notes().isEmpty()) {
      context.put("ghiChu", saga.notes());
    }
    try {
      return json.writeValueAsString(context);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được tham số saga", e);
    }
  }

  private Map<String, Object> parse(String value) {
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cột context của saga hỏng", e);
    }
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
