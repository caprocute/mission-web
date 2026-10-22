package vn.viettel.okrkpi.config.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** CORE-08-11: ký ES256 dạng P1363 bằng khoá nền tảng. */
class KeyPairUiPackSignerTest {

  private static final byte[] DATA = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

  @Test
  void configuredKeySignsP1363AndVerifies() throws Exception {
    KeyPair pair = KeyPairUiPackSigner.generate();
    KeyPairUiPackSigner signer =
        KeyPairUiPackSigner.of(
            "k1",
            Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    byte[] sig = signer.sign(DATA);
    assertThat(signer.keyId()).isEqualTo("k1");
    assertThat(sig).hasSize(64);
    assertThat(KeyPairUiPackSigner.verify(signer.publicKey(), DATA, sig)).isTrue();
    assertThat(KeyPairUiPackSigner.verify(signer.publicKey(), "x".getBytes(), sig)).isFalse();
  }

  @Test
  void missingKeyFallsBackToEphemeralAndMismatchFails() throws Exception {
    KeyPairUiPackSigner temp = KeyPairUiPackSigner.of("k1", "", "");
    assertThat(temp.keyId()).isEqualTo("k1-tam");
    assertThat(KeyPairUiPackSigner.verify(temp.publicKey(), DATA, temp.sign(DATA))).isTrue();
    KeyPair a = KeyPairUiPackSigner.generate();
    KeyPair b = KeyPairUiPackSigner.generate();
    assertThatThrownBy(
            () ->
                KeyPairUiPackSigner.of(
                    "k1",
                    Base64.getEncoder().encodeToString(a.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(b.getPublic().getEncoded())))
        .isInstanceOf(IllegalStateException.class);
  }
}
