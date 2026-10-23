package vn.viettel.okrkpi.task.domain.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CORE-02-24: so sánh theo nội dung mảng, không in khoá đã bọc, cấp lại xoá dấu thu hồi. */
class KeyWrapTest {

  private static final UUID NGUOI = UUID.fromString("01960000-0000-7000-8000-000000000001");
  private static final UUID CAP = UUID.fromString("01960000-0000-7000-8000-000000000002");
  private static final Instant LUC = Instant.parse("2026-10-23T01:00:00Z");

  private static KeyWrap wrap() {
    return new KeyWrap(
        NGUOI, new byte[] {1, 2}, "ECDH-ES+A256KW", new byte[] {3}, new byte[] {4}, CAP, LUC, null);
  }

  @Test
  void bangNhauTheoNoiDungVaKhacKhiMotTruongKhac() {
    KeyWrap a = wrap();
    assertThat(a).isEqualTo(wrap()).hasSameHashCodeAs(wrap()).isNotEqualTo("khac");
    UUID khac = UUID.randomUUID();
    assertThat(a)
        .isNotEqualTo(
            new KeyWrap(
                khac,
                new byte[] {1, 2},
                a.algorithm(),
                new byte[] {3},
                new byte[] {4},
                CAP,
                LUC,
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {9},
                a.algorithm(),
                new byte[] {3},
                new byte[] {4},
                CAP,
                LUC,
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {1, 2},
                "RSA-OAEP-256",
                new byte[] {3},
                new byte[] {4},
                CAP,
                LUC,
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI, new byte[] {1, 2}, a.algorithm(), null, new byte[] {4}, CAP, LUC, null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {1, 2},
                a.algorithm(),
                new byte[] {3},
                new byte[] {5},
                CAP,
                LUC,
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {1, 2},
                a.algorithm(),
                new byte[] {3},
                new byte[] {4},
                khac,
                LUC,
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {1, 2},
                a.algorithm(),
                new byte[] {3},
                new byte[] {4},
                CAP,
                LUC.plusSeconds(1),
                null))
        .isNotEqualTo(
            new KeyWrap(
                NGUOI,
                new byte[] {1, 2},
                a.algorithm(),
                new byte[] {3},
                new byte[] {4},
                CAP,
                LUC,
                LUC));
  }

  @Test
  void capLaiXoaThuHoiVaKhongInKhoa() {
    KeyWrap revoked =
        new KeyWrap(
            NGUOI, new byte[] {1, 2}, "ECDH-ES+A256KW", null, new byte[] {4}, CAP, LUC, LUC);
    assertThat(revoked.active()).isFalse();
    KeyWrap again = revoked.grantedBy(NGUOI);
    assertThat(again.active()).isTrue();
    assertThat(again.grantedBy()).isEqualTo(NGUOI);
    assertThat(again.deviceId()).isEqualTo("0102");
    assertThat(again.toString()).contains("0102").doesNotContain("[4]", "wrappedKey");
  }
}
