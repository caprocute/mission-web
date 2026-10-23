package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.IndicatorSource;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Indicator;

/**
 * Đọc chỉ số chuyên môn qua REST theo hồ sơ kết nối: {@code GET <endpoint><path>}, giá trị và thời
 * điểm đo lấy theo con trỏ JSON khai báo. Giá trị không phải số là lỗi nội dung.
 */
class RestIndicatorSource implements IndicatorSource {

  private final String code;
  private final HrmHttp http;
  private final Indicator indicator;
  private final Duration timeout;

  RestIndicatorSource(String code, HrmHttp http, Indicator indicator, Duration timeout) {
    this.code = Objects.requireNonNull(code, "code");
    this.http = Objects.requireNonNull(http, "http");
    this.indicator = Objects.requireNonNull(indicator, "indicator");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public String connectorCode() {
    return code;
  }

  @Override
  public Reading read(Connector connector, String externalIndicatorId) throws IOException {
    String path =
        indicator
            .path()
            .replace(
                "{id}",
                URLEncoder.encode(externalIndicatorId, StandardCharsets.UTF_8).replace("+", "%20"));
    JsonNode body = http.getJson(connector, path, Map.of(), timeout);
    JsonNode v = body.at(indicator.value());
    BigDecimal value;
    if (v.isNumber()) {
      value = v.decimalValue();
    } else if (v.isTextual()) {
      try {
        value = new BigDecimal(v.asText().strip().replace(',', '.'));
      } catch (NumberFormatException e) {
        throw new IOException("Chỉ số " + code + " không phải số", e);
      }
    } else {
      throw new IOException("Chỉ số " + code + " không có giá trị");
    }
    return new Reading(value, instant(body.at(indicator.measuredAt()).asText(null)));
  }

  static Instant instant(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
