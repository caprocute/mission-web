package vn.viettel.okrkpi.shared.infrastructure.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client {@code simulateAppraisalScore} của SVC-07 ({@code POST
 * /api/v1/appraisals/profiles/{id}/simulate}, DV-07-08): tính thử điểm của một hồ sơ với điều chỉnh
 * cấu phần, dùng phiên bản Policy Pack của kết quả gốc, không lưu gì. Chuyển tiếp {@code
 * Authorization} của người gọi để SVC-07 kiểm quyền theo người dùng. Đặt ở {@code shared} vì mô-đun
 * nghiệp vụ không gọi HTTP trực tiếp (ArchUnit, TL-01).
 */
public class ScoringSimulationClient {

  static final String PATH = "/api/v1/appraisals/profiles/{id}/simulate";

  /** Header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42). */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";

  /** Một điều chỉnh cấu phần ({@code component} như A, B, C; {@code taskId} có thể rỗng). */
  public record Adjustment(
      String component, UUID taskId, BigDecimal oldValue, BigDecimal newValue, String reason) {}

  public record Outcome(BigDecimal totalScore, String ratingCode) {}

  public record Result(String policyVersion, Outcome before, Outcome after) {}

  private final RestClient http;

  public ScoringSimulationClient(String baseUrl, Duration timeout) {
    this(build(baseUrl, timeout));
  }

  ScoringSimulationClient(RestClient http) {
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
   * @return kết quả; {@code null} khi SVC-07 chưa có kết quả gốc của hồ sơ (404)
   * @throws BusinessException {@code COMMON-403-OUT-OF-SCOPE} khi SVC-07 từ chối người gọi (401,
   *     403)
   * @throws DependencyUnavailableException khi SVC-07 lỗi hoặc không trả lời
   */
  public Result simulate(
      TenantId tenant, UUID profileId, List<Adjustment> adjustments, String authorization) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (Adjustment a : adjustments) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("component", a.component());
      if (a.taskId() != null) {
        m.put("taskId", a.taskId().toString());
      }
      if (a.oldValue() != null) {
        m.put("oldValue", a.oldValue());
      }
      m.put("newValue", a.newValue());
      if (a.reason() != null) {
        m.put("reason", a.reason());
      }
      items.add(m);
    }
    JsonNode body;
    try {
      body =
          http.post()
              .uri(PATH, profileId)
              .contentType(MediaType.APPLICATION_JSON)
              .headers(
                  h -> {
                    h.set("Idempotency-Key", UUID.randomUUID().toString());
                    h.set(TENANT_HEADER, tenant.value().toString());
                    if (authorization != null) {
                      h.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                  })
              .body(Map.of("adjustments", items))
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.NOT_FOUND.value()) {
        return null;
      }
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        throw new BusinessException(
            FORBIDDEN, "Đồng chí không có quyền tính thử kết quả của hồ sơ trong phạm vi này.");
      }
      throw unavailable(e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    if (body == null) {
      throw unavailable(null);
    }
    return new Result(
        body.path("policyVersion").asText(null),
        outcome(body.path("before")),
        outcome(body.path("after")));
  }

  private static Outcome outcome(JsonNode n) {
    return new Outcome(
        n.hasNonNull("totalScore") ? n.get("totalScore").decimalValue() : null,
        n.path("ratingCode").asText(null));
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "scoring-service",
        DependencyUnavailableException.Reason.IO_ERROR,
        Duration.ofSeconds(5),
        e);
  }
}
