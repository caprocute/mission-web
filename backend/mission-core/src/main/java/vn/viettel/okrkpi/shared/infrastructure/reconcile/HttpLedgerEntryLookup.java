package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Đọc sổ cái qua {@code GET /api/v1/ledger/entries} (hợp đồng {@code listLedgerEntries}) bằng token
 * dịch vụ của mission-core; token không mang tenant nên tenant đích đi trong {@code X-Tenant-Id}
 * (wiki trang 14 mục 42). Lọc theo {@code aggregateId} và khoảng {@code recorded_at}, đọc hết các
 * trang 200 dòng.
 */
public class HttpLedgerEntryLookup implements LedgerEntryLookup {

  static final String PATH = "/api/v1/ledger/entries";
  static final int PAGE = 200;

  /** Chặn vòng đọc vô hạn khi một đối tượng có quá nhiều bản ghi trong cửa sổ (suy luận). */
  static final int MAX_PAGES = 50;

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Supplier<String> token;

  public HttpLedgerEntryLookup(RestClient.Builder builder, String baseUrl, Supplier<String> token) {
    this.http = builder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public List<LedgerRef> entries(TenantId tenant, UUID aggregateId, Instant from, Instant to) {
    List<LedgerRef> out = new ArrayList<>();
    String cursor = null;
    try {
      for (int page = 0; page < MAX_PAGES; page++) {
        String c = cursor;
        Map<String, Object> body =
            http.get()
                .uri(
                    u -> {
                      u.path(PATH)
                          .queryParam("aggregateId", aggregateId)
                          .queryParam("from", from.toString())
                          .queryParam("to", to.toString())
                          .queryParam("limit", PAGE);
                      if (c != null) {
                        u.queryParam("cursor", c);
                      }
                      return u.build();
                    })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
                .header(WorkflowSagaClient.TENANT_HEADER, tenant.value().toString())
                .retrieve()
                .body(MAP);
        if (body == null || !(body.get("items") instanceof List<?> items)) {
          throw new RestClientException("ledger-service không trả danh sách bản ghi");
        }
        for (Object o : items) {
          if (o instanceof Map<?, ?> m) {
            out.add(
                new LedgerRef(
                    UUID.fromString(String.valueOf(m.get("eventId"))),
                    String.valueOf(m.get("eventType")),
                    String.valueOf(m.get("payloadHash")),
                    Instant.parse(String.valueOf(m.get("recordedAt")))));
          }
        }
        if (!(body.get("nextCursor") instanceof String next) || next.isEmpty()) {
          return out;
        }
        cursor = next;
      }
      throw new RestClientException("Đối tượng có quá nhiều bản ghi sổ cái trong cửa sổ đối soát");
    } catch (RestClientException | IllegalArgumentException | java.time.DateTimeException e) {
      throw new DependencyUnavailableException(
          "ledger-service",
          DependencyUnavailableException.Reason.IO_ERROR,
          Duration.ofMinutes(5),
          e);
    }
  }
}
