package vn.viettel.okrkpi.esign.infrastructure.provider.trust;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Kiểm chứng thư của một nhà cung cấp chữ ký (DV-10-04): chuỗi phải dẫn về một chứng thư gốc đã nạp
 * cho nhà cung cấp đó, rồi hỏi trạng thái thu hồi qua OCSP; OCSP không trả lời được thì đọc CRL.
 * Địa chỉ OCSP, CRL và chứng thư gốc nằm trong cấu hình của nhà cung cấp, không đọc từ phần mở rộng
 * AIA hay CRLDP của chứng thư (suy luận: tránh gọi tới địa chỉ do chứng thư tự khai).
 */
public final class CertificateTrust {

  /** Trạng thái thu hồi lưu ở {@code esign.certificate_ref.last_ocsp_status}. */
  public enum Status {
    GOOD,
    REVOKED,
    UNKNOWN
  }

  /** Kết quả kiểm tra tại {@code checkedAt}. */
  public record Result(Status status, Instant checkedAt) {}

  private static final Logger LOG = LoggerFactory.getLogger(CertificateTrust.class);
  private static final MediaType OCSP_REQUEST =
      MediaType.parseMediaType("application/ocsp-request");
  private static final MediaType OCSP_RESPONSE =
      MediaType.parseMediaType("application/ocsp-response");
  private static final Duration SKEW = Duration.ofMinutes(5);

  private final String providerId;
  private final Set<TrustAnchor> anchors;
  private final List<X509Certificate> roots;
  private final ResilientClient ocsp;
  private final ResilientClient crl;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param ocsp máy chủ OCSP của nhà cung cấp, {@code null} nếu không có
   * @param crl địa chỉ tải CRL của CA cấp, {@code null} nếu không có
   */
  public CertificateTrust(
      String providerId,
      List<X509Certificate> roots,
      ResilientClient ocsp,
      ResilientClient crl,
      Clock clock) {
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    if (roots == null || roots.isEmpty()) {
      throw new IllegalArgumentException("Nhà cung cấp " + providerId + " chưa nạp chứng thư gốc");
    }
    this.roots = List.copyOf(roots);
    Set<TrustAnchor> set = new HashSet<>();
    for (X509Certificate root : roots) {
      set.add(new TrustAnchor(root, null));
    }
    this.anchors = Set.copyOf(set);
    this.ocsp = ocsp;
    this.crl = crl;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public String providerId() {
    return providerId;
  }

  /** Chuỗi (chứng thư người ký đứng đầu) dẫn về một chứng thư gốc đã nạp và còn hiệu lực. */
  public boolean trusted(List<X509Certificate> chain) {
    if (chain == null || chain.isEmpty()) {
      return false;
    }
    try {
      List<X509Certificate> path = new ArrayList<>();
      for (X509Certificate c : chain) {
        if (!roots.contains(c)) {
          path.add(c);
        }
      }
      PKIXParameters params = new PKIXParameters(anchors);
      params.setRevocationEnabled(false);
      params.setDate(Date.from(clock.instant()));
      CertPathValidator.getInstance("PKIX")
          .validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params);
      return true;
    } catch (GeneralSecurityException e) {
      LOG.debug("Chuỗi chứng thư không dẫn về gốc của {}: {}", providerId, e.getMessage());
      return false;
    }
  }

  /**
   * Trạng thái thu hồi của chứng thư người ký. Gọi sau {@link #trusted}; CA cấp lấy từ chuỗi hoặc
   * từ chứng thư gốc đã nạp.
   */
  public Result check(List<X509Certificate> chain) {
    X509Certificate leaf = chain.get(0);
    X509Certificate issuer = issuerOf(leaf, chain);
    Instant now = clock.instant();
    if (issuer == null) {
      return new Result(Status.UNKNOWN, now);
    }
    if (ocsp != null) {
      try {
        Status status = askOcsp(leaf, issuer, now);
        if (status != Status.UNKNOWN || crl == null) {
          return new Result(status, now);
        }
      } catch (RuntimeException e) {
        LOG.warn("OCSP của {} không trả lời được: {}", providerId, e.getMessage());
      }
    }
    if (crl != null) {
      try {
        return new Result(readCrl(leaf, issuer, now), now);
      } catch (RuntimeException e) {
        LOG.warn("CRL của {} không đọc được: {}", providerId, e.getMessage());
      }
    }
    return new Result(Status.UNKNOWN, now);
  }

