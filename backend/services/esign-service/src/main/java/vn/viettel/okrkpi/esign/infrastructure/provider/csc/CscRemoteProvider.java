package vn.viettel.okrkpi.esign.infrastructure.provider.csc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.Cms;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.CscClient;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.ObjectStorage;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.PdfSignatures;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.TimestampClient;
import vn.viettel.okrkpi.esign.infrastructure.provider.trust.CertificateTrust;
import vn.viettel.okrkpi.esign.spi.SignRequest;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;

/**
 * Nhà cung cấp ký từ xa theo CSC API v2 dùng chung cho MySign (DV-10-02), Viettel-CA và nhánh ký từ
 * xa của CA chuyên dùng Chính phủ (DV-10-04). Mở phiên: chọn chứng thư của người ký ({@code
 * credentials/list}, {@code credentials/info}), chuẩn bị vùng ký PDF và thuộc tính CMS ngay tại
 * esign, gửi băm (không gửi tài liệu) qua {@code signatures/signHash} bất đồng bộ; người ký xác
 * nhận trên ứng dụng của nhà cung cấp. Thăm dò tới khi có chữ ký thô, rồi dựng CMS kèm dấu thời
 * gian (PAdES-B-T), nhúng vào PDF và ghi tệp đã ký lên kho đối tượng. Trạng thái phiên giữa hai
 * bước nằm trên kho đối tượng, nên bước nào chạy ở bản sao nào cũng được.
 *
 * <p>Khi có {@link CertificateTrust}, chuỗi chứng thư phải dẫn về chứng thư gốc đã nạp cho nhà cung
 * cấp và trạng thái thu hồi lấy từ OCSP/CRL của CA (chứng thư bị thu hồi thì không ký); khi không
 * có, dùng trạng thái chứng thư do máy chủ CSC trả (cách của DV-10-02).
 *
 * <p>Mức B-LTA (DSS với OCSP/CRL và dấu thời gian lưu trữ) bổ sung khi DV-10-03 có TSA và kho dữ
 * liệu kiểm tra chứng thư; tới lúc đó tệp đạt B-T khi có {@code tsa-url}, B-B khi không.
 */
public class CscRemoteProvider implements SignatureProvider {

  private static final Logger LOG = LoggerFactory.getLogger(CscRemoteProvider.class);
  private static final String PDF = "application/pdf";

  private final CscClient csc;
  private final ObjectStorage storage;
  private final TimestampClient tsa;
  private final Settings settings;
  private final CertificateTrust trust;
  private final ObjectMapper json;
  private final Clock clock;

  /**
   * Tham số của một nhà cung cấp.
   *
   * @param id mã nhà cung cấp ({@link SignatureProvider#id()})
   * @param label tên hiển thị trong nhật ký và thông báo lỗi
   * @param signatureSize số byte chừa cho chữ ký trong PDF
   * @param signedPrefix tiền tố khoá đối tượng của tệp đã ký
   */
  public record Settings(String id, String label, int signatureSize, String signedPrefix) {}

