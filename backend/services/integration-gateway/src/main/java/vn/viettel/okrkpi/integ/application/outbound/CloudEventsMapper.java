package vn.viettel.okrkpi.integ.application.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException.Reason;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/**
 * Chuyển sự kiện nội bộ sang CloudEvents 1.0 JSON chế độ cấu trúc cho hệ ngoài (DV-13-02, TL-01
 * "Phong bì sự kiện", ví dụ EVT-12 chia sẻ lên CSDL CBCCVC).
 *
 * <ul>
 *   <li>{@code source} theo TL-01: {@code urn:vt-okrkpi:<cell>:<module>}, module là phần đầu của
 *       topic (TL-03 dùng {@code urn:vt-okr:<cell>:<service>} cho phong bì nội bộ, wiki trang 14).
 *   <li>{@code type} là {@code vn.viettel.okrkpi.<topic>}; {@code dataschema} là {@code
 *       <gốc>/<topic>.json}.
 *   <li>Phần mở rộng {@code traceparent}, {@code tenantid}, {@code classification}, {@code
 *       policyversion} lấy từ tiêu đề Kafka nội bộ ({@link OutboxHeaders}).
 * </ul>
 *
 * <p>Chặn trước khi dựng phong bì: mức phân loại thực tế (cao hơn giữa nhãn sự kiện và dữ liệu)
 * phải nằm trong {@link OutboundPolicy#allowed()}; dữ liệu không được chứa giá trị đã niêm phong
 * (đối tượng {@code {"c2": ...}}, {@code {"c3": ...}}, trường {@code *_enc}, nút nhị phân).
 */
public class CloudEventsMapper {

  public static final String SPEC_VERSION = "1.0";
  public static final String DATA_CONTENT_TYPE = "application/json";
  public static final String TYPE_PREFIX = "vn.viettel.okrkpi.";
  public static final String SOURCE_PREFIX = "urn:vt-okrkpi:";

  static final Pattern TOPIC = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+\\.v[0-9]+$");
  static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
  static final Pattern CELL = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
  static final int MAX_SUBJECT = 256;
  static final int MAX_POLICY_VERSION = 128;

  private final String cell;
  private final String dataSchemaBase;
  private final ObjectMapper json;
  private final Clock clock;

