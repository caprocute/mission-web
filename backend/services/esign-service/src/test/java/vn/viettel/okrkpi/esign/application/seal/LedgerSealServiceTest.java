package vn.viettel.okrkpi.esign.application.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Quy tắc của dịch vụ niêm phong sổ cái, không cần HSM (DV-10-03). */
class LedgerSealServiceTest {

  static final String ROOT = "ab".repeat(32);
  static final Instant NOW = Instant.parse("2026-10-23T17:00:00Z");

  final AtomicReference<byte[]> signed = new AtomicReference<>();
  final AtomicReference<byte[]> stamped = new AtomicReference<>();
  final SealSigner signer =
      digest -> {
        signed.set(digest);
        return new SealSigner.Sealed(new byte[] {1, 2}, "0a", new byte[32]);
      };
  final TimestampAuthority tsa =
      sha -> {
        stamped.set(sha);
        return new byte[] {3};
      };
  final LedgerSealService service =
      new LedgerSealService(
          signer, tsa, Set.of("ledger-service"), Clock.fixed(NOW, ZoneOffset.UTC));

  LedgerSealService.Command command(String client, String root) {
    return new LedgerSealService.Command(client, LocalDate.of(2026, 10, 23), root, null, 42);
  }

  @Test
  void kySha256CuaGocVaDongDauThoiGianTrenChinhGoc() {
    LedgerSealService.Result r = service.seal(command("ledger-service", ROOT));
    byte[] root = HexFormat.of().parseHex(ROOT);
    assertThat(signed.get()).isEqualTo(Hashes.sha256(root));
    assertThat(stamped.get()).isEqualTo(root);
    assertThat(r.signature()).containsExactly(1, 2);
    assertThat(r.timestampToken()).containsExactly(3);
    assertThat(r.certificateSerial()).isEqualTo("0a");
    assertThat(r.certificateSha256()).hasSize(32);
    assertThat(r.signedAt()).isEqualTo(NOW);
    assertThat(r.sealDate()).isEqualTo(LocalDate.of(2026, 10, 23));
    assertThat(r.merkleRoot()).isEqualTo(ROOT);
  }

  @Test
  void tuChoiBenGoiKhongPhaiSoCai() {
    for (String client : new String[] {"okr-core", null}) {
      assertThatThrownBy(() -> service.seal(command(client, ROOT)))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              e ->
                  assertThat(((BusinessException) e).errorCode().code())
                      .isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    }
    assertThat(signed.get()).isNull();
  }

  @Test
  void gocSaiDangBiTuChoi() {
    for (String root : new String[] {null, "AB".repeat(32), "ab", "zz".repeat(32)}) {
      assertThatThrownBy(() -> service.seal(command("ledger-service", root)))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              e ->
                  assertThat(((BusinessException) e).errorCode().code())
                      .isEqualTo("COMMON-422-VALIDATION"));
    }
  }

  @Test
  void sealedSaoChepMangVaKhongInChuKy() {
    byte[] sig = {9};
    SealSigner.Sealed s = new SealSigner.Sealed(sig, "01", new byte[] {7});
    sig[0] = 0;
    assertThat(s.signature()).containsExactly(9);
    s.signature()[0] = 5;
    assertThat(s.signature()).containsExactly(9);
    assertThat(s.certificateSha256()).containsExactly(7);
    assertThat(s).isEqualTo(new SealSigner.Sealed(new byte[] {9}, "01", new byte[] {7}));
    assertThat(s).isNotEqualTo(new SealSigner.Sealed(new byte[] {8}, "01", new byte[] {7}));
    assertThat(s.hashCode())
        .isEqualTo(new SealSigner.Sealed(new byte[] {9}, "01", new byte[] {7}).hashCode());
    assertThat(s.toString()).contains("01");
    assertThatThrownBy(() -> new SealSigner.Sealed(null, "01", new byte[1]))
        .isInstanceOf(NullPointerException.class);
  }
}
