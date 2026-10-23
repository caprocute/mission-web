package vn.viettel.okrkpi.integ.infrastructure.truc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Máy khách giao thức REST của X-Road gọi qua security server của đơn vị: tiêu đề {@code
 * X-Road-Client} mang mã hệ thống con gọi, đường dẫn {@code /r1/<mã dịch vụ>/...}; mTLS giữa các
 * security server do hạ tầng X-Road lo, mã thông báo (nếu có) lấy theo {@code secret_path}. Nội
 * dung trả về không ghi nhật ký.
 */
class XRoadClient {

  static final String CLIENT_HEADER = "X-Road-Client";

  /** Kết quả một lời gọi: mã trạng thái và thân. */
  record Reply(int status, byte[] body) {}

  private final HttpClient http;
  private final ConnectorSecrets secrets;
  private final Duration timeout;

  XRoadClient(HttpClient http, ConnectorSecrets secrets, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  Reply get(Connector connector, String path) throws IOException {
    XRoadEndpoint ep = XRoadEndpoint.of(connector);
    return send(connector, ep, HttpRequest.newBuilder(URI.create(ep.url(path))).GET());
  }

  Reply put(Connector connector, String path, byte[] body, String contentType) throws IOException {
    XRoadEndpoint ep = XRoadEndpoint.of(connector);
    return send(
        connector,
        ep,
        HttpRequest.newBuilder(URI.create(ep.url(path)))
            .header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private Reply send(Connector connector, XRoadEndpoint ep, HttpRequest.Builder req)
      throws IOException {
    req.timeout(timeout).header(CLIENT_HEADER, ep.client()).header("Accept", "application/json");
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
      throw new IOException("Bị ngắt khi gọi X-Road " + connector.code(), e);
    }
    if (res.statusCode() >= 500 || res.statusCode() == 429) {
      throw new IOException(
          "Security server X-Road của " + connector.code() + " trả mã " + res.statusCode());
    }
    return new Reply(res.statusCode(), res.body());
  }
}
