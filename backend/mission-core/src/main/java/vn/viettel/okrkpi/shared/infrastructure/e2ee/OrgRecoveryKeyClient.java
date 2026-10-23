package vn.viettel.okrkpi.shared.infrastructure.e2ee;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client {@code GET /api/v1/org/e2ee/recovery-key} ({@code getRecoveryKey} của {@code org.yaml},
 * SVC-04, CORE-07-06): khoá phục hồi tổ chức đang hiệu lực; và {@code GET
 * /api/v1/org/e2ee/recoveries/{id}} ({@code getE2eeRecovery}): biên bản khôi phục để đối chiếu khoá
 * bọc khôi phục. Token dịch vụ không mang claim tenant nên tenant đích gửi trong {@code
 * X-Tenant-Id}; tenant chưa khai báo khoá (404) trả rỗng. Các mô-đun nghiệp vụ không gọi HTTP trực
 * tiếp (ArchUnit), nên client nằm ở {@code shared.infrastructure}.
 */
public class OrgRecoveryKeyClient {

  /**
   * {@code id} là người nhận đại diện ghi trong bản bọc, {@code fingerprint} là {@code deviceId}.
   */
  public record Current(UUID id, String fingerprint) {}

  /** Biên bản khôi phục ({@code RecoveryRecord}) rút gọn các trường cần đối chiếu. */
  public record Recovery(
      UUID id,
      String objectType,
      UUID objectId,
      UUID newRecipientId,
      String newKeyFingerprint,
      Instant recordedAt) {}

  public static final String TENANT_HEADER = "X-Tenant-Id";

  private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Supplier<String> token;

  public OrgRecoveryKeyClient(RestClient.Builder builder, String baseUrl, Supplier<String> token) {
    this.http = builder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
    this.token = Objects.requireNonNull(token, "token");
  }

  public Optional<Current> current(TenantId tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    try {
      Map<String, Object> body =
          http.get()
              .uri("/api/v1/org/e2ee/recovery-key")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
              .header(TENANT_HEADER, tenantId.value().toString())
              .retrieve()
              .body(MAP);
      if (body == null
          || !(body.get("id") instanceof String id)
          || !(body.get("fingerprint") instanceof String fp)
          || !HEX64.matcher(fp).matches()) {
        throw new RestClientException("org-service trả khoá phục hồi sai dạng");
      }
      return Optional.of(new Current(UUID.fromString(id), fp));
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (RestClientException | IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  /** Biên bản khôi phục theo mã; rỗng khi SVC-04 không có (404). */
  public Optional<Recovery> recovery(TenantId tenantId, UUID id) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(id, "id");
    try {
      Map<String, Object> b =
          http.get()
              .uri("/api/v1/org/e2ee/recoveries/{id}", id)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
              .header(TENANT_HEADER, tenantId.value().toString())
              .retrieve()
              .body(MAP);
      if (b == null
          || !(b.get("id") instanceof String rid)
          || !(b.get("objectType") instanceof String type)
          || !(b.get("objectId") instanceof String object)
          || !(b.get("newRecipientId") instanceof String recipient)
          || !(b.get("newKeyFingerprint") instanceof String fp)
          || !(b.get("recordedAt") instanceof String at)) {
        throw new RestClientException("org-service trả biên bản khôi phục sai dạng");
      }
      return Optional.of(
          new Recovery(
              UUID.fromString(rid),
              type,
              UUID.fromString(object),
              UUID.fromString(recipient),
              fp,
              OffsetDateTime.parse(at).toInstant()));
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (RestClientException | IllegalArgumentException | DateTimeParseException e) {
      throw unavailable(e);
    }
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "org-service", DependencyUnavailableException.Reason.IO_ERROR, Duration.ofSeconds(5), e);
  }
}
