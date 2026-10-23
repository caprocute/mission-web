package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import vn.viettel.okrkpi.esign.application.seal.TimestampAuthority;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Client dấu thời gian RFC 3161 dùng chung (DV-10-03): PAdES-B-T/LTA của mọi nhà cung cấp và niêm
 * phong sổ cái. Mỗi yêu cầu có nonce ngẫu nhiên 64 bit và xin kèm chứng thư TSA; token trả về được
 * kiểm khớp băm và nonce, chữ ký của TSA (chứng thư có EKU {@code timeStamping}) và chuỗi chứng thư
 * tới neo tin cậy cấu hình. Nhiều TSA thử theo thứ tự ưu tiên, mỗi TSA một instance Resilience4j
 * riêng ({@code esign-tsa-1}, {@code esign-tsa-2}…), nên một TSA ngắt mạch không kéo TSA khác.
 */
public class TsaClient implements TimestampAuthority {

  private static final Logger log = LoggerFactory.getLogger(TsaClient.class);
  private static final MediaType QUERY = MediaType.parseMediaType("application/timestamp-query");
  private static final MediaType REPLY = MediaType.parseMediaType("application/timestamp-reply");

  /** Một TSA đã cấu hình. */
  public record Endpoint(String url, ResilientClient client) {
    public Endpoint {
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(client, "client");
    }
  }

  private final List<Endpoint> endpoints;
  private final Set<TrustAnchor> anchors;
  private final SecureRandom random = new SecureRandom();

  public TsaClient(List<Endpoint> endpoints, Collection<X509Certificate> trustAnchors) {
    if (endpoints.isEmpty()) {
      throw new IllegalArgumentException("Cần ít nhất một máy chủ dấu thời gian");
    }
    if (trustAnchors.isEmpty()) {
      throw new IllegalArgumentException("Cần neo tin cậy cho chứng thư máy chủ dấu thời gian");
    }
    this.endpoints = List.copyOf(endpoints);
    Set<TrustAnchor> set = new HashSet<>();
    trustAnchors.forEach(c -> set.add(new TrustAnchor(c, null)));
    this.anchors = Set.copyOf(set);
  }

  /** Token dấu thời gian (ContentInfo DER) trên SHA-256 của {@code data}. */
  public byte[] timestamp(byte[] data) {
    return timestampDigest(Hashes.sha256(data));
  }

  @Override
  public byte[] timestampDigest(byte[] sha256) {
    if (sha256 == null || sha256.length != 32) {
      throw new IllegalArgumentException("Message imprint SHA-256 phải dài 32 byte");
    }
    TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
    generator.setCertReq(true);
    TimeStampRequest request =
        generator.generate(TSPAlgorithms.SHA256, sha256, new BigInteger(64, random));
    RuntimeException last = null;
    for (Endpoint e : endpoints) {
      try {
        return call(e, request);
      } catch (RuntimeException ex) {
        last = ex;
        log.warn("Máy chủ dấu thời gian {} lỗi: {}", e.url(), ex.getMessage());
      }
    }
    throw new SignatureProviderException(
        "Không lấy được dấu thời gian từ máy chủ nào", false, last);
  }

  private byte[] call(Endpoint e, TimeStampRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(QUERY);
    headers.setAccept(List.of(REPLY));
    byte[] body;
    try {
      body = e.client().exchange(HttpMethod.POST, "", request.getEncoded(), headers, byte[].class);
    } catch (IOException ex) {
      throw new IllegalStateException("Không mã hoá được yêu cầu dấu thời gian", ex);
    }
    return verify(request, body);
  }

  /** Kiểm phản hồi; trả token DER. */
  byte[] verify(TimeStampRequest request, byte[] body) {
    try {
      if (body == null) {
        throw new SignatureProviderException("Máy chủ dấu thời gian trả rỗng", false);
      }
      TimeStampResponse response = new TimeStampResponse(body);
      // Khớp nonce, message imprint, thuật toán băm và trạng thái cấp.
      response.validate(request);
      TimeStampToken token = response.getTimeStampToken();
      if (token == null) {
        throw new SignatureProviderException(
            "Máy chủ dấu thời gian từ chối: " + response.getStatusString(), false);
      }
      X509Certificate signer = signerCertificate(token);
      // Chữ ký của TSA, EKU timeStamping duy nhất và critical, chứng thư còn hiệu lực lúc cấp.
      token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));
      checkChain(signer, token);
      return token.toCMSSignedData().toASN1Structure().getEncoded(ASN1Encoding.DER);
    } catch (IOException
        | TSPException
        | GeneralSecurityException
        | org.bouncycastle.operator.OperatorCreationException e) {
      throw new SignatureProviderException(
          "Dấu thời gian không hợp lệ: " + e.getMessage(), false, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static X509Certificate signerCertificate(TimeStampToken token)
      throws GeneralSecurityException {
    SignerId sid = token.getSID();
    Collection<X509CertificateHolder> matches =
        token.getCertificates().getMatches((Selector<X509CertificateHolder>) sid);
    if (matches.isEmpty()) {
      throw new SignatureProviderException("Token dấu thời gian thiếu chứng thư TSA", false);
    }
    return new JcaX509CertificateConverter().getCertificate(matches.iterator().next());
  }

  private void checkChain(X509Certificate signer, TimeStampToken token)
      throws GeneralSecurityException {
    for (TrustAnchor a : anchors) {
      if (a.getTrustedCert().equals(signer)) {
        return;
      }
    }
    List<X509Certificate> path = new ArrayList<>();
    path.add(signer);
    JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
    for (Object o : token.getCertificates().getMatches(null)) {
      X509Certificate c = converter.getCertificate((X509CertificateHolder) o);
      if (!c.equals(signer)) {
        path.add(c);
      }
    }
    PKIXParameters params = new PKIXParameters(anchors);
    // Thu hồi chứng thư TSA kiểm ở bộ kiểm OCSP/CRL dùng chung (DV-10-04), không ở đây.
    params.setRevocationEnabled(false);
    params.setDate(token.getTimeStampInfo().getGenTime());
    CertPathValidator.getInstance("PKIX")
        .validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params);
  }
}
