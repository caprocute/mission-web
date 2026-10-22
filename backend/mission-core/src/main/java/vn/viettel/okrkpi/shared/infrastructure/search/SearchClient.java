package vn.viettel.okrkpi.shared.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Client {@code searchFullText} của SVC-16 ({@code GET /api/v1/search}, {@code platform.yaml}) thay
 * mặt người dùng: chuyển tiếp {@code Authorization} để SVC-16 chỉ trả kết quả trong phạm vi người
 * gọi. Đặt ở {@code shared} vì mô-đun nghiệp vụ không gọi HTTP trực tiếp (ArchUnit, TL-01); MOD-02
 * dùng cho CORE-02-15. Thời gian chờ ngắn vì kết quả chỉ là gợi ý.
 */
public class SearchClient {

  static final String PATH = "/api/v1/search";

  /** Header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42). */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  /** Một kết quả: mã thực thể và điểm của chỉ mục. */
  public record Hit(UUID id, double score) {}

  private final RestClient http;

  public SearchClient(String baseUrl, Duration timeout) {
    this(build(baseUrl, timeout));
  }

  SearchClient(RestClient http) {
    this.http = Objects.requireNonNull(http, "http");
  }

  private static RestClient build(String baseUrl, Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 1_000));
    factory.setReadTimeout((int) timeout.toMillis());
    return RestClient.builder()
        .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
        .requestFactory(factory)
        .build();
  }

  /**
   * Tìm theo loại tài liệu ({@code TASK}, {@code SOURCE_DOC}, {@code CATALOG_ITEM}) trong kỳ.
   *
   * @throws IllegalStateException khi SVC-16 lỗi, quá thời gian hoặc không gọi được
   */
  public List<Hit> search(
      TenantId tenant, String q, String type, UUID period, int limit, String authorization) {
    String text = q.length() > 200 ? q.substring(0, 200) : q;
    JsonNode body;
    try {
      body =
          http.get()
              .uri(
                  u -> {
                    u.path(PATH)
                        .queryParam("q", text)
                        .queryParam("type", type)
                        .queryParam("limit", Math.max(1, Math.min(200, limit)));
                    if (period != null) {
                      u.queryParam("period", period);
                    }
                    return u.build();
                  })
              .headers(
                  h -> {
                    h.set(TENANT_HEADER, tenant.value().toString());
                    if (authorization != null) {
                      h.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                  })
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientException e) {
      throw new IllegalStateException("Không gọi được SVC-16", e);
    }
    List<Hit> out = new ArrayList<>();
    if (body != null) {
      for (JsonNode item : body.path("items")) {
        try {
          out.add(
              new Hit(UUID.fromString(item.path("id").asText()), item.path("score").asDouble()));
        } catch (IllegalArgumentException e) {
          // bỏ mục không có mã hợp lệ
        }
      }
    }
    return out;
  }
}
