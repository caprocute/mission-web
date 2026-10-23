package vn.viettel.okrkpi.integ.infrastructure.truc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Chiều vào của Trục liên thông văn bản qua X-Road (DV-13-05, QT-05 bước 1b): kéo {@code GET <dịch
 * vụ>/edocs?since=&limit=50} theo con trỏ stream {@code edocs}; mỗi văn bản là một thông điệp mã
 * {@code docId} của Trục, nội dung là gói EdXML nguyên văn (giải mã base64 của trường {@code
 * edxml}). Hợp đồng REST của dịch vụ Trục là suy luận cho bản giả lập (YC-PT-13).
 */
class TrucEdocConnector implements InboundConnector {

  static final String STREAM = "edocs";
  static final String EPOCH = "1970-01-01T00:00:00Z";
  static final String TYPE = "EDXML";
  static final int PAGE = 50;

  private final String code;
  private final XRoadClient client;
  private final ObjectMapper json;
  private final Duration period;
  private final Translator translator;

  TrucEdocConnector(
      String code, XRoadClient client, ObjectMapper json, Duration period, Translator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
    this.json = Objects.requireNonNull(json, "json");
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
  public int maxConcurrentCalls() {
    return 2;
  }

  @Override
  public Translator translator() {
    return translator;
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    XRoadClient.Reply reply =
        client.get(
            connector,
            "/edocs?since=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&limit=" + PAGE);
    if (reply.status() / 100 != 2) {
      throw new IOException("Trục " + connector.code() + " trả mã " + reply.status());
    }
    JsonNode body = json.readTree(reply.body());
    List<InboundMessage> items = new ArrayList<>();
    Instant newest = null;
    for (JsonNode item : body.path("items")) {
      String docId = item.path("docId").asText("");
      if (docId.isBlank() || docId.length() > 128) {
        throw new IOException("Văn bản Trục thiếu mã hoặc mã quá dài");
      }
      byte[] edxml;
      try {
        edxml = Base64.getMimeDecoder().decode(item.path("edxml").asText(""));
      } catch (IllegalArgumentException e) {
        throw new IOException("Văn bản Trục " + docId + " không phải base64", e);
      }
      items.add(new InboundMessage(docId, TYPE, edxml));
      Instant t = instant(item.path("receivedAt").asText(null));
      if (t != null && (newest == null || t.isAfter(newest))) {
        newest = t;
      }
    }
    String next = body.path("next").asText(null);
    if ((next == null || next.isBlank()) && newest != null) {
      next = newest.toString();
    }
    return new Page(items, next == null || next.isBlank() ? cursor : next, newest);
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
