package vn.viettel.okrkpi.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bộ ca hợp đồng của {@link CryptoProvider} (DV-10-05): một bộ ca, mỗi hiện thực một lớp con. Mọi
 * hiện thực mới (JCA, Ban Cơ yếu, HSM) phải qua đủ các ca này để {@link FieldEncryptor}, {@link
 * DekService}, {@link BlindIndexer} dùng được mà không đổi mã.
 */
public abstract class CryptoProviderContract {

  private static final byte[] AAD = "tenant|bang|cot|dong".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PLAIN =
      "Nhận xét của cấp uỷ: hoàn thành tốt nhiệm vụ".getBytes(StandardCharsets.UTF_8);

  protected CryptoProvider provider;

  /** Tạo hiện thực cần kiểm. */
  protected abstract CryptoProvider create();

  @BeforeEach
  void setUp() {
    provider = create();
  }

  @Test
  void keysAndNoncesHaveFixedLengthsAndAreRandom() {
    byte[] k1 = provider.generateKey();
    byte[] k2 = provider.generateKey();
    assertThat(k1).hasSize(CryptoProvider.KEY_LENGTH_BYTES);
    assertThat(k1).isNotEqualTo(k2);
    byte[] n1 = provider.generateNonce();
    assertThat(n1).hasSize(CryptoProvider.NONCE_LENGTH_BYTES);
    assertThat(n1).isNotEqualTo(provider.generateNonce());
  }

  @Test
  void encryptThenDecryptRoundTripsWithTagAppended() {
    byte[] key = provider.generateKey();
    byte[] nonce = provider.generateNonce();
    byte[] ct = provider.encrypt(key, nonce, AAD, PLAIN);
    assertThat(ct).hasSize(PLAIN.length + CryptoProvider.TAG_LENGTH_BYTES);
    assertThat(Arrays.copyOf(ct, PLAIN.length)).isNotEqualTo(PLAIN);
    assertThat(provider.decrypt(key, nonce, AAD, ct)).isEqualTo(PLAIN);
    byte[] nonce2 = provider.generateNonce();
    assertThat(provider.decrypt(key, nonce2, AAD, provider.encrypt(key, nonce2, AAD, new byte[0])))
        .isEmpty();
  }

  @Test
  void tamperedCiphertextWrongAadOrWrongKeyIsRejected() {
    byte[] key = provider.generateKey();
    byte[] nonce = provider.generateNonce();
    byte[] ct = provider.encrypt(key, nonce, AAD, PLAIN);
    byte[] tampered = ct.clone();
    tampered[0] ^= 0x01;
    assertThatThrownBy(() -> provider.decrypt(key, nonce, AAD, tampered))
        .isInstanceOf(CryptoException.class);
    byte[] otherAad = "tenant|bang|cot|khac".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> provider.decrypt(key, nonce, otherAad, ct))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> provider.decrypt(provider.generateKey(), nonce, AAD, ct))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> provider.decrypt(key, nonce, AAD, new byte[5]))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  void invalidInputsAreRejected() {
    byte[] key = provider.generateKey();
    byte[] nonce = provider.generateNonce();
    assertThatThrownBy(() -> provider.encrypt(key, nonce, new byte[0], PLAIN))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> provider.encrypt(new byte[16], nonce, AAD, PLAIN))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> provider.encrypt(key, new byte[8], AAD, PLAIN))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> provider.encrypt(key, nonce, null, PLAIN))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void macIsDeterministic32BytesAndKeyDependent() {
    byte[] key = provider.generateKey();
    byte[] data = "chỉ mục mù".getBytes(StandardCharsets.UTF_8);
    byte[] mac = provider.hmacSha256(key, data);
    assertThat(mac).hasSize(32).isEqualTo(provider.hmacSha256(key, data));
    assertThat(provider.hmacSha256(provider.generateKey(), data)).isNotEqualTo(mac);
    assertThat(provider.hmacSha256(key, "khác".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(mac);
    assertThatThrownBy(() -> provider.hmacSha256(new byte[8], data))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  void algorithmVersionIsStable() {
    assertThat(provider.algorithmVersion()).isEqualTo(create().algorithmVersion());
  }
}
