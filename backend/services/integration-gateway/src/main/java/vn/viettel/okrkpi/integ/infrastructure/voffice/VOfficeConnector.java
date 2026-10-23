package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Bộ kết nối vOffice chiều vào (DV-13-03; ĐHTN cùng giao thức dùng mã khác): kéo {@code GET
 * /api/v2/directives?updatedSince=&limit=200} mỗi 30 giây theo con trỏ stream {@code directives}.
 * Mỗi văn bản thành một thông điệp mã {@code <id>:<version>} (khoá chống trùng {@code
 * voffice:${id}:${version}} của TL-01), nên văn bản đổi phiên bản được xử lý lại còn bản trùng thì
 * không. Phần tử có trường {@code edxml} được lưu nguyên gói EdXML (vOffice đẩy theo NĐ 30/2020,
 * TL-02a QT-05 bước 1b).
 */
public class VOfficeConnector implements InboundConnector {

  public static final String STREAM = "directives";
  public static final String TYPE_JSON = "VOFFICE_JSON";
  public static final String TYPE_EDXML = "EDXML";
  static final int PAGE = 200;
  static final String EPOCH = "1970-01-01T00:00:00Z";

  private final String code;
  private final VOfficeClient client;
  private final Duration period;
  private final Translator translator;

  VOfficeConnector(
      String code, VOfficeClient client, Duration period, VOfficeTranslator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
    this.period = Objects.requireNonNull(period, "period");
    this.translator = Objects.requireNonNull(translator, "translator");
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String stream() {
    return STREAM;
  }

  @Override
  public String initialCursor() {
    return EPOCH;
  }

  @Override
  public Duration period() {
    return period;
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    JsonNode body =
        client.get(
            connector,
            VOfficeClient.DIRECTIVES,
            Map.of("updatedSince", cursor, "limit", String.valueOf(PAGE)));
    List<InboundMessage> items = new ArrayList<>();
    Instant newest = null;
    for (JsonNode item : body.path("items")) {
      String id = item.path("id").asText("");
      long version = item.path("version").asLong(1);
      String externalId = id + ":" + version;
      if (id.isBlank() || externalId.length() > 128) {
        throw new IOException("Văn bản vOffice thiếu mã hoặc mã quá dài");
      }
      JsonNode edxml = item.get("edxml");
      items.add(
          edxml != null && edxml.isTextual()
              ? new InboundMessage(
                  externalId, TYPE_EDXML, edxml.asText().getBytes(StandardCharsets.UTF_8))
              : new InboundMessage(externalId, TYPE_JSON, client.json().writeValueAsBytes(item)));
      Instant updated = instant(item.path("updatedAt").asText(null));
      if (updated != null && (newest == null || updated.isAfter(newest))) {
        newest = updated;
      }
    }
    String next = body.path("nextUpdatedSince").asText(null);
    if (next == null && newest != null) {
      next = newest.toString();
    }
    return new Page(items, next == null ? cursor : next, newest);
  }

  @Override
  public Translator translator() {
    return translator;
  }

  private static Instant instant(String s) {
    if (s == null) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
