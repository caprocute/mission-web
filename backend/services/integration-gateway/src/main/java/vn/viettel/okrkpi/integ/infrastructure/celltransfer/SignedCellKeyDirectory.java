package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.integ.infrastructure.crypto.PemKeys;

/**
 * Danh bạ khoá cell có ký của vùng điều phối toàn cục (DV-13-10, suy luận về nơi lưu, wiki trang
 * 14). Tài liệu dạng {@code {"payload": base64(JSON), "signature": base64}}, chữ ký ECDSA P-256
 * SHA-256 bằng khoá gốc của vùng toàn cục; khoá công khai gốc là mỏ neo tin cậy cấu hình sẵn ở mỗi
 * cell. Chỉ dùng tài liệu khi chữ ký đúng, chưa quá {@code expiresAt}, phiên bản không lùi; mỗi
 * cell chỉ có hiệu lực trong {@code [notBefore, notAfter)}. Tải lại theo chu kỳ, tải lỗi thì giữ
 * bản tốt gần nhất cho tới khi hết hạn.
 */
public class SignedCellKeyDirectory implements CellKeyDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(SignedCellKeyDirectory.class);

  /** Một mục cell đã kiểm, kèm thời hạn. */
  record Entry(CellKeys keys, Instant notBefore, Instant notAfter) {}

  /** Tài liệu đã kiểm. */
  record Document(long version, Instant expiresAt, Map<String, Entry> cells) {}

  private final Supplier<byte[]> source;
  private final PublicKey root;
  private final ObjectMapper json;
  private final Clock clock;
  private final Duration refresh;
  private final boolean allowHttp;
  private Document current;
  private Instant loadedAt = Instant.EPOCH;

  public SignedCellKeyDirectory(
      Supplier<byte[]> source,
      PublicKey root,
      ObjectMapper json,
      Clock clock,
      Duration refresh,
      boolean allowHttp) {
    this.source = Objects.requireNonNull(source, "source");
    this.root = Objects.requireNonNull(root, "root");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.refresh = Objects.requireNonNull(refresh, "refresh");
    this.allowHttp = allowHttp;
  }

  @Override
  public synchronized Optional<CellKeys> find(String cellCode) {
    Instant now = clock.instant();
    if (current == null || !now.isBefore(loadedAt.plus(refresh))) {
      try {
        Document loaded = parse(source.get(), now);
        if (current != null && loaded.version() < current.version()) {
          throw new IllegalStateException("Danh bạ khoá cell bị lùi phiên bản");
        }
        current = loaded;
        loadedAt = now;
      } catch (RuntimeException e) {
        LOG.warn("Không tải được danh bạ khoá cell: {}", ErrorText.code(e));
        loadedAt = now.minus(refresh).plusSeconds(Math.min(30, refresh.toSeconds()));
      }
    }
    if (current == null || !now.isBefore(current.expiresAt())) {
      return Optional.empty();
    }
    Entry e = current.cells().get(cellCode);
    if (e == null || now.isBefore(e.notBefore()) || !now.isBefore(e.notAfter())) {
      return Optional.empty();
    }
    return Optional.of(e.keys());
  }

  Document parse(byte[] raw, Instant now) {
    try {
      JsonNode envelope = json.readTree(raw);
      byte[] payload = Base64.getDecoder().decode(envelope.path("payload").asText(""));
      byte[] signature = Base64.getDecoder().decode(envelope.path("signature").asText(""));
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(root);
      s.update(payload);
      if (!s.verify(signature)) {
        throw new IllegalStateException("Chữ ký danh bạ khoá cell không đúng");
      }
      JsonNode doc = json.readTree(payload);
      Instant expires = Instant.parse(doc.path("expiresAt").asText());
      if (!now.isBefore(expires)) {
        throw new IllegalStateException("Danh bạ khoá cell đã hết hạn");
      }
      Map<String, Entry> cells = new HashMap<>();
      for (JsonNode c : doc.path("cells")) {
        String code = c.path("cell").asText("");
        URI endpoint = URI.create(c.path("endpoint").asText(""));
        if (!"https".equals(endpoint.getScheme())
            && !(allowHttp && "http".equals(endpoint.getScheme()))) {
          throw new IllegalStateException("Kênh liên cell phải dùng https");
        }
        // Khoá ký: EC P-256 của cell, hoặc khoá của chứng thư tổ chức khi ký qua SVC-10 (EC hoặc
        // RSA).
        String signingAlg = c.path("signingKeyAlg").asText("EC");
        if (!"EC".equals(signingAlg) && !"RSA".equals(signingAlg)) {
          throw new IllegalStateException("Thuật toán khoá ký cell chưa hỗ trợ");
        }
        cells.put(
            code,
            new Entry(
                new CellKeys(
                    code,
                    endpoint,
                    PemKeys.publicKey(c.path("signingKey").asText(""), signingAlg),
                    PemKeys.publicKey(c.path("encryptionKey").asText(""), "RSA")),
                Instant.parse(c.path("notBefore").asText()),
                Instant.parse(c.path("notAfter").asText())));
      }
      return new Document(doc.path("version").asLong(0), expires, Map.copyOf(cells));
    } catch (IOException
        | GeneralSecurityException
        | IllegalArgumentException
        | java.time.format.DateTimeParseException e) {
      throw new IllegalStateException("Danh bạ khoá cell không hợp lệ", e);
    }
  }
}
