package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.PersonnelSink;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gửi lô nhân sự sang org-service qua cổng nội bộ {@code POST
 * /internal/v1/org/sync-runs/{runId}/batches} (DV-04-07, thân {@code BatchRequest}): org-service tự
 * mở lần chạy khi chưa có. Mỗi lời gọi đóng lần chạy ({@code last=true}) vì một thông điệp HRM là
 * một lần chạy trọn; mã khác 2xx là lỗi vào ra để luồng vào thử lại rồi chuyển thư chết.
 */
class OrgServicePersonnelSink implements PersonnelSink {

  static final String PATH = "/internal/v1/org/sync-runs/%s/batches";

  private final HttpClient http;
  private final ObjectMapper json;
  private final String baseUrl;
  private final ServiceToken token;
  private final Duration timeout;

  OrgServicePersonnelSink(
      HttpClient http, ObjectMapper json, String baseUrl, ServiceToken token, Duration timeout) {
    this.http = http;
    this.json = json;
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    this.token = token;
    this.timeout = timeout;
  }

  @Override
  public void forward(TenantId tenant, String connectorCode, UUID runId, List<Person> persons) {
    if (baseUrl.isEmpty()) {
      throw new IllegalStateException("Chưa cấu hình địa chỉ org-service cho lô nhân sự");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", tenant.value());
    body.put("connector", connectorCode);
    body.put("records", persons);
    body.put("last", true);
    body.put("deadLetters", 0);
    body.put("reason", null);
    try {
      HttpRequest.Builder req =
          HttpRequest.newBuilder(URI.create(baseUrl + PATH.formatted(runId)))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
      if (token.enabled()) {
        req.header("Authorization", "Bearer " + token.bearer());
      }
      HttpResponse<Void> res = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
      if (res.statusCode() / 100 != 2) {
        throw new IOException("org-service trả mã " + res.statusCode() + " cho lô nhân sự");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException("Bị ngắt khi gửi lô nhân sự", e));
    }
  }
}
