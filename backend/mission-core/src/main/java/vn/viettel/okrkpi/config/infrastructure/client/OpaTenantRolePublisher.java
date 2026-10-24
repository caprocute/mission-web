package vn.viettel.okrkpi.config.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.config.application.port.TenantRolePublisher;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi {@code data.tenant_roles.<tenant>} của OPA qua Data API ({@code PUT
 * /v1/data/tenant_roles/<tenant>}), đúng dạng mà {@code backend/opa/okrkpi/authz.rego} đọc: {@code
 * {mã: {base, status, permissions}}}. Gốc {@code tenant_roles} nằm ngoài bundle {@code okrkpi} nên
 * ghi được khi OPA nạp chính sách từ tệp (dev) hay bundle có roots khai báo riêng (BS-04 mục giới
 * hạn).
 */
public class OpaTenantRolePublisher implements TenantRolePublisher {

  private final HttpClient http;
  private final URI base;
  private final ObjectMapper json;
  private final Duration timeout;

  public OpaTenantRolePublisher(URI opaBaseUrl, ObjectMapper json, Duration timeout) {
    this.base = Objects.requireNonNull(opaBaseUrl, "opaBaseUrl");
    this.json = Objects.requireNonNull(json, "json");
    this.timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
  }

  @Override
  public void publish(TenantId tenantId, List<CustomRole> roles) {
    Map<String, Object> doc = new LinkedHashMap<>();
    for (CustomRole r : roles) {
      Map<String, Object> def = new LinkedHashMap<>();
      def.put("base", r.baseRole());
      def.put("status", r.status().name());
      def.put("permissions", r.permissions());
      doc.put(r.code(), def);
    }
    String body;
    try {
      body = json.writeValueAsString(doc);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    URI uri = base.resolve("/v1/data/tenant_roles/" + tenantId.value());
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
      if (res.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "OPA trả mã " + res.statusCode() + " khi ghi vai trò tuỳ chỉnh");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Không kết nối được OPA", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi ghi vai trò tuỳ chỉnh sang OPA", e);
    }
  }
}
