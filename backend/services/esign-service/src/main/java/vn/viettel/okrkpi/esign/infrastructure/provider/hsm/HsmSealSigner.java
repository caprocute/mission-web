package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import vn.viettel.okrkpi.esign.application.seal.SealSigner;
import vn.viettel.okrkpi.esign.application.sign.Hashes;

/** Ký niêm phong sổ cái bằng khoá niêm phong của cell trong HSM (ADR-17). */
public class HsmSealSigner implements SealSigner {

  private final Pkcs11Signer signer;
  private final String keyAlias;

  public HsmSealSigner(Pkcs11Signer signer, String keyAlias) {
    this.signer = signer;
    this.keyAlias = keyAlias;
  }

  @Override
  public Sealed sign(byte[] digest) {
    X509Certificate cert = signer.chain(keyAlias).get(0);
    byte[] signature = signer.sign(keyAlias, digest, Pkcs11Signer.SHA256_WITH_ECDSA);
    try {
      return new Sealed(
          signature, cert.getSerialNumber().toString(16), Hashes.sha256(cert.getEncoded()));
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException("Chứng thư niêm phong không mã hoá được", e);
    }
  }
}
