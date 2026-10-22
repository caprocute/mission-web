package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDelayRisks;
import vn.viettel.okrkpi.task.domain.lifecycle.DelayForecast;

/**
 * {@link TaskDelayRisks} trên {@code taskDataSource}: {@code delay_risk} giữ mức, {@code
 * delay_risk_detail} giữ số ngày, giải thích và tiến độ làm căn cứ (cột bổ sung của V4).
 */
public class JdbcTaskDelayRisks implements TaskDelayRisks {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcTaskDelayRisks(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<Instant> storedAt(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT delay_risk_at FROM task.task WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ? AND delay_risk_at IS NOT NULL")
        .params(periodBucket, tenant.value(), taskId)
        .query((rs, i) -> rs.getTimestamp(1).toInstant())
        .optional();
  }

  @Override
  public Optional<DelayForecast> find(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT delay_risk, delay_risk_detail::text, delay_risk_at FROM task.task"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " AND delay_risk IS NOT NULL AND delay_risk_at IS NOT NULL")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) -> {
              JsonNode d = read(rs.getString(2));
              String progress = d.path("basedOnProgressId").asText(null);
              return new DelayForecast(
                  rs.getString(1),
                  d.path("neededWorkingDays").asInt(0),
                  d.path("explanation").asText(""),
                  rs.getTimestamp(3).toInstant(),
                  progress == null ? null : UUID.fromString(progress));
            })
        .optional();
  }

  @Override
  public void store(TenantId tenant, int periodBucket, UUID taskId, DelayForecast f) {
    ObjectNode d = json.createObjectNode();
    d.put("neededWorkingDays", f.neededWorkingDays());
    d.put("explanation", f.explanation());
    if (f.basedOnProgressId() != null) {
      d.put("basedOnProgressId", f.basedOnProgressId().toString());
    }
    jdbc.sql(
            "UPDATE task.task SET delay_risk = ?, delay_risk_detail = ?::jsonb, delay_risk_at = ?"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(
            f.level(),
            d.toString(),
            Timestamp.from(f.computedAt()),
            periodBucket,
            tenant.value(),
            taskId)
        .update();
  }

  private JsonNode read(String text) {
    if (text == null) {
      return json.createObjectNode();
    }
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      return json.createObjectNode();
    }
  }
}
