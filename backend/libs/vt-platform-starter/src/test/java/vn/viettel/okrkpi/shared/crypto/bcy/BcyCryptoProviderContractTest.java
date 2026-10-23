package vn.viettel.okrkpi.shared.crypto.bcy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;
import vn.viettel.okrkpi.shared.crypto.CryptoProviderContract;

/**
 * Bộ ca hợp đồng {@link CryptoProviderContract} ở chế độ {@code bcy} với thư viện Ban Cơ yếu giả
 * lập, nạp qua {@link BcyLibraryLoader} đúng như cấu hình triển khai; thêm ca ký và kiểm chữ ký.
 */
class BcyCryptoProviderContractTest extends CryptoProviderContract {

  static BcyCryptoProperties properties() {
    return new BcyCryptoProperties(
        List.of(),
        GiaLapBcyCryptoLibrary.class.getName(),
        Map.of("slot", "0", "key-labels", "ky-van-ban"),
        Set.copyOf(GiaLapBcyCryptoLibrary.ALGORITHMS.values()));
  }

  @Override
  protected CryptoProvider create() {
    return new BcyCryptoProvider(BcyLibraryLoader.load(properties()));
  }

  @Test
  void usesOwnCiphertextVersion() {
    assertThat(provider.algorithmVersion()).isEqualTo(BcyCryptoProvider.VERSION_BCY);
  }

  @Test
  void signsAndVerifiesWithTimestamp() {
    BcyCryptoProvider bcy = (BcyCryptoProvider) provider;
    byte[] digest = bcy.digest("quyết định".getBytes(StandardCharsets.UTF_8));
    BcyCryptoLibrary.Signature sig = bcy.sign("ky-van-ban", digest);
    assertThat(sig.format()).isEqualTo("CADES_B_T");
    assertThat(sig.timestampToken()).isNotEmpty();
    assertThat(bcy.verify(sig, digest)).isTrue();
    assertThat(bcy.verify(sig, bcy.digest(new byte[] {1}))).isFalse();
    BcyCryptoLibrary.Signature moved =
        new BcyCryptoLibrary.Signature(
            sig.value(),
            sig.format(),
            sig.timestampToken(),
            sig.certificate(),
            sig.signedAt().plusSeconds(60));
    assertThat(bcy.verify(moved, digest)).isFalse();
    assertThatThrownBy(() -> bcy.sign("khong-co", digest)).isInstanceOf(CryptoException.class);
  }

  @Test
  void closedLibraryRefusesWork() {
    BcyCryptoProvider bcy = (BcyCryptoProvider) provider;
    bcy.close();
    assertThatThrownBy(() -> bcy.hmacSha256(new byte[32], new byte[1]))
        .isInstanceOf(CryptoException.class);
  }
}
