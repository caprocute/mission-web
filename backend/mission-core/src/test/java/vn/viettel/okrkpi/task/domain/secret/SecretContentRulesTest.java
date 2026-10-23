package vn.viettel.okrkpi.task.domain.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.RecoveryKey;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Violation;

/** Kiểm dạng bản mã và khoá đã bọc C3 (CORE-07-06). */
class SecretContentRulesTest {

  static final UUID PERSON = UUID.fromString("01927a02-0000-7000-8000-000000000706");
  static final UUID RECOVERY = UUID.fromString("01927a02-0000-7000-8000-000000000707");
  static final byte[] CIPHER = "ban-ma".getBytes(StandardCharsets.UTF_8);

  static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }

  static String device(String label) {
    return hex(SecretContentRules.sha256(label.getBytes(StandardCharsets.UTF_8)));
  }

  static KeyWrap wrap(UUID who, String device) {
    return SecretContentRules.wrap(0, who, device, "RSA-OAEP-256", null, b64(new byte[] {1}));
  }

  @Test
  void banMaHopLeVaBamKhop() {
    var s =
        SecretContentRules.seal(
            b64(CIPHER), b64(new byte[12]), device("aad"), hex(SecretContentRules.sha256(CIPHER)));
    assertThat(s.ciphertext()).isEqualTo(CIPHER);
    assertThat(s.sha256()).hasSize(32);
  }

  @Test
  void nonceSaiDoDaiBamLechVaHexHoaBiChan() {
    String sha = hex(SecretContentRules.sha256(CIPHER));
    assertThatThrownBy(() -> SecretContentRules.seal(b64(CIPHER), b64(new byte[16]), sha, sha))
        .isInstanceOfSatisfying(Violation.class, v -> assertThat(v.field()).isEqualTo("nonce"));
    assertThatThrownBy(
            () -> SecretContentRules.seal(b64(CIPHER), b64(new byte[12]), sha, device("khac")))
        .isInstanceOfSatisfying(Violation.class, v -> assertThat(v.field()).isEqualTo("sha256"));
    assertThatThrownBy(
            () -> SecretContentRules.seal(b64(CIPHER), b64(new byte[12]), sha.toUpperCase(), sha))
        .isInstanceOfSatisfying(Violation.class, v -> assertThat(v.field()).isEqualTo("aadHash"));
    assertThatThrownBy(() -> SecretContentRules.seal("%%", b64(new byte[12]), sha, sha))
        .isInstanceOfSatisfying(
            Violation.class, v -> assertThat(v.field()).isEqualTo("ciphertext"));
  }

  @Test
  void ecdhCanKhoaTamRsaKhongDungKhoaTam() {
    assertThatThrownBy(
            () ->
                SecretContentRules.wrap(
                    2, PERSON, device("a"), "ECDH-ES+A256KW", null, b64(new byte[] {1})))
        .isInstanceOfSatisfying(
            Violation.class,
            v -> assertThat(v.field()).isEqualTo("keyWraps[2].ephemeralPublicKey"));
    assertThatThrownBy(
            () ->
                SecretContentRules.wrap(
                    0,
                    PERSON,
                    device("a"),
                    "RSA-OAEP-256",
                    b64(new byte[] {9}),
                    b64(new byte[] {1})))
        .isInstanceOf(Violation.class);
    assertThatThrownBy(
            () ->
                SecretContentRules.wrap(
                    0, PERSON, device("a"), "A128KW", null, b64(new byte[] {1})))
        .isInstanceOf(Violation.class);
    KeyWrap ok =
        SecretContentRules.wrap(
            0, PERSON, device("a"), null, b64(new byte[] {9}), b64(new byte[] {1}));
    assertThat(ok.algorithm()).isEqualTo("ECDH-ES+A256KW");
    assertThat(ok.deviceId()).isEqualTo(device("a"));
  }

  @Test
  void moiKhoaCongKhaiMotBanBoc() {
    assertThatThrownBy(
            () ->
                SecretContentRules.distinct(
                    List.of(wrap(PERSON, device("a")), wrap(RECOVERY, device("a")))))
        .isInstanceOfSatisfying(
            Violation.class, v -> assertThat(v.field()).isEqualTo("keyWraps[1].deviceId"));
    assertThatThrownBy(() -> SecretContentRules.distinct(List.of())).isInstanceOf(Violation.class);
  }

  @Test
  void luonCanBanBocChoKhoaPhucHoi() {
    RecoveryKey key = new RecoveryKey(Set.of(device("phuc-hoi")), RECOVERY);
    assertThatThrownBy(
            () -> SecretContentRules.requireRecovery(List.of(wrap(PERSON, device("a"))), key))
        .isInstanceOf(Violation.class);
    assertThatThrownBy(
            () -> SecretContentRules.requireRecovery(List.of(wrap(PERSON, device("a"))), null))
        .hasMessageContaining("chưa khai báo");
    SecretContentRules.requireRecovery(
        List.of(wrap(PERSON, device("a")), wrap(RECOVERY, device("phuc-hoi"))), key);
    assertThatThrownBy(
            () ->
                SecretContentRules.requireRecoveryRecipient(
                    List.of(wrap(PERSON, device("phuc-hoi"))), key))
        .isInstanceOfSatisfying(
            Violation.class, v -> assertThat(v.field()).isEqualTo("keyWraps[0].recipientPersonId"));
  }

  @Test
  void khongInKhoaRaNhatKy() {
    assertThat(wrap(PERSON, device("a")).toString()).doesNotContain("wrappedKey");
  }
}
