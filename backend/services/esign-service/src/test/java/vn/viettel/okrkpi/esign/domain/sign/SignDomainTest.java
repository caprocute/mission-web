package vn.viettel.okrkpi.esign.domain.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bất biến của chứng thư, tài liệu ký và trạng thái yêu cầu ký (DV-10-01). */
class SignDomainTest {

  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

  private static CertificateRef cert(Instant from, Instant to, String ocsp) {
    return new CertificateRef(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new byte[32],
        "CN=A",
        "CN=CA",
        "01",
        from,
        to,
        new byte[] {1},
        ocsp,
        null);
  }

  @Test
  void certificateIsUsableOnlyInsideValidityAndWhenNotRevoked() {
    Instant from = NOW.minus(Duration.ofDays(1));
    Instant to = NOW.plus(Duration.ofDays(1));
    assertThat(cert(from, to, null).usableAt(NOW)).isTrue();
    assertThat(cert(from, to, "GOOD").usableAt(NOW)).isTrue();
    assertThat(cert(from, to, CertificateRef.OCSP_REVOKED).usableAt(NOW)).isFalse();
    assertThat(cert(NOW.plusSeconds(1), to, null).usableAt(NOW)).isFalse();
    assertThat(cert(from, NOW, null).usableAt(NOW)).isFalse();
  }

  @Test
  void certificateKeepsDefensiveCopiesAndIdentityById() {
    CertificateRef a = cert(NOW, NOW.plusSeconds(60), null);
    a.certSha256()[0] = 9;
    a.certDer()[0] = 9;
    assertThat(a.certSha256()[0]).isZero();
    assertThat(a.certDer()[0]).isEqualTo((byte) 1);
    assertThat(a).isEqualTo(a).isNotEqualTo(cert(NOW, NOW.plusSeconds(60), null)).isNotEqualTo("x");
    assertThat(a.hashCode()).isEqualTo(a.id().hashCode());
    assertThat(a.toString()).contains("CN=A");
    assertThatThrownBy(
            () ->
                new CertificateRef(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new byte[31],
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    new byte[0],
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void itemMatchesItsHashAndRecordsSignature() {
    byte[] sha = new byte[32];
    sha[0] = 7;
    SignItem item =
        SignItem.pending(UUID.randomUUID(), "PDF_A", "k", sha, SignatureFormat.PADES_LTA);
    assertThat(item.signed()).isFalse();
    assertThat(item.matches(sha.clone())).isTrue();
    assertThat(item.matches(new byte[32])).isFalse();
    UUID certId = UUID.randomUUID();
    item.sign(new byte[] {1, 2}, "obj", certId, NOW);
    assertThat(item.signed()).isTrue();
    assertThat(item.certificateRefId()).isEqualTo(certId);
    assertThat(item.signedObjectKey()).isEqualTo("obj");
    assertThat(item.signedAt()).isEqualTo(NOW);
    assertThatThrownBy(
            () ->
                SignItem.pending(
                    UUID.randomUUID(), "PDF_A", "k", new byte[16], SignatureFormat.PADES_LTA))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalStatusesAreTheFourEndStates() {
    EnumSet<SignRequestStatus> terminal = EnumSet.noneOf(SignRequestStatus.class);
    for (SignRequestStatus s : SignRequestStatus.values()) {
      if (s.isTerminal()) {
        terminal.add(s);
      }
    }
    assertThat(terminal)
        .containsExactlyInAnyOrder(
            SignRequestStatus.DA_KY,
            SignRequestStatus.TU_CHOI,
            SignRequestStatus.HET_HAN,
            SignRequestStatus.LOI);
  }
}
