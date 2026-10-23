package vn.viettel.okrkpi.org.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.domain.e2ee.KeyMaterial;

/** CORE-07-06: kiểm SPKI theo thuật toán, vân tay và chuỗi chuẩn được ký của danh bạ khoá. */
class KeyMaterialTest {

  @Test
  void nhanDungKhoaP256VaRsa3072() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    byte[] p256 = ec.generateKeyPair().getPublic().getEncoded();
    assertThat(KeyMaterial.spki("ECDH-P256", Base64.getEncoder().encodeToString(p256), "f")).isEqualTo(p256);

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(3072);
    byte[] r3072 = rsa.generateKeyPair().getPublic().getEncoded();
    assertThat(KeyMaterial.spki("RSA-OAEP-3072", Base64.getEncoder().encodeToString(r3072), "f")).isEqualTo(r3072);
    assertThatThrownBy(() -> KeyMaterial.spki("ECDH-P256", Base64.getEncoder().encodeToString(r3072), "f"))
        .isInstanceOf(OrgRuleViolation.class);
    assertThatThrownBy(() -> KeyMaterial.spki("RSA-OAEP-3072", Base64.getEncoder().encodeToString(p256), "f"))
        .isInstanceOf(OrgRuleViolation.class);
  }

  @Test
  void tuChoiDuongCongKhacThuatToanLaVaDoDai() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp384r1"));
    String p384 = Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded());
    for (String[] c : List.of(new String[] {"ECDH-P256", p384}, new String[] {"X25519", p384},
        new String[] {"ECDH-P256", ""}, new String[] {"BCY", Base64.getEncoder().encodeToString(new byte[8])},
        new String[] {"BCY", Base64.getEncoder().encodeToString(new byte[KeyMaterial.MAX_SPKI_BYTES + 1])})) {
      assertThatThrownBy(() -> KeyMaterial.spki(c[0], c[1], "f")).isInstanceOf(OrgRuleViolation.class);
    }
    assertThat(KeyMaterial.spki("BCY", Base64.getEncoder().encodeToString(new byte[64]), "f")).hasSize(64);
  }

  @Test
  void chuoiChuanCoDinhVaLamTronMicroGiay() {
    UUID tenant = UUID.fromString("01960000-0000-7000-8000-000000000001");
    UUID id = UUID.fromString("01960000-0000-7000-8000-000000000002");
    byte[] fp = KeyMaterial.fingerprint(new byte[] {1, 2, 3});
    assertThat(HexFormat.of().formatHex(fp))
        .isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
    String s = KeyMaterial.canonical(tenant, "RECOVERY_KEY", id, null, "ECDH-P256", fp,
        Instant.parse("2026-10-23T01:02:03.123456789Z"), null);
    assertThat(s).isEqualTo("v1|" + tenant + "|RECOVERY_KEY|" + id + "||ECDH-P256|" + HexFormat.of().formatHex(fp)
        + "|2026-10-23T01:02:03.123456Z|");
    assertThatThrownBy(() -> KeyMaterial.hexFingerprint("ABC", "f")).isInstanceOf(OrgRuleViolation.class);
  }
}
