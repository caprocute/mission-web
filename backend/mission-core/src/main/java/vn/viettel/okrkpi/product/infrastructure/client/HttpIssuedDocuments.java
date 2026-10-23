package vn.viettel.okrkpi.product.infrastructure.client;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException.Reason;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * {@link IssuedDocuments} qua cổng nội bộ của SVC-13 integration-gateway (DV-13-03), token dịch vụ
 * client-credentials của mission-core (vai trò {@code svc_mission_core}); tenant gửi cả ở tham số
 * {@code tenantId} lẫn {@code X-Tenant-Id}. Tìm văn bản là {@code GET
 * /internal/v1/integ/issued-docs}; chuyển tệp gốc sang SVC-08 là {@code POST
 * /internal/v1/integ/issued-docs/transfer} — đường dẫn và thân của lệnh chuyển là suy luận vì
 * DV-13-03 chưa công bố (wiki trang 14). Resilience4j instance {@link #INSTANCE}: timeout, circuit
 * breaker, thử lại cho GET và lệnh có {@code Idempotency-Key}.
 */
public class HttpIssuedDocuments implements IssuedDocuments {

  /** Tên instance Resilience4j và tên phụ thuộc trong lỗi 503. */
  public static final String INSTANCE = "integration-gateway";

  static final String SEARCH = "/internal/v1/integ/issued-docs";
  static final String TRANSFER = "/internal/v1/integ/issued-docs/transfer";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String CONNECTOR = "VOFFICE";
  static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final ResilientClient http;
  private final Supplier<String> token;

  public HttpIssuedDocuments(ResilientClient http, Supplier<String> token) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public Page search(TenantId tenant, UUID orgUnitId, String q, int limit, String cursor) {
    StringBuilder uri =
        new StringBuilder(SEARCH).append("?tenantId={t}&orgUnitId={u}&limit={l}&connector={c}");
    List<Object> vars = new ArrayList<>(List.of(tenant.value(), orgUnitId, limit, CONNECTOR));
    if (q != null) {
      uri.append("&q={q}");
      vars.add(q);
    }
    if (cursor != null) {
      uri.append("&cursor={k}");
      vars.add(cursor);
    }
    Map<?, ?> body;
    try {
      body = call(HttpMethod.GET, uri.toString(), null, headers(tenant), vars.toArray());
    } catch (NotFound e) {
      return new Page(List.of(), null);
    }
    List<Doc> items = new ArrayList<>();
    if (body.get("items") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          items.add(doc(m));
        }
      }
    }
    return new Page(items, string(body.get("nextCursor")));
  }

  @Override
  public Optional<Transferred> transfer(
      TenantId tenant,
      UUID orgUnitId,
      String ref,
      UUID evidenceId,
      UUID requestedBy,
      String idempotencyKey) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("tenantId", tenant.value().toString());
    request.put("orgUnitId", orgUnitId.toString());
    request.put("ref", ref);
    request.put("ownerRefId", evidenceId.toString());
    request.put("requestedBy", requestedBy.toString());
    request.put("connector", CONNECTOR);
    HttpHeaders h = headers(tenant);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set(ResilientClient.IDEMPOTENCY_KEY, idempotencyKey);
    Map<?, ?> body;
    try {
      body = call(HttpMethod.POST, TRANSFER, request, h);
    } catch (NotFound e) {
      return Optional.empty();
    }
    try {
      Map<?, ?> doc = body.get("doc") instanceof Map<?, ?> m ? m : Map.of();
      if (!Boolean.TRUE.equals(body.get("signatureValid"))) {
        // SVC-13 không chuyển tệp có chữ ký không hợp lệ.
        return Optional.of(new Transferred(null, 0, null, 0L, null, false, doc(doc)));
      }
      return Optional.of(
          new Transferred(
              UUID.fromString(string(body.get("objectId"))),
              ((Number) body.get("objectVersion")).intValue(),
              HexFormat.of().parseHex(string(body.get("sha256"))),
              ((Number) body.get("sizeBytes")).longValue(),
              string(body.get("mediaType")),
              Boolean.TRUE.equals(body.get("signatureValid")),
              doc(doc)));
    } catch (RuntimeException e) {
      throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, e);
    }
  }

  private HttpHeaders headers(TenantId tenant) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, tenant.value().toString());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    return h;
  }

  /** Lời gọi SVC-13: 404 là không có văn bản, lỗi khác là phụ thuộc không sẵn sàng (503). */
  private Map<?, ?> call(
      HttpMethod method, String uri, Object body, HttpHeaders headers, Object... vars) {
    try {
      Map<?, ?> result = http.exchange(method, uri, body, headers, Map.class, vars);
      if (result == null) {
        throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, null);
      }
      return result;
    } catch (HttpClientErrorException e) {
      if (HttpStatus.resolve(e.getStatusCode().value()) == HttpStatus.NOT_FOUND) {
        throw new NotFound();
      }
      throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, e);
    } catch (RestClientException | IllegalArgumentException e) {
      throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, e);
    }
  }

  private static Doc doc(Map<?, ?> m) {
    String date = string(m.get("docDate"));
    return new Doc(
        string(m.get("ref")),
        string(m.get("docNumber")),
        date == null ? null : LocalDate.parse(date),
        string(m.get("summary")),
        string(m.get("signerName")),
        Boolean.TRUE.equals(m.get("signatureValid")));
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  /** SVC-13 trả 404. */
  private static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFound() {
      super(null, null, false, false);
    }
  }
}
