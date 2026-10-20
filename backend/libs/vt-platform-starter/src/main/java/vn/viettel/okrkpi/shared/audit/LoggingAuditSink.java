package vn.viettel.okrkpi.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kênh kiểm toán tạm thời khi chưa có outbox (NEN-011): ghi mỗi sự kiện thành một dòng JSON trên
 * logger riêng {@value #LOGGER_NAME}, mà cấu hình Log4j2 của starter định tuyến tới appender {@code
 * AUDIT} riêng ({@code event.dataset=mission.audit}), không cộng dồn vào logger vận hành. Không bảo
 * đảm bền vững như outbox; NEN-011 thay bằng bean ghi {@code sys.audit.v1}.
 */
public class LoggingAuditSink implements AuditSink {

  /** Tên logger của kênh kiểm toán (khai báo trong {@code log4j2-spring.xml}). */
  public static final String LOGGER_NAME = "OKRKPI_AUDIT";

  private static final Logger audit = LoggerFactory.getLogger(LOGGER_NAME);

  private final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Override
  public void publish(AuditEvent event) {
    audit.info(toJson(event));
  }

  String toJson(AuditEvent e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("topic", TOPIC);
    m.put("eventId", String.valueOf(e.eventId()));
    m.put("occurredAt", e.occurredAt());
    m.put("type", e.type().name());
    m.put("outcome", e.outcome().name());
    m.put("tenantId", e.tenantId() == null ? null : e.tenantId().toString());
    m.put("actorId", e.actorId().toString());
    m.put("actorRole", e.actorRole());
    m.put("action", e.action());
    m.put("objectType", e.objectType());
    m.put("objectId", e.objectId());
    m.put("classification", e.classification() == null ? null : e.classification().name());
    m.put("traceId", e.traceId());
    m.put("attributes", e.attributes());
    try {
      return json.writeValueAsString(m);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Không tuần tự hoá được sự kiện kiểm toán", ex);
    }
  }
}
