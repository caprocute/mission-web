package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignRequest;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;

/**
 * Nhà cung cấp giả lập cho dev/test (DV-10-01): người ký "xác nhận" sau {@code confirmAfter}; chữ
 * ký là băm xác định của tài liệu, không phải chữ ký thật. Không bao giờ bật ở production ({@link
 * EsignConfiguration} chặn lúc khởi động).
 */
public class FakeSignatureProvider implements SignatureProvider {

  public static final String ID = "fake";

  /** Kết quả mà phiên kế tiếp sẽ nhận, để kiểm thử các nhánh. */
  public enum Outcome {
    SIGN,
    REJECT,
    NEVER_CONFIRM
  }

  private static final byte[] CERT_DER =
      "FAKE-CERT:Nguoi ky thu nghiem".getBytes(StandardCharsets.UTF_8);

  private final Clock clock;
  private final Duration confirmAfter;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private volatile Outcome nextOutcome = Outcome.SIGN;

  public FakeSignatureProvider(Clock clock, Duration confirmAfter) {
    this.clock = clock;
    this.confirmAfter = confirmAfter;
  }

  public void nextOutcome(Outcome outcome) {
    this.nextOutcome = outcome;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Set<SignatureFormat> supportedFormats() {
    return EnumSet.of(SignatureFormat.PADES_B_LTA, SignatureFormat.CADES_B_T, SignatureFormat.JWS);
  }

  @Override
  public SignSession begin(SignRequest request) {
    String id = "fake-" + UUID.randomUUID();
    sessions.put(id, new Session(request, clock.instant(), nextOutcome));
    return new SignSession(id, request.expiresAt());
  }

  /**
   * Ký lô giả lập như HSM của tổ chức (DV-10-06): lần step-up của người quyết định khi tạo yêu cầu
   * là xác nhận cho cả lô, nên phiên được ủy quyền ngay.
   */
  @Override
  public Optional<BatchMode> batchMode() {
    return Optional.of(BatchMode.STEP_UP);
  }

  @Override
  public SignSession beginBatch(SignRequest request) {
    String id = "fake-lo-" + UUID.randomUUID();
    sessions.put(id, new Session(request, clock.instant().minus(confirmAfter), Outcome.SIGN));
    return new SignSession(id, request.expiresAt());
  }

  @Override
  public SignedItem signItem(String sessionId, SignRequest.Document document) {
    if (!sessions.containsKey(sessionId)) {
      throw new SignatureProviderException("Không có phiên ký lô " + sessionId, true);
    }
    Instant now = clock.instant();
    return new SignedItem(
        document.sha256(),
        Hashes.sha256(concat("FAKE-SIG:".getBytes(StandardCharsets.UTF_8), document.sha256())),
        null,
        certificate(now),
        now);
  }

  @Override
  public SignStatus poll(String sessionId) {
    Session session = sessions.get(sessionId);
    if (session == null) {
      return SignStatus.EXPIRED;
    }
    Instant now = clock.instant();
    if (!now.isBefore(session.request().expiresAt())) {
      return SignStatus.EXPIRED;
    }
    if (now.isBefore(session.startedAt().plus(confirmAfter))) {
      return SignStatus.PENDING;
    }
    return switch (session.outcome()) {
      case SIGN -> SignStatus.SIGNED;
      case REJECT -> SignStatus.REJECTED;
      case NEVER_CONFIRM -> SignStatus.PENDING;
    };
  }

  @Override
  public List<SignedItem> collect(String sessionId) {
    Session session = sessions.get(sessionId);
    if (session == null) {
      return List.of();
    }
    Instant now = clock.instant();
    SignedItem.Certificate certificate = certificate(now);
    return session.request().documents().stream()
        .map(
            d ->
                new SignedItem(
                    d.sha256(),
                    Hashes.sha256(concat("FAKE-SIG:".getBytes(StandardCharsets.UTF_8), d.sha256())),
                    null,
                    certificate,
                    now))
        .toList();
  }

  private static SignedItem.Certificate certificate(Instant now) {
    return new SignedItem.Certificate(
        CERT_DER,
        "CN=Người ký thử nghiệm",
        "CN=Nhà cung cấp giả lập",
        "FAKE-0001",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(365)),
        "GOOD",
        now);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private record Session(SignRequest request, Instant startedAt, Outcome outcome) {}
}
