package vn.viettel.okrkpi.task.infrastructure.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException.Reason;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocContents;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Paragraph;

/**
 * {@link IncomingDocContents} qua cổng nội bộ {@code GET
 * /internal/v1/integ/received-docs/{documentId}} của SVC-13 integration-gateway (suy luận, wiki
 * trang 14), token dịch vụ của mission-core; tenant gửi ở tham số {@code tenantId} và {@code
 * X-Tenant-Id}. 404 là SVC-13 không có nội dung, lỗi khác là phụ thuộc không sẵn sàng.
 */
public class HttpIncomingDocContents implements IncomingDocContents {

  /** Tên instance Resilience4j, dùng chung với cổng văn bản đã ban hành của MOD-03. */
  public static final String INSTANCE = "integration-gateway";

  static final String PATH = "/internal/v1/integ/received-docs/{id}?tenantId={t}";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final ResilientClient http;
  private final Supplier<String> token;

  public HttpIncomingDocContents(ResilientClient http, Supplier<String> token) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public Optional<Content> fetch(TenantId tenant, UUID documentId) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, tenant.value().toString());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    Map<?, ?> body;
    try {
      body = http.exchange(HttpMethod.GET, PATH, null, h, Map.class, documentId, tenant.value());
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 404) {
        return Optional.empty();
      }
      throw unavailable(e);
    } catch (RestClientException | IllegalArgumentException e) {
      throw unavailable(e);
    }
    if (body == null) {
      throw unavailable(null);
    }
    try {
      List<Paragraph> paragraphs = new ArrayList<>();
      if (body.get("paragraphs") instanceof List<?> list) {
        for (Object o : list) {
          if (o instanceof Map<?, ?> p && p.get("no") instanceof Number n) {
            paragraphs.add(new Paragraph(n.intValue(), string(p.get("text"))));
          }
        }
      }
      String unit = string(body.get("recipientUnitId"));
      return Optional.of(
          new Content(
              unit == null ? null : UUID.fromString(unit),
              string(body.get("format")),
              string(body.get("issuerHeader")),
              string(body.get("title")),
              string(body.get("preamble")),
              paragraphs,
              string(body.get("recipients")),
              string(body.get("classification"))));
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  private static DependencyUnavailableException unavailable(Throwable cause) {
    return new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, cause);
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }
}
