package vn.viettel.okrkpi.esign.application.seal;

import java.util.Objects;

/** Ký băm bằng khoá niêm phong của cell trong HSM (cổng ra của {@link LedgerSealService}). */
public interface SealSigner {

  /** Chữ ký ECDSA P-256 DER trên băm SHA-256 {@code digest}. */
  Sealed sign(byte[] digest);

  /**
   * Chữ ký niêm phong.
   *
   * @param signature chữ ký DER
   * @param certificateSerial số hiệu chứng thư khoá niêm phong (hex)
   * @param certificateSha256 dấu vân tay chứng thư ({@code signer_cert_sha256})
   */
  record Sealed(byte[] signature, String certificateSerial, byte[] certificateSha256) {

    public Sealed {
      Objects.requireNonNull(signature, "signature");
      Objects.requireNonNull(certificateSerial, "certificateSerial");
      Objects.requireNonNull(certificateSha256, "certificateSha256");
      signature = signature.clone();
      certificateSha256 = certificateSha256.clone();
    }

    @Override
    public byte[] signature() {
      return signature.clone();
    }

    @Override
    public byte[] certificateSha256() {
      return certificateSha256.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Sealed s && java.util.Arrays.equals(signature, s.signature);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
      return "Sealed[" + certificateSerial + "]";
    }
  }
}