  /**
   * @param tsa máy chủ dấu thời gian, {@code null} thì chữ ký chỉ đạt B-B
   * @param trust kho gốc và kiểm tra thu hồi, {@code null} thì tin trạng thái do máy chủ CSC trả
   */
  public CscRemoteProvider(
      Settings settings,
      CscClient csc,
      ObjectStorage storage,
      TimestampClient tsa,
      CertificateTrust trust,
      ObjectMapper json,
      Clock clock) {
    this.settings = settings;
    this.csc = csc;
    this.storage = storage;
    this.tsa = tsa;
    this.trust = trust;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String id() {
    return settings.id();
  }

  @Override
  public Set<SignatureFormat> supportedFormats() {
    return EnumSet.of(SignatureFormat.PADES_B_LTA, SignatureFormat.CADES_B_T);
  }

  @Override
  public boolean available() {
    return csc.available();
  }

  @Override
  public SignSession begin(SignRequest request) {
    Instant now = clock.instant();
    Credential credential = chooseCredential(request, now);
    X509Certificate signer = credential.chain().get(0);
    String sessionId = settings.id() + "-" + request.requestId();
    Calendar signDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    signDate.setTime(Date.from(now));
    List<DocState> docs = new ArrayList<>();
    List<byte[]> hashes = new ArrayList<>();
    int index = 0;
    for (SignRequest.Document doc : request.documents()) {
      byte[] contentDigest;
      String preparedKey = null;
      if (doc.format() == SignatureFormat.PADES_B_LTA) {
        byte[] pdf =
            storage
                .get(doc.documentRef())
                .orElseThrow(
                    () -> new SignatureProviderException("Không có tệp cần ký trong kho", true));
        if (!Arrays.equals(Hashes.sha256(pdf), doc.sha256())) {
          throw new SignatureProviderException("Tệp trong kho không khớp băm của hồ sơ", true);
        }
        PdfSignatures.Prepared prepared =
            PdfSignatures.prepare(pdf, signDate, settings.signatureSize());
        preparedKey = "tam/" + sessionId + "/" + index + ".pdf";
        storage.put(preparedKey, prepared.pdf(), PDF);
        contentDigest = prepared.contentDigest();
      } else if (doc.format() == SignatureFormat.CADES_B_T) {
        contentDigest = doc.sha256();
      } else {
        throw new SignatureProviderException(
            settings.label() + " chưa hỗ trợ định dạng " + doc.format(), true);
      }
      byte[] signedAttributes = Cms.signedAttributes(contentDigest, signer);
      hashes.add(Hashes.sha256(signedAttributes));
      docs.add(
          new DocState(
              doc.documentRef(),
              HexFormat.of().formatHex(doc.sha256()),
              doc.format().name(),
              Base64.getEncoder().encodeToString(signedAttributes),
              preparedKey));
      index++;
    }
    String signAlgo = Cms.signAlgo(signer);
    String responseId = csc.signHash(credential.id(), hashes, signAlgo);
    SessionState state =
        new SessionState(
            request.requestId().toString(),
            responseId,
            credential.chain().stream().map(CscRemoteProvider::derB64).toList(),
            credential.status(),
            docs);
    storage.put(stateKey(sessionId), write(state), "application/json");
    return new SignSession(sessionId, request.expiresAt());
  }

  @Override
  public SignStatus poll(String sessionId) {
    return switch (csc.poll(read(sessionId).responseId()).state()) {
      case PENDING -> SignStatus.PENDING;
      case SIGNED -> SignStatus.SIGNED;
      case REJECTED -> SignStatus.REJECTED;
      case EXPIRED -> SignStatus.EXPIRED;
    };
  }

  @Override
  public List<SignedItem> collect(String sessionId) {
    SessionState state = read(sessionId);
    CscClient.Poll poll = csc.poll(state.responseId());
    if (poll.state() != CscClient.Poll.State.SIGNED
        || poll.signatures().size() != state.docs().size()) {
      throw new SignatureProviderException(
          settings.label()
              + " trả "
              + poll.signatures().size()
              + "/"
              + state.docs().size()
              + " chữ ký",
          true);
    }
    List<X509Certificate> chain = state.chain().stream().map(this::parse).toList();
    SignedItem.Certificate certificate = certificate(chain.get(0), state.certStatus());
    Instant now = clock.instant();
    List<SignedItem> items = new ArrayList<>();
    for (int i = 0; i < state.docs().size(); i++) {
      DocState doc = state.docs().get(i);
      byte[] signature = poll.signatures().get(i);
      byte[] token = tsa == null ? null : tsa.timestamp(signature);
      byte[] cms =
          Cms.signedData(
              Base64.getDecoder().decode(doc.signedAttributes()), signature, chain, token);
      String signedKey = null;
      if (doc.preparedKey() != null) {
        byte[] prepared =
            storage
                .get(doc.preparedKey())
                .orElseThrow(
                    () -> new SignatureProviderException("Mất tệp chuẩn bị ký của phiên", true));
        signedKey = settings.signedPrefix() + "/" + state.requestId() + "/" + i + ".pdf";
        storage.put(signedKey, PdfSignatures.embed(prepared, cms), PDF);
      }
      items.add(
          new SignedItem(HexFormat.of().parseHex(doc.sha256()), cms, signedKey, certificate, now));
    }
    if (tsa == null) {
      LOG.warn("Chưa cấu hình máy chủ dấu thời gian: chữ ký phiên {} chỉ đạt mức B-B", sessionId);
    }
    return items;
  }

  /**
   * Chứng thư của người ký ở nhà cung cấp, kèm trạng thái thu hồi (để liệt kê và đăng ký vào {@code
   * esign.certificate_ref}). Chứng thư không dẫn về gốc tin cậy bị bỏ qua.
   */
  @Override
  public List<SignedItem.Certificate> certificates(UUID tenantId, UUID personId) {
    List<SignedItem.Certificate> out = new ArrayList<>();
    for (String id : csc.credentials(personId.toString())) {
      CscClient.CredentialInfo info = csc.info(id);
      List<X509Certificate> chain = chain(info);
      if (chain.isEmpty() || (trust != null && !trust.trusted(chain))) {
        continue;
      }
      out.add(certificate(chain.get(0), status(chain, info)));
    }
    return out;
  }

  @Override
  public boolean checksCertificates() {
    return trust != null;
  }

  /** Hỏi lại OCSP/CRL cho chứng thư đã lưu; rỗng khi chứng thư không thuộc gốc của nhà cung cấp. */
  @Override
  public Optional<SignedItem.Certificate> recheck(byte[] certificateDer) {
    if (trust == null) {
      return Optional.empty();
    }
    List<X509Certificate> chain = List.of(parse(certificateDer));
    if (!trust.trusted(chain)) {
      return Optional.empty();
    }
    return Optional.of(certificate(chain.get(0), trust.check(chain).status().name()));
  }

  private Credential chooseCredential(SignRequest request, Instant now) {
    byte[] wanted = request.certificateSha256();
    for (String id : csc.credentials(request.signerPersonId().toString())) {
      CscClient.CredentialInfo info = csc.info(id);
      if (info.key() != null && "disabled".equalsIgnoreCase(info.key().status())) {
        continue;
      }
      List<X509Certificate> chain = chain(info);
      if (chain.isEmpty()) {
        continue;
      }
      X509Certificate leaf = chain.get(0);
      if (wanted != null && !Arrays.equals(wanted, Hashes.sha256(der(leaf)))) {
        continue;
      }
      if (trust != null && !trust.trusted(chain)) {
        if (wanted != null) {
          throw new SignatureProviderException(
              "Chứng thư số đã chọn không thuộc chuỗi gốc tin cậy của " + settings.label(), true);
        }
        continue;
      }
      String status = status(chain, info);
      if (!CertificateTrust.Status.GOOD.name().equals(status) || !validAt(leaf, now)) {
        if (wanted != null) {
          throw new SignatureProviderException(
              "Chứng thư số đã chọn hết hạn hoặc bị thu hồi", true);
        }
        continue;
      }
      return new Credential(id, chain, status);
    }
    throw new SignatureProviderException(
        "Người ký không có chứng thư số hợp lệ ở " + settings.label(), true);
  }

  private List<X509Certificate> chain(CscClient.CredentialInfo info) {
    return info.cert().certificates().stream()
        .map(b -> parse(Base64.getDecoder().decode(b)))
        .toList();
  }

  /**
   * Trạng thái thu hồi chuẩn hoá ({@code GOOD}, {@code REVOKED}, {@code UNKNOWN}): theo OCSP/CRL
   * của CA khi có kho gốc, theo trạng thái máy chủ CSC trả khi không.
   */
  private String status(List<X509Certificate> chain, CscClient.CredentialInfo info) {
    if (trust != null) {
      return trust.check(chain).status().name();
    }
    return switch (info.cert().status() == null
        ? ""
        : info.cert().status().toLowerCase(Locale.ROOT)) {
      case "valid" -> CertificateTrust.Status.GOOD.name();
      case "revoked" -> CertificateTrust.Status.REVOKED.name();
      default -> CertificateTrust.Status.UNKNOWN.name();
    };
  }

  private static boolean validAt(X509Certificate cert, Instant now) {
    try {
      cert.checkValidity(Date.from(now));
      return true;
    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
      return false;
    }
  }

  private SignedItem.Certificate certificate(X509Certificate leaf, String status) {
    String ocsp = status == null ? CertificateTrust.Status.UNKNOWN.name() : status;
    return new SignedItem.Certificate(
        der(leaf),
        leaf.getSubjectX500Principal().getName(),
        leaf.getIssuerX500Principal().getName(),
        leaf.getSerialNumber().toString(16),
        leaf.getNotBefore().toInstant(),
        leaf.getNotAfter().toInstant(),
        ocsp,
        clock.instant());
  }

  private static String stateKey(String sessionId) {
    return "tam/" + sessionId + "/state.json";
  }

  private SessionState read(String sessionId) {
    byte[] raw =
        storage
            .get(stateKey(sessionId))
            .orElseThrow(
                () -> new SignatureProviderException("Không có phiên ký " + sessionId, true));
    try {
      return json.readValue(raw, SessionState.class);
    } catch (java.io.IOException e) {
      throw new SignatureProviderException("Phiên ký hỏng: " + sessionId, true, e);
    }
  }

  private byte[] write(SessionState state) {
    try {
      return json.writeValueAsString(state).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] der(X509Certificate cert) {
    try {
      return cert.getEncoded();
    } catch (java.security.cert.CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String derB64(X509Certificate cert) {
    return Base64.getEncoder().encodeToString(der(cert));
  }

  private X509Certificate parse(String b64) {
    return parse(Base64.getDecoder().decode(b64));
  }

  private X509Certificate parse(byte[] der) {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(der));
    } catch (CertificateException e) {
      throw new SignatureProviderException(
          "Chứng thư " + settings.label() + " trả về không đọc được", true, e);
    }
  }

  private record Credential(String id, List<X509Certificate> chain, String status) {}

  /** Trạng thái phiên lưu ở kho đối tượng giữa lúc mở phiên và lúc nhận chữ ký. */
  record SessionState(
      String requestId,
      String responseId,
      List<String> chain,
      String certStatus,
      List<DocState> docs) {}

  /** Một tài liệu của phiên: thuộc tính ký DER (base64) và khoá tệp PDF đã chừa vùng ký. */
  record DocState(
      String ref, String sha256, String format, String signedAttributes, String preparedKey) {}
}
