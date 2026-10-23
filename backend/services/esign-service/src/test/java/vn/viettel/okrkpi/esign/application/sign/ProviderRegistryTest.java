package vn.viettel.okrkpi.esign.application.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.infrastructure.sign.FakeSignatureProvider;

/**
 * Nhà cung cấp theo tenant (DV-10-04): bật, mặc định, danh sách dùng được, trùng mã nhà cung cấp.
 */
class ProviderRegistryTest {

  private static final UUID LIMITED = UUID.fromString("0192f1a2-0000-7000-8000-00000000e201");
  private static final UUID OPEN = UUID.fromString("0192f1a2-0000-7000-8000-00000000e202");
  private static final UUID EMPTY = UUID.fromString("0192f1a2-0000-7000-8000-00000000e203");

  private final FakeSignatureProvider fake =
      new FakeSignatureProvider(Clock.systemUTC(), Duration.ZERO);

  private ProviderRegistry registry() {
    Map<ProviderCode, String> ids = new EnumMap<>(ProviderCode.class);
    ids.put(ProviderCode.MYSIGN, FakeSignatureProvider.ID);
    ids.put(ProviderCode.VIETTEL_CA, FakeSignatureProvider.ID);
    ids.put(ProviderCode.BAN_CO_YEU, "chua-lap");
    return new ProviderRegistry(
        List.of(fake),
        ids,
        ProviderCode.MYSIGN,
        Map.of(
            LIMITED,
            new ProviderRegistry.TenantProviders(
                Set.of(ProviderCode.VIETTEL_CA), ProviderCode.VIETTEL_CA),
            EMPTY,
            new ProviderRegistry.TenantProviders(null, null)));
  }

  @Test
  void tenantKhaiRiengThiChiDungNhaCungCapDuocBat() {
    ProviderRegistry r = registry();
    assertThat(r.defaultCode(LIMITED)).isEqualTo(ProviderCode.VIETTEL_CA);
    assertThat(r.defaultCode(OPEN)).isEqualTo(ProviderCode.MYSIGN);
    assertThat(r.defaultCode(EMPTY)).isEqualTo(ProviderCode.MYSIGN);
    assertThat(r.enabled(LIMITED, ProviderCode.MYSIGN)).isFalse();
    assertThat(r.enabled(LIMITED, ProviderCode.VIETTEL_CA)).isTrue();
    assertThat(r.enabled(OPEN, ProviderCode.CA_CHINH_PHU)).isTrue();
    assertThat(r.enabled(EMPTY, ProviderCode.CA_CHINH_PHU)).isTrue();
    // Hai mã cùng trỏ một bản hiện thực: mỗi bản hiện thực chỉ một lần.
    assertThat(r.enabledFor(OPEN)).containsExactly(fake);
    assertThat(r.enabledFor(LIMITED)).containsExactly(fake);
    assertThat(r.forCode(ProviderCode.BAN_CO_YEU)).isEmpty();
    assertThat(r.defaultCode()).isEqualTo(ProviderCode.MYSIGN);
  }

  @Test
  void tenantKhongBatNhaCungCapNaoDaLapThiDanhSachRong() {
    ProviderRegistry r =
        new ProviderRegistry(
            List.of(fake),
            Map.of(ProviderCode.MYSIGN, FakeSignatureProvider.ID),
            ProviderCode.MYSIGN,
            Map.of(
                LIMITED,
                new ProviderRegistry.TenantProviders(Set.of(ProviderCode.VIETTEL_CA), null)));
    assertThat(r.enabledFor(LIMITED)).isEmpty();
    assertThat(r.defaultCode(LIMITED)).isEqualTo(ProviderCode.MYSIGN);
  }

  @Test
  void trungMaNhaCungCapBiTuChoi() {
    assertThatThrownBy(
            () -> new ProviderRegistry(List.of(fake, fake), Map.of(), ProviderCode.MYSIGN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Trùng nhà cung cấp");
  }

  @Test
  void taiLieuNguonSoSanhTheoThamChieuVaBam() {
    byte[] sha = new byte[32];
    var a =
        new SourceDocumentPort.Document(
            "PDF_A", "r", sha, vn.viettel.okrkpi.esign.domain.sign.SignatureFormat.PADES_LTA);
    var same =
        new SourceDocumentPort.Document(
            "PDF_A",
            "r",
            sha.clone(),
            vn.viettel.okrkpi.esign.domain.sign.SignatureFormat.PADES_LTA);
    var otherRef =
        new SourceDocumentPort.Document(
            "PDF_A", "x", sha, vn.viettel.okrkpi.esign.domain.sign.SignatureFormat.PADES_LTA);
    byte[] sha2 = new byte[32];
    sha2[0] = 1;
    var otherSha =
        new SourceDocumentPort.Document(
            "PDF_A", "r", sha2, vn.viettel.okrkpi.esign.domain.sign.SignatureFormat.PADES_LTA);
    assertThat(a)
        .isEqualTo(same)
        .hasSameHashCodeAs(same)
        .isNotEqualTo(otherRef)
        .isNotEqualTo(otherSha);
    assertThat(a.equals("r")).isFalse();
  }
}
