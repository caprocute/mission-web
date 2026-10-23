package vn.viettel.okrkpi.dossier.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * {@link EdxmlGateway} qua cổng nội bộ của integration-gateway (SVC-13, DV-13-05, wiki trang 14):
 * {@code POST /internal/v1/integ/edxml-transfers} (202; 422 kèm {@code reason} {@code
 * DESTINATION_NOT_CONFIGURED} hoặc {@code PAYLOAD_TOO_LARGE}; 413 khi thân quá 28 MB) và {@code GET
 * .../{transferId}}. Gọi bằng token dịch vụ, tenant ở {@code X-Tenant-Id}, {@code Idempotency-Key}
 * là {@code transferId} nên được thử lại an toàn. Tệp đính kèm mã base64 chuẩn, không xuống dòng.
 *
 * <p>{@code lastError} của SVC-13 là mã lỗi của thư chết (tên lớp ngoại lệ); cổng đổi sang lý do
 * tiếng Việt cho MSG-11-06 (suy luận).
 */
public class HttpEdxmlGateway implements EdxmlGateway {

  public static final String INSTANCE = "dossier-integ";
  static final String TRANSFERS = "/internal/v1/integ/edxml-transfers";
  static final String TRANSFER = TRANSFERS + "/{id}";
  static final String TENANT_HEADER = "X-Tenant-Id";

  static final String REASON_TIMEOUT = "bên nhận không phản hồi";
  static final String REASON_CERTIFICATE = "chứng thư số của bên nhận đã hết hạn hoặc không hợp lệ";
  static final String REASON_OTHER = "bên nhận chưa tiếp nhận gói con";

  private final ResilientClient client;
  private final Supplier<String> token;
  private final ObjectMapper json;

  public HttpEdxmlGateway(ResilientClient client, Supplier<String> token, ObjectMapper json) {
    this.client = Objects.requireNonNull(client, "client");
    this.token = Objects.requireNonNull(token, "token");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public RemoteState send(TenantId tenant, Outgoing o) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transferId", o.transferId().toString());
    body.put("packageId", o.packageId().toString());
    body.put("destination", o.destination());
    body.put("subject", o.subject());
    if (o.documentNumber() != null) {
      body.put("documentNumber", o.documentNumber());
    }
    List<Map<String, String>> files = new ArrayList<>();
    for (Attachment a : o.attachments()) {
      Map<String, String> f = new LinkedHashMap<>();
      f.put("name", a.name());
      f.put("contentType", a.contentType());
      f.put("contentBase64", Base64.getEncoder().encodeToString(a.content()));
      files.add(f);
    }
    body.put("attachments", files);
    HttpHeaders h = headers(tenant);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set(ResilientClient.IDEMPOTENCY_KEY, o.transferId().toString());
    JsonNode res;
    try {
      res = client.exchange(HttpMethod.POST, TRANSFERS, body, h, JsonNode.class);
    } catch (RuntimeException e) {
      RestClientResponseException http = http(e);
      if (http != null && http.getStatusCode().value() == 413) {
        throw new PayloadTooLargeException();
      }
      if (http != null && http.getStatusCode().value() == 422) {
        String reason = reason(http);
        if ("PAYLOAD_TOO_LARGE".equals(reason)) {
          throw new PayloadTooLargeException();
        }
        if ("DESTINATION_NOT_CONFIGURED".equals(reason)) {
          throw new DestinationNotConfiguredException(o.destination());
        }
      }
      throw new UnavailableException("SVC-13 chưa nhận gói con: " + e.getMessage(), e);
    }
    if (res == null || res.path("status").isMissingNode()) {
      return new RemoteState(o.transferId(), "CHO", 0, null, null, null, null);
    }
    return state(o.transferId(), res);
  }

  @Override
  public Optional<RemoteState> state(TenantId tenant, UUID transferId) {
    JsonNode res;
    try {
      res =
          client.exchange(
              HttpMethod.GET, TRANSFER, null, headers(tenant), JsonNode.class, transferId);
    } catch (RuntimeException e) {
      // 404 (SVC-13 chưa có lần gửi), 5xx, mất kết nối: lượt sau hỏi lại hoặc gửi lại.
      return Optional.empty();
    }
    if (res == null || !res.hasNonNull("status")) {
      return Optional.empty();
    }
    return Optional.of(state(transferId, res));
  }

  private RemoteState state(UUID transferId, JsonNode n) {
    String status = n.path("status").asText();
    Instant acked = time(n.path("acknowledgedAt"));
    return new RemoteState(
        transferId,
        status,
        n.path("attempts").asInt(0),
        time(n.path("nextAttemptAt")),
        text(n.path("ackRef")),
        acked,
        reasonText(text(n.path("lastError"))));
  }

  /** Lý do tiếng Việt theo mã lỗi thư chết của SVC-13. */
  static String reasonText(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String c = code.toLowerCase(java.util.Locale.ROOT);
    if (c.contains("certificate") || c.contains("ssl") || c.contains("cert_")) {
      return REASON_CERTIFICATE;
    }
    if (c.contains("timeout")
        || c.contains("connect")
        || c.contains("illegalstate")
        || c.contains("unreachable")) {
      return REASON_TIMEOUT;
    }
    return REASON_OTHER;
  }

  private String reason(RestClientResponseException e) {
    try {
      JsonNode p = json.readTree(e.getResponseBodyAsByteArray());
      return p == null ? null : p.path("reason").asText(null);
    } catch (java.io.IOException | RuntimeException ignored) {
      return null;
    }
  }

  private static RestClientResponseException http(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof RestClientResponseException r) {
        return r;
      }
    }
    return null;
  }

  private HttpHeaders headers(TenantId tenant) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, tenant.value().toString());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    return h;
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
  }

  private static Instant time(JsonNode n) {
    String v = text(n);
    if (v == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(v).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(v);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
