package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Rest;

/**
 * Bộ kết nối REST chung cho HRM/HIS (DV-13-08): kéo nhân sự sau con trỏ stream {@code hrm-persons}
 * theo hồ sơ kết nối. Một trang là một thông điệp (một lần chạy đồng bộ ở org-service), mã thông
 * điệp là băm SHA-256 của nội dung nên trang kéo lại y nguyên không xử lý hai lần.
 */
class RestHrmConnector implements InboundConnector {

  static final String STREAM = "hrm-persons";

  private final String code;
  private final HrmHttp http;
  private final Rest rest;
  private final Duration period;
  private final Duration timeout;
  private final Translator translator;

  RestHrmConnector(
      String code,
      HrmHttp http,
      Rest rest,
      Duration period,
      Duration timeout,
      Translator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.http = Objects.requireNonNull(http, "http");
    this.rest = Objects.requireNonNull(rest, "rest");
    this.period = Objects.requireNonNull(period, "period");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
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
    return rest.initialCursor();
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
  public Page fetch(Connector connector, String cursor) throws IOException {
    JsonNode body =
        http.getJson(
            connector,
            rest.path(),
            Map.of(rest.cursorParam(), cursor, rest.limitParam(), String.valueOf(rest.pageSize())),
            timeout);
    JsonNode items = body.at(rest.items());
    if (!items.isArray() || items.isEmpty()) {
      return new Page(List.of(), cursor, null);
    }
    byte[] raw = http.json().writeValueAsBytes(items);
    Instant newest = null;
    if (rest.updatedAt() != null) {
      for (JsonNode item : items) {
        Instant t = RestIndicatorSource.instant(item.at(rest.updatedAt()).asText(null));
        if (t != null && (newest == null || t.isAfter(newest))) {
          newest = t;
        }
      }
    }
    String next = body.at(rest.nextCursor()).asText(null);
    if ((next == null || next.isBlank()) && newest != null) {
      next = newest.toString();
    }
    return new Page(
        List.of(new InboundMessage("trang:" + Digests.sha256(raw), BatchFiles.TYPE_JSON, raw)),
        next == null || next.isBlank() ? cursor : next,
        newest);
  }

  @Override
  public Translator translator() {
    return translator;
  }
}
