package vn.viettel.okrkpi.integ.infrastructure.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.integ.application.anchor.AnchorLedger;
import vn.viettel.okrkpi.integ.application.anchor.CellAnchor;

/**
 * Gọi cổng nội bộ của ledger-service qua lưới dịch vụ (mTLS của Istio, suy luận, wiki trang 14):
 *
 * <ul>
 *   <li>{@code GET /internal/v1/ledger/cell-anchors?cell=&windowEnd=}: gói neo cấp cell của ngày
 *       kết thúc ở {@code windowEnd} ({@code AnchorDocument} của {@code seal.Anchorer.DailyAnchor}
 *       trong {@code services-go/ledger}); {@code 404} là chưa có.
 *   <li>{@code POST /internal/v1/ledger/anchor-receipts} kèm {@code Idempotency-Key} là mã bản neo
 *       của đích: ledger-service ghi {@code ledger.anchor_receipt}; {@code 409} là đã ghi (gửi lại
 *       sau sự cố), coi như xong.
 * </ul>
 */
public class HttpAnchorLedger implements AnchorLedger {

  static final String CELL_ANCHORS = "/internal/v1/ledger/cell-anchors";
  static final String RECEIPTS = "/internal/v1/ledger/anchor-receipts";

  private final URI base;
  private final HttpClient http;
  private final Duration timeout;
  private final Supplier<Optional<String>> token;
  private final ObjectMapper json;

  public HttpAnchorLedger(
      URI base,
      HttpClient http,
      Duration timeout,
      Supplier<Optional<String>> token,
      ObjectMapper json) {
    this.base = Objects.requireNonNull(base, "base");
    this.http = Objects.requireNonNull(http, "http");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.token = Objects.requireNonNull(token, "token");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<CellAnchor> cellAnchor(String cellCode, Instant windowEnd) throws IOException {
    URI uri =
        base.resolve(
            CELL_ANCHORS
                + "?cell="
                + URLEncoder.encode(cellCode, StandardCharsets.UTF_8)
                + "&windowEnd="
                + URLEncoder.encode(windowEnd.toString(), StandardCharsets.UTF_8));
    HttpResponse<byte[]> res = send(request(uri).GET().build());
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() != 200) {
      throw new IOException("ledger-service trả mã " + res.statusCode());
    }
    return Optional.of(parse(json.readTree(res.body())));
  }

  static CellAnchor parse(JsonNode n) throws IOException {
    try {
      HexFormat hex = HexFormat.of();
      Base64.Decoder b64 = Base64.getDecoder();
      return new CellAnchor(
          UUID.fromString(n.path("cellSealId").asText()),
          n.path("cellCode").asText(),
          instant(n.path("windowStart")),
          Instant.parse(n.path("windowEnd").asText()),
          hex.parseHex(n.path("cellRoot").asText()),
          optionalHex(n.path("prevCellRoot")),
          b64.decode(n.path("signature").asText()),
          n.path("signerCertSha256").isTextual() ? n.path("signerCertSha256").asText() : null,
          b64.decode(n.path("tsaToken").asText()),
          instant(n.path("tsaGenTime")));
    } catch (RuntimeException e) {
      throw new IOException("Gói neo của ledger-service không hợp lệ", e);
    }
  }

  /** ledger-service ghi chuỗi rỗng cho gốc ngày trước của niêm phong đầu tiên. */
  private static byte[] optionalHex(JsonNode n) {
    return n.isTextual() && !n.asText().isEmpty() ? HexFormat.of().parseHex(n.asText()) : null;
  }

  private static Instant instant(JsonNode n) {
    return n.isTextual() ? Instant.parse(n.asText()) : null;
  }

  @Override
  public void submit(Receipt r) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("cellSealId", r.cellSealId().toString());
    body.put("anchorTarget", r.target().name());
    body.put("connectorCode", r.connectorCode());
    body.put("receipt", Base64.getEncoder().encodeToString(r.receipt()));
    body.put("receivedAt", r.receivedAt().toString());
    HttpRequest req =
        request(base.resolve(RECEIPTS))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", r.dispatchId().toString())
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
            .build();
    int status = send(req).statusCode();
    if (status != 200 && status != 201 && status != 204 && status != 409) {
      throw new IOException("ledger-service trả mã " + status);
    }
  }

  private HttpRequest.Builder request(URI uri) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
    token.get().ifPresent(t -> b.header("Authorization", "Bearer " + t));
    return b;
  }

  private HttpResponse<byte[]> send(HttpRequest req) throws IOException {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi ledger-service", e);
    }
  }
}
