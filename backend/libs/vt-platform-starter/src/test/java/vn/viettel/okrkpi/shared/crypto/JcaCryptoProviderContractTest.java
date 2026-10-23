package vn.viettel.okrkpi.shared.crypto;

/** Bộ ca hợp đồng {@link CryptoProviderContract} với hiện thực tiêu chuẩn ({@code standard}). */
class JcaCryptoProviderContractTest extends CryptoProviderContract {

  @Override
  protected CryptoProvider create() {
    return new JcaAesGcmCryptoProvider();
  }
}