  private X509Certificate issuerOf(X509Certificate leaf, List<X509Certificate> chain) {
    List<X509Certificate> candidates = new ArrayList<>(chain.subList(1, chain.size()));
    candidates.addAll(roots);
    for (X509Certificate c : candidates) {
      if (c.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
        try {
          leaf.verify(c.getPublicKey());
          return c;
        } catch (GeneralSecurityException e) {
          // khác khoá, thử ứng viên tiếp
        }
      }
    }
    return null;
  }

  private Status askOcsp(X509Certificate leaf, X509Certificate issuer, Instant now) {
    try {
      CertificateID id =
          new CertificateID(
              new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1),
              new JcaX509CertificateHolder(issuer),
              leaf.getSerialNumber());
      byte[] nonce = new BigInteger(128, random).toByteArray();
      OCSPReq request =
          new OCSPReqBuilder()
              .addRequest(id)
              .setRequestExtensions(
                  new Extensions(
                      new Extension(
                          OCSPObjectIdentifiers.id_pkix_ocsp_nonce,
                          false,
                          new DEROctetString(nonce))))
              .build();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(OCSP_REQUEST);
      headers.setAccept(List.of(OCSP_RESPONSE));
      byte[] body = ocsp.exchange(HttpMethod.POST, "", request.getEncoded(), headers, byte[].class);
      OCSPResp response = new OCSPResp(body);
      if (response.getStatus() != OCSPResp.SUCCESSFUL) {
        return Status.UNKNOWN;
      }
      BasicOCSPResp basic = (BasicOCSPResp) response.getResponseObject();
      if (!signedByIssuerOrDelegate(basic, issuer)) {
        LOG.warn("Phản hồi OCSP của {} không do CA cấp hay bên được uỷ quyền ký", providerId);
        return Status.UNKNOWN;
      }
      for (SingleResp single : basic.getResponses()) {
        if (!single.getCertID().equals(id)) {
          continue;
        }
        if (single.getNextUpdate() != null
            && single.getNextUpdate().toInstant().isBefore(now.minus(SKEW))) {
          return Status.UNKNOWN;
        }
        CertificateStatus status = single.getCertStatus();
        if (status == CertificateStatus.GOOD) {
          return Status.GOOD;
        }
        return status instanceof RevokedStatus ? Status.REVOKED : Status.UNKNOWN;
      }
      return Status.UNKNOWN;
    } catch (java.io.IOException | org.bouncycastle.operator.OperatorCreationException e) {
      throw new IllegalStateException("Không dựng hoặc đọc được thông điệp OCSP", e);
    } catch (org.bouncycastle.cert.ocsp.OCSPException
        | java.security.cert.CertificateEncodingException e) {
      throw new IllegalStateException("Phản hồi OCSP không hợp lệ", e);
    }
  }

  private boolean signedByIssuerOrDelegate(BasicOCSPResp basic, X509Certificate issuer) {
    try {
      if (basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(issuer))) {
        return true;
      }
    } catch (Exception e) {
      // không phải CA cấp ký, xét bên được uỷ quyền
    }
    for (X509CertificateHolder holder : basic.getCerts()) {
      try {
        X509Certificate responder = new JcaX509CertificateConverter().getCertificate(holder);
        responder.verify(issuer.getPublicKey());
        responder.checkValidity();
        List<String> usage = responder.getExtendedKeyUsage();
        if (usage != null
            && usage.contains(KeyPurposeId.id_kp_OCSPSigning.getId())
            && basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(responder))) {
          return true;
        }
      } catch (Exception e) {
        // bỏ qua chứng thư không hợp lệ
      }
    }
    return false;
  }

  private Status readCrl(X509Certificate leaf, X509Certificate issuer, Instant now) {
    try {
      byte[] body = crl.get("", byte[].class);
      X509CRL list =
          (X509CRL)
              CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(body));
      list.verify(issuer.getPublicKey());
      if (list.getNextUpdate() != null
          && list.getNextUpdate().toInstant().isBefore(now.minus(SKEW))) {
        return Status.UNKNOWN;
      }
      return list.isRevoked(leaf) ? Status.REVOKED : Status.GOOD;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("CRL không hợp lệ: " + e.getMessage(), e);
    }
  }
}
