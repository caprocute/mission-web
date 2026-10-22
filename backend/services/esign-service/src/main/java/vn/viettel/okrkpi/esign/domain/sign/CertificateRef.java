package vn.viettel.okrkpi.esign.domain.sign;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Chứng thư đã đăng ký hoặc đã dùng để ký ({@code esign.certificate_ref}); chỉ phần công khai,
 * không bao giờ có khoá riêng.
 */
public record CertificateRef(
    UUID id,
    UUID tenantId,
    UUID personId,
    byte[] certSha256,
    String subjectDn,
    String issuerDn,
    String serialNo,
    Instant notBefore,
    Instant notAfter,
    byte[] certDer,
    String lastOcspStatus,
    Instant lastOcspAt) {

  public static final String OCSP_REVOKED = "REVOKED";

  public CertificateRef {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(certSha256, "certSha256");
    Objects.requireNonNull(notBefore, "notBefore");
    Objects.requireNonNull(notAfter, "notAfter");
    Objects.requireNonNull(certDer, "certDer");
    if (certSha256.length != 32) {
      throw new IllegalArgumentException("Dấu vân tay chứng thư phải là SHA-256");
    }
    certSha256 = certSha256.clone();
    certDer = certDer.clone();
  }

  /** Còn dùng được để ký tại {@code now}: trong hiệu lực và chưa bị thu hồi theo OCSP gần nhất. */
  public boolean usableAt(Instant now) {
    return !now.isBefore(notBefore)
        && now.isBefore(notAfter)
        && !OCSP_REVOKED.equals(lastOcspStatus);
  }

  @Override
  public byte[] certSha256() {
    return certSha256.clone();
  }

  @Override
  public byte[] certDer() {
    return certDer.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CertificateRef other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "CertificateRef[" + id + ", " + subjectDn + "]";
  }
}
