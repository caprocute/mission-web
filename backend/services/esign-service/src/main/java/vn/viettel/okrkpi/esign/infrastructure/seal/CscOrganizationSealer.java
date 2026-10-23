package vn.viettel.okrkpi.esign.infrastructure.seal;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealer;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.Cms;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.CscClient;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/**
 * Dấu số tổ chức qua MySign (CSC API v2, chế độ đồng bộ) bằng credential tổ chức của cell, khoá nằm
 * ở HSM của nhà cung cấp. Thuật toán ký theo loại khoá của chứng thư (RSA hoặc EC, SHA-256).
 */
public class CscOrganizationSealer implements OrganizationSealer {

  private final CscClient csc;
  private final String credentialId;

  public CscOrganizationSealer(CscClient csc, String credentialId) {
    this.csc = Objects.requireNonNull(csc, "csc");
    if (credentialId == null || credentialId.isBlank()) {
      throw new IllegalStateException("Thiếu credential tổ chức ở MySign");
    }
    this.credentialId = credentialId;
  }

  @Override
  public Seal seal(UUID tenantId, byte[] sha256) {
    X509Certificate cert = certificate();
    byte[] signature = csc.signHashNow(credentialId, List.of(sha256), Cms.signAlgo(cert)).get(0);
    String algorithm =
        "RSA".equals(cert.getPublicKey().getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
    return new Seal(signature, algorithm, cert.getPublicKey().getEncoded());
  }

  private X509Certificate certificate() {
    CscClient.CredentialInfo info = csc.info(credentialId);
    if (info.cert().certificates().isEmpty()) {
      throw new SignatureProviderException("MySign không trả chứng thư tổ chức", true);
    }
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(
                  new ByteArrayInputStream(
                      Base64.getDecoder().decode(info.cert().certificates().get(0))));
    } catch (CertificateException | IllegalArgumentException e) {
      throw new SignatureProviderException("Chứng thư tổ chức không đọc được", false, e);
    }
  }
}
