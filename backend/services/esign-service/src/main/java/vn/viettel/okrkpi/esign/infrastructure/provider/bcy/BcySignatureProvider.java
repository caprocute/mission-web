package vn.viettel.okrkpi.esign.infrastructure.provider.bcy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.spi.SignRequest;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoLibrary;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoProvider;

/**
 * Nhà cung cấp chữ ký Ban Cơ yếu ({@code bcy}, mã {@code BAN_CO_YEU}) cho bản cách ly (DV-10-05,
 * QT-09, QT-11, ADR-06): ký bằng khoá trong thiết bị qua {@link BcyCryptoProvider} của chế độ
 * {@code okrkpi.crypto.provider=bcy}, kèm dấu thời gian theo quy định của Ban Cơ yếu.
 *
 * <p>Thiết bị ký ngay, không có bước người ký xác nhận trên điện thoại, nên {@link #begin} chỉ ghi
 * mã phiên {@code bcy:<mã yêu cầu>} và {@link #collect} ký từ các băm tài liệu đã lưu ở {@code
 * esign.sign_item}. Nhờ vậy phiên không phụ thuộc pod: worker ở pod nào thăm dò cũng ký được. Định
 * dạng là CAdES có dấu thời gian ({@code CADES_T} trong bộ giá trị TL-03); thư viện công bố định
 * dạng khác thì nhà cung cấp từ chối thay vì ghi sai {@code signature_format}.
 */
public final class BcySignatureProvider implements SignatureProvider {

  public static final String ID = "bcy";
  static final String SESSION_PREFIX = "bcy:";
  static final String LIBRARY_FORMAT = "CADES_B_T";

  private final BcyCryptoProvider crypto;
  private final SignRequestRepository requests;
  private final String keyLabelTemplate;
  private final Clock clock;

  public BcySignatureProvider(
      BcyCryptoProvider crypto,
      SignRequestRepository requests,
      String keyLabelTemplate,
      Clock clock) {
    this.crypto = crypto;
    this.requests = requests;
    this.keyLabelTemplate = keyLabelTemplate;
    this.clock = clock;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Set<SignatureFormat> supportedFormats() {
    return EnumSet.of(SignatureFormat.CADES_B_T);
  }

  @Override
  public SignSession begin(SignRequest request) {
    for (SignRequest.Document d : request.documents()) {
      if (d.format() != SignatureFormat.CADES_B_T) {
        throw new SignatureProviderException(
            "Ban Cơ yếu chỉ ký định dạng CAdES có dấu thời gian", true);
      }
    }
    return new SignSession(SESSION_PREFIX + request.requestId(), request.expiresAt());
  }

  @Override
  public SignStatus poll(String sessionId) {
    return requestOf(sessionId).isPresent() ? SignStatus.SIGNED : SignStatus.EXPIRED;
  }

  @Override
  public List<SignedItem> collect(String sessionId) {
    var request =
        requestOf(sessionId)
            .orElseThrow(
                () -> new SignatureProviderException("Không thấy yêu cầu của phiên ký", true));
    String keyLabel = keyLabelTemplate.replace("{signer}", request.signerPersonId().toString());
    List<SignedItem> out = new ArrayList<>();
    for (SignItem item : request.items()) {
      BcyCryptoLibrary.Signature signature;
      try {
        signature = crypto.sign(keyLabel, item.documentSha256());
      } catch (CryptoException e) {
        throw new SignatureProviderException("Thiết bị Ban Cơ yếu không ký được", false, e);
      }
      if (!LIBRARY_FORMAT.equals(signature.format())) {
        throw new SignatureProviderException(
            "Thư viện Ban Cơ yếu trả định dạng chữ ký chưa được duyệt: " + signature.format(),
            true);
      }
      out.add(
          new SignedItem(
              item.documentSha256(),
              signature.value(),
              null,
              certificate(signature.certificate()),
              signature.signedAt()));
    }
    return out;
  }

  /** Kiểm chữ ký đã lưu đối với băm tài liệu, bằng chính thư viện Ban Cơ yếu. */
  public boolean verify(byte[] signatureValue, byte[] documentSha256, SignedItem.Certificate cert) {
    BcyCryptoLibrary.Signature signature =
        new BcyCryptoLibrary.Signature(
            signatureValue,
            LIBRARY_FORMAT,
            new byte[0],
            new BcyCryptoLibrary.Certificate(
                cert.der(),
                cert.subjectDn(),
                cert.issuerDn(),
                cert.serialNo(),
                cert.notBefore(),
                cert.notAfter()),
            Instant.EPOCH);
    return crypto.verify(signature, documentSha256);
  }

  private Optional<vn.viettel.okrkpi.esign.domain.sign.SignRequest> requestOf(String sessionId) {
    if (sessionId == null || !sessionId.startsWith(SESSION_PREFIX)) {
      return Optional.empty();
    }
    UUID id;
    try {
      id = UUID.fromString(sessionId.substring(SESSION_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return requests.findById(id).filter(r -> clock.instant().isBefore(r.expiresAt()));
  }

  /** Bản cách ly không có OCSP trực tuyến: trạng thái {@code UNKNOWN}, chưa có lần kiểm. */
  private static SignedItem.Certificate certificate(BcyCryptoLibrary.Certificate c) {
    return new SignedItem.Certificate(
        c.der(),
        c.subjectDn(),
        c.issuerDn(),
        c.serialNo(),
        c.notBefore(),
        c.notAfter(),
        "UNKNOWN",
        null);
  }
}
