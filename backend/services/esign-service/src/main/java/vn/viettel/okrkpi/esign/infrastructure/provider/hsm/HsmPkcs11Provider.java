package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.esign.application.seal.TimestampAuthority;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.Cms;
import vn.viettel.okrkpi.esign.spi.SignRequest;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;

/**
 * Nhà cung cấp chữ ký tổ chức (dấu cơ quan) bằng khoá trong HSM qua PKCS#11 (DV-10-03, TL-01
 * "SVC-10 esign-service"). Không có bước người ký xác nhận: {@link #begin} ký ngay từng băm thành
 * CAdES-B-T (CMS SignedData tách rời, kèm dấu thời gian RFC 3161 trên chữ ký), {@link #poll} trả
 * {@link SignStatus#SIGNED}, {@link #collect} trả kết quả rồi xoá phiên. Khoá riêng không rời HSM.
 */
public class HsmPkcs11Provider implements SignatureProvider {

  public static final String ID = "hsm";

  private final Pkcs11Signer signer;
  private final String keyAlias;
  private final TimestampAuthority tsa;
  private final Clock clock;
  private final Map<String, List<SignedItem>> sessions = new ConcurrentHashMap<>();

  public HsmPkcs11Provider(
      Pkcs11Signer signer, String keyAlias, TimestampAuthority tsa, Clock clock) {
    this.signer = signer;
    this.keyAlias = keyAlias;
    this.tsa = tsa;
    this.clock = clock;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Set<SignatureFormat> supportedFormats() {
    return Set.of(SignatureFormat.CADES_B_T);
  }

  @Override
  public SignSession begin(SignRequest request) {
    List<X509Certificate> chain;
    try {
      chain = signer.chain(keyAlias);
    } catch (HsmUnavailableException e) {
      throw new SignatureProviderException("HSM chưa sẵn sàng", false, e);
    }
    X509Certificate cert = chain.get(0);
    Instant now = clock.instant();
    List<SignedItem> items = new ArrayList<>(request.documents().size());
    for (SignRequest.Document d : request.documents()) {
      if (d.format() != SignatureFormat.CADES_B_T) {
        throw new SignatureProviderException("Chữ ký tổ chức qua HSM chỉ hỗ trợ CAdES-B-T", true);
      }
      byte[] attrs = Cms.signedAttributes(d.sha256(), cert);
      byte[] signature;
      try {
        signature = signer.sign(keyAlias, Hashes.sha256(attrs), Pkcs11Signer.SHA256_WITH_ECDSA);
      } catch (HsmUnavailableException e) {
        throw new SignatureProviderException("HSM không ký được", false, e);
      }
      byte[] token = tsa == null ? null : tsa.timestampDigest(Hashes.sha256(signature));
      byte[] cms = Cms.signedData(attrs, signature, chain, token);
      items.add(new SignedItem(d.sha256(), cms, null, certificate(cert), now));
    }
    String id = UUID.randomUUID().toString();
    sessions.put(id, List.copyOf(items));
    return new SignSession(id, request.expiresAt());
  }

  @Override
  public SignStatus poll(String sessionId) {
    return sessions.containsKey(sessionId) ? SignStatus.SIGNED : SignStatus.EXPIRED;
  }

  @Override
  public List<SignedItem> collect(String sessionId) {
    List<SignedItem> items = sessions.remove(sessionId);
    if (items == null) {
      throw new SignatureProviderException("Phiên ký HSM không còn", true);
    }
    return items;
  }

  static SignedItem.Certificate certificate(X509Certificate c) {
    try {
      return new SignedItem.Certificate(
          c.getEncoded(),
          c.getSubjectX500Principal().getName(),
          c.getIssuerX500Principal().getName(),
          c.getSerialNumber().toString(16),
          c.getNotBefore().toInstant(),
          c.getNotAfter().toInstant(),
          null,
          null);
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException("Chứng thư HSM không mã hoá được", e);
    }
  }
}
