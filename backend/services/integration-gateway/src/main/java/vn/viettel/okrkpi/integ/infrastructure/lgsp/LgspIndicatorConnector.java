package vn.viettel.okrkpi.integ.infrastructure.lgsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Kéo chỉ tiêu KT-XH (QĐ khoản 10.2.2) qua LGSP tỉnh, bộ (DV-13-06): {@code GET
 * <endpoint><path>?capNhatTu=<con trỏ>&gioiHan=<cỡ trang>} trả {@code {items:[{maChiTieu, kyBaoCao,
 * maDiaBan, giaTri, donViTinh, daCongBo, thoiDiemCongBo, capNhatLuc}], next}}. Mỗi chỉ tiêu theo
 * kỳ, địa bàn và thời điểm cập nhật là một thông điệp, nên bản sửa ở LGSP là thông điệp mới còn bản
 * gửi lại bị chống trùng. Hợp đồng REST là suy luận cho bản giả lập (YC-PT-13); chứng thư tổ chức
 * do egress gateway gắn khi khởi tạo TLS ra LGSP, mã thông báo (nếu có) theo {@code secret_path}.
 */
class LgspIndicatorConnector implements InboundConnector {

  static final String STREAM = "ktxh-indicators";
  static final String TYPE = "KTXH_CHI_TIEU";
  static final String EPOCH = "1970-01-01T00:00:00Z";

  private final String code;
  private final HttpClient http;
  private final ConnectorSecrets secrets;
  private final ObjectMapper json;
  private final String path;
  private final int pageSize;
  private final Duration period;
  private final Duration timeout;
  private final Translator translator;

  LgspIndicatorConnector(
      String code,
      HttpClient http,
      ConnectorSecrets secrets,
      ObjectMapper json,
      String path,
      int pageSize,
      Duration period,
      Duration timeout,
      Translator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.http = Objects.requireNonNull(http, "http");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.json = Objects.requireNonNull(json, "json");
    this.path = Objects.requireNonNull(path, "path");
    this.pageSize = pageSize;
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
    return EPOCH;
  }

  @Override
  public Duration period() {
    return period;
  }

  @Override
  public Translator translator() {
    return translator;
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    String endpoint = connector.endpoint();
    if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
      throw new IOException("Địa chỉ LGSP của " + connector.code() + " không hợp lệ");
    }
    URI uri =
        URI.create(
            endpoint.replaceAll("/+$", "")
                + path
                + "?capNhatTu="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
                + "&gioiHan="
                + pageSize);
    HttpRequest.Builder req =
        HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
    if (connector.secretPath() != null) {
      secrets
          .token(connector.secretPath())
          .ifPresent(t -> req.header("Authorization", "Bearer " + t));
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi LGSP " + connector.code(), e);
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("LGSP " + connector.code() + " trả mã " + res.statusCode());
    }
    JsonNode body = json.readTree(res.body());
    List<InboundMessage> items = new ArrayList<>();
    Instant newest = null;
    for (JsonNode item : body.path("items")) {
      String updated = item.path("capNhatLuc").asText("");
      String id =
          item.path("maChiTieu").asText("")
              + "|"
              + item.path("kyBaoCao").asText("")
              + "|"
              + item.path("maDiaBan").asText("")
              + "|"
              + updated;
      if (id.length() > 128) {
        throw new IOException("Mã chỉ tiêu LGSP quá dài");
      }
      items.add(new InboundMessage(id, TYPE, json.writeValueAsBytes(item)));
      Instant t = instant(updated);
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

  static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
