package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.outbound.SignedMessage;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Adapter chiều ra dùng chung cho mọi luồng CloudEvents (DV-13-02): kiểm lại chính sách chia sẻ, ký
 * JWS theo {@link OutboundPolicy#jwsMode()}, gửi {@code POST} tới {@code connector.endpoint} qua
 * mTLS ({@link MtlsHttpClients}) với tiêu đề {@code Idempotency-Key} là mã thông điệp.
 *
 * <p>Xác nhận phía nhận (suy luận, wiki trang 14): {@code 202} là đã nhận và sẽ xác nhận sau (gọi
 * lại {@code acknowledge}); {@code 200}, {@code 201}, {@code 204} là xác nhận ngay, mã xác nhận lấy
 * ở tiêu đề {@code X-Ack-Ref} hoặc trường {@code ackRef} của thân JSON, không có thì ghi {@code
 * HTTP <mã>}. Mã khác là lỗi để pipeline thử lại; thân phản hồi không ghi nhật ký.
 */
public class CloudEventsHttpSender implements OutboundSender {

  public static final String ACK_HEADER = "X-Ack-Ref";
  static final int MAX_ACK = 128;

  private final OutboundPolicy policy;
  private final CloudEventsMapper mapper;
  private final PayloadSigner signer;
  private final MtlsHttpClients mtls;
  private final HttpClient plain;
  private final ConnectorSecrets secrets;
  private final ObjectMapper json;
  private final Duration timeout;

  public CloudEventsHttpSender(
      OutboundPolicy policy,
      CloudEventsMapper mapper,
      PayloadSigner signer,
      MtlsHttpClients mtls,
      HttpClient plain,
      ConnectorSecrets secrets,
      ObjectMapper json,
      Duration timeout) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.mtls = Objects.requireNonNull(mtls, "mtls");
    this.plain = Objects.requireNonNull(plain, "plain");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.json = Objects.requireNonNull(json, "json");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public String connectorCode() {
    return policy.connectorCode();
  }

  public OutboundPolicy policy() {
    return policy;
  }

  @Override
  public Optional<String> send(
      Connector connector, UUID messageId, String messageType, byte[] payload) throws IOException {
    mapper.recheck(payload, policy);
    SignedMessage message = SignedMessage.sign(payload, policy.jwsMode(), signer);
    URI target = URI.create(connector.endpoint());
    HttpClient http;
    if (policy.mtlsRequired()) {
      if (!"https".equals(target.getScheme().toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException(
            "Kết nối " + connector.code() + " bắt buộc mTLS nhưng địa chỉ không phải https");
      }
      http = mtls.client(connector.code());
    } else {
      http = plain;
    }
    HttpRequest.Builder req =
        HttpRequest.newBuilder(target)
            .timeout(timeout)
            .header("Content-Type", message.contentType())
            .header("Idempotency-Key", messageId.toString())
            .POST(HttpRequest.BodyPublishers.ofByteArray(message.body()));
    message.headers().forEach(req::header);
    if (connector.secretPath() != null) {
      secrets
          .token(connector.secretPath())
          .ifPresent(t -> req.header("Authorization", "Bearer " + t));
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gửi tới " + connector.code(), e);
    }
    int status = res.statusCode();
    if (status == 202) {
      return Optional.empty();
    }
    if (status == 200 || status == 201 || status == 204) {
      return Optional.of(ackRef(res));
    }
    throw new IOException("Bên nhận " + connector.code() + " trả mã " + status);
  }

  private String ackRef(HttpResponse<byte[]> res) {
    Optional<String> header = res.headers().firstValue(ACK_HEADER).filter(v -> !v.isBlank());
    if (header.isPresent()) {
      return cut(header.get());
    }
    byte[] body = res.body();
    if (body != null && body.length > 0) {
      try {
        JsonNode node = json.readTree(body);
        if (node != null
            && node.path("ackRef").isTextual()
            && !node.path("ackRef").asText().isBlank()) {
          return cut(node.path("ackRef").asText());
        }
      } catch (IOException e) {
        // Thân không phải JSON: dùng mã trạng thái làm xác nhận.
      }
    }
    return "HTTP " + res.statusCode();
  }

  private static String cut(String v) {
    return v.length() > MAX_ACK ? v.substring(0, MAX_ACK) : v;
  }
}
