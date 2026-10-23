package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.operator.ContentSigner;

/** {@link ContentSigner} SHA256withECDSA chạy trong HSM, dùng khi tự ký chứng thư dev. */
final class HsmContentSigner implements ContentSigner {

  private final Provider provider;
  private final PrivateKey key;
  private final ByteArrayOutputStream data = new ByteArrayOutputStream();

  HsmContentSigner(Provider provider, PrivateKey key) {
    this.provider = provider;
    this.key = key;
  }

  @Override
  public AlgorithmIdentifier getAlgorithmIdentifier() {
    return new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
  }

  @Override
  public OutputStream getOutputStream() {
    return data;
  }

  @Override
  public byte[] getSignature() {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA", provider);
      s.initSign(key);
      s.update(data.toByteArray());
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new HsmUnavailableException("HSM không ký được chứng thư", e);
    }
  }
}
