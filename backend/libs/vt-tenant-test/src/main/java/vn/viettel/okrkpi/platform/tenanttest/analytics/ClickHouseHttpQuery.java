package vn.viettel.okrkpi.platform.tenanttest.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TenantScopedQuery} trên giao diện HTTP của ClickHouse: mỗi tenant đăng nhập bằng người
 * dùng riêng gắn row policy {@code tenant_id = '<tenant>'}, nên câu SQL có lọc gì đi nữa cũng chỉ
 * thấy dòng của tenant đó. Không kéo driver JDBC.
 */
public final class ClickHouseHttpQuery implements TenantScopedQuery {

  /** Thông tin đăng nhập ClickHouse của một tenant (chỉ dùng trong kiểm thử). */
  public record Credentials(String user, String password) {}

  private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

  private final URI endpoint;
  private final Map<String, Credentials> credentialsByTenant;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  public ClickHouseHttpQuery(URI endpoint, Map<String, Credentials> credentialsByTenant) {
    this.endpoint = endpoint;
    this.credentialsByTenant = Map.copyOf(credentialsByTenant);
  }

  @Override
  public List<Map<String, String>> query(String tenant, String sql) {
    Credentials c = credentialsByTenant.get(tenant);
    if (c == null) {
      throw new IllegalArgumentException("Chưa khai người dùng ClickHouse cho tenant " + tenant);
    }
    return execute(c, sql + " FORMAT JSONEachRow");
  }

  /** Chạy câu lệnh bằng {@code credentials} (dựng bảng mẫu trong kiểm thử tự kiểm). */
  public List<Map<String, String>> execute(Credentials credentials, String sql) {
    String auth =
        Base64.getEncoder()
            .encodeToString(
                (credentials.user() + ":" + credentials.password())
                    .getBytes(StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Basic " + auth)
            .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        throw new IllegalStateException("ClickHouse trả " + res.statusCode() + ": " + res.body());
      }
      List<Map<String, String>> rows = new ArrayList<>();
      for (String line : res.body().split("\n")) {
        if (line.isBlank()) {
          continue;
        }
        Map<String, String> row = new LinkedHashMap<>();
        json.readValue(line, ROW).forEach((k, v) -> row.put(k, v == null ? null : v.toString()));
        rows.add(row);
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("Không gọi được ClickHouse", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi gọi ClickHouse", e);
    }
  }
}