  public CloudEventsMapper(String cell, String dataSchemaBase, ObjectMapper json, Clock clock) {
    if (cell == null || !CELL.matcher(cell).matches()) {
      throw new IllegalArgumentException("Mã cell phải là chữ thường, số và dấu gạch ngang");
    }
    this.cell = cell;
    this.dataSchemaBase =
        Objects.requireNonNull(dataSchemaBase, "dataSchemaBase").replaceAll("/+$", "");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Dựng {@link OutboundEvent} từ tiêu đề Kafka của sự kiện nội bộ; {@code subject} và {@code data}
   * do bộ kết nối cung cấp sau khi đổi sang mã ngoài.
   */
  public OutboundEvent fromHeaders(
      String topic,
      Map<String, String> headers,
      String subject,
      ObjectNode data,
      Classification dataClassification) {
    String id = headers.get(OutboxHeaders.EVENT_ID);
    String tenant = headers.get(OutboxHeaders.TENANT_ID);
    if (id == null || tenant == null) {
      throw new OutboundRejectedException(
          Reason.INVALID_ENVELOPE, "Sự kiện thiếu tiêu đề id hoặc tenantid");
    }
    try {
      return new OutboundEvent(
          UUID.fromString(id),
          topic,
          subject,
          occurredAt(headers.get(OutboxHeaders.OCCURRED_AT)),
          headers.get(OutboxHeaders.TRACEPARENT),
          UUID.fromString(tenant),
          classification(headers.get(OutboxHeaders.CLASSIFICATION)),
          headers.get(OutboxHeaders.POLICY_VERSION),
          data,
          dataClassification);
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new OutboundRejectedException(
          Reason.INVALID_ENVELOPE, "Tiêu đề sự kiện không hợp lệ: " + e.getClass().getSimpleName());
    }
  }

  /** Phong bì CloudEvents dạng cây JSON, theo thứ tự thuộc tính của ví dụ TL-01. */
  public ObjectNode toCloudEvent(OutboundEvent event, OutboundPolicy policy) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(policy, "policy");
    Classification level = event.effectiveClassification();
    if (!policy.permits(level)) {
      throw new OutboundRejectedException(
          Reason.CLASSIFICATION_NOT_ALLOWED,
          "Kết nối " + policy.connectorCode() + " không được chia sẻ dữ liệu mức " + level);
    }
    rejectSealed(event.data(), "data");
    if (!TOPIC.matcher(event.topic()).matches()) {
      throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "Tên topic không hợp lệ");
    }
    ObjectNode ce = json.createObjectNode();
    ce.put("specversion", SPEC_VERSION);
    ce.put("id", event.eventId().toString());
    ce.put("source", SOURCE_PREFIX + cell + ":" + module(event.topic()));
    ce.put("type", TYPE_PREFIX + event.topic());
    if (event.subject() != null && !event.subject().isBlank()) {
      if (event.subject().length() > MAX_SUBJECT) {
        throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "subject quá dài");
      }
      ce.put("subject", event.subject());
    }
    ce.put(
        "time", DateTimeFormatter.ISO_INSTANT.format(event.time().truncatedTo(ChronoUnit.MILLIS)));
    ce.put("datacontenttype", DATA_CONTENT_TYPE);
    ce.put("dataschema", dataSchemaBase + "/" + event.topic() + ".json");
    if (event.traceparent() != null && !event.traceparent().isBlank()) {
      if (!TRACEPARENT.matcher(event.traceparent()).matches()) {
        throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "traceparent không hợp lệ");
      }
      ce.put("traceparent", event.traceparent());
    }
    ce.put("tenantid", event.tenantId().toString());
    ce.put("classification", level.name());
    if (event.policyVersion() != null && !event.policyVersion().isBlank()) {
      if (event.policyVersion().length() > MAX_POLICY_VERSION) {
        throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "policyversion quá dài");
      }
      ce.put("policyversion", event.policyVersion());
    }
    ce.set("data", event.data().deepCopy());
    return ce;
  }

  /** Phong bì CloudEvents đã tuần tự hoá UTF-8, là nội dung lưu ở {@code outbound_message}. */
  public byte[] toJson(OutboundEvent event, OutboundPolicy policy) {
    try {
      return json.writeValueAsBytes(toCloudEvent(event, policy));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được phong bì CloudEvents", e);
    }
  }

  /**
   * Kiểm lại một phong bì đã lưu trước khi gửi (phòng cấu hình kết nối bị thu hẹp sau khi ghi hàng
   * chờ): mức phân loại và giá trị niêm phong.
   */
  public void recheck(byte[] cloudEvent, OutboundPolicy policy) {
    JsonNode ce;
    try {
      ce = json.readTree(cloudEvent);
    } catch (java.io.IOException e) {
      throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "Phong bì không phải JSON");
    }
    if (ce == null || !ce.isObject() || !SPEC_VERSION.equals(ce.path("specversion").asText())) {
      throw new OutboundRejectedException(
          Reason.INVALID_ENVELOPE, "Không phải phong bì CloudEvents 1.0");
    }
    Classification level;
    try {
      level = Classification.valueOf(ce.path("classification").asText(""));
    } catch (IllegalArgumentException e) {
      throw new OutboundRejectedException(Reason.INVALID_ENVELOPE, "classification không hợp lệ");
    }
    if (!policy.permits(level)) {
      throw new OutboundRejectedException(
          Reason.CLASSIFICATION_NOT_ALLOWED,
          "Kết nối " + policy.connectorCode() + " không được chia sẻ dữ liệu mức " + level);
    }
    rejectSealed(ce.path("data"), "data");
  }

  static String module(String topic) {
    return topic.substring(0, topic.indexOf('.'));
  }

  private Instant occurredAt(String value) {
    return value == null || value.isBlank() ? clock.instant() : Instant.parse(value);
  }

  private static Classification classification(String value) {
    return value == null || value.isBlank()
        ? Classification.C1
        : Classification.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private static void rejectSealed(JsonNode node, String path) {
    if (node.isBinary()) {
      throw sealed(path);
    }
    if (node.isObject()) {
      if (node.size() == 1 && (node.has("c2") || node.has("c3"))) {
        throw sealed(path);
      }
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> f = fields.next();
        if (f.getKey().endsWith("_enc") || f.getKey().endsWith("Enc")) {
          throw sealed(path + "." + f.getKey());
        }
        rejectSealed(f.getValue(), path + "." + f.getKey());
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        rejectSealed(node.get(i), path + "[" + i + "]");
      }
    }
  }

  private static OutboundRejectedException sealed(String path) {
    return new OutboundRejectedException(
        Reason.SEALED_VALUE, "Dữ liệu ra ngoài còn giá trị niêm phong ở " + path);
  }
}
