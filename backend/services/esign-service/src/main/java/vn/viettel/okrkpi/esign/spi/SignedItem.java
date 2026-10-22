package vn.viettel.okrkpi.esign.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Chữ ký nhận về cho một tài liệu, kèm chứng thư công khai đã dùng và kết quả kiểm tra thu hồi tại
 * thời điểm ký (để kiểm chứng dài hạn).
 */
public record SignedItem(
    byte[] documentSha256,
    byte[] signatureValue,
    String signedObjectKey,
    Certificate certificate,
    Instant signedAt) {

  public SignedItem {
    Objects.requireNonNull(documentSha256, "documentSha256");
    Objects.requireNonNull(signatureValue, "signatureValue");
    Objects.requireNonNull(certificate, "certificate");
    Objects.requireNonNull(signedAt, "signedAt");
    documentSha256 = documentSha256.clone();
    signatureValue = signatureValue.clone();
  }

  @Override
  public byte[] documentSha256() {
    return documentSha256.clone();
  }

  @Override
  public byte[] signatureValue() {
    return signatureValue.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SignedItem s && java.util.Arrays.equals(documentSha256, s.documentSha256);
  }

  @Override
  public int hashCode() {
    return java.util.Arrays.hashCode(documentSha256);
  }

  @Override
  public String toString() {
    return "SignedItem[" + signedObjectKey + "]";
  }

  /** Phần công khai của chứng thư người ký. */
  public record Certificate(
      byte[] der,
      String subjectDn,
      String issuerDn,
      String serialNo,
      Instant notBefore,
      Instant notAfter,
      String ocspStatus,
      Instant ocspAt) {

    public Certificate {
      Objects.requireNonNull(der, "der");
      der = der.clone();
    }

    @Override
    public byte[] der() {
      return der.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Certificate c && java.util.Arrays.equals(der, c.der);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(der);
    }

    @Override
    public String toString() {
      return "Certificate[" + subjectDn + ", " + serialNo + "]";
    }
  }
}
