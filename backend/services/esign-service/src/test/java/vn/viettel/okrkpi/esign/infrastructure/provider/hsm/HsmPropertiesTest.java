package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.junit.jupiter.api.Test;

/** Cấu hình SunPKCS11 và chuyển chữ ký P1363 sang DER, không cần HSM (DV-10-03). */
class HsmPropertiesTest {

  @Test
  void macDinhVaKhuonThuocTinhKhoa() {
    HsmProperties p =
        new HsmProperties(true, "/lib/x.so", null, null, "bi-mat", null, null, 0, null, false);
    assertThat(p.orgKeyAlias()).isEqualTo("mission-org-sign");
    assertThat(p.sealKeyAlias()).isEqualTo("mission-seal");
    assertThat(p.maxSessions()).isEqualTo(8);
    assertThat(p.acquireTimeout()).isEqualTo(Duration.ofSeconds(2));
    String cfg = p.sunPkcs11Config();
    assertThat(cfg)
        .contains("library=/lib/x.so")
        .contains("slotListIndex=0")
        .contains("CKA_EXTRACTABLE=false")
        .contains("CKA_SENSITIVE=true")
        .doesNotContain("bi-mat");
    assertThat(p.toString()).doesNotContain("bi-mat");
    HsmProperties slot =
        new HsmProperties(
            true, "/lib/x.so", 7, null, null, "a", "b", 3, Duration.ofSeconds(1), true);
    assertThat(slot.sunPkcs11Config()).contains("slot=7").doesNotContain("slotListIndex");
    assertThatThrownBy(
            () ->
                new HsmProperties(true, " ", null, null, null, null, null, null, null, false)
                    .sunPkcs11Config())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void p1363SangDer() throws Exception {
    byte[] p1363 = new byte[64];
    p1363[31] = 5;
    p1363[32] = (byte) 0x80;
    ASN1Sequence seq = ASN1Sequence.getInstance(HsmPkcs11Signer.toDer(p1363));
    assertThat(((ASN1Integer) seq.getObjectAt(0)).getValue()).isEqualTo(BigInteger.valueOf(5));
    assertThat(((ASN1Integer) seq.getObjectAt(1)).getValue().signum()).isPositive();
    assertThatThrownBy(() -> HsmPkcs11Signer.toDer(new byte[3]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
