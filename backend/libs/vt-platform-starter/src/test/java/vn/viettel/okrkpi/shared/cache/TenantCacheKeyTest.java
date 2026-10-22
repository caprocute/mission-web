package vn.viettel.okrkpi.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.MissingTenantException;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** NEN-035: quy ước khoá {@code t:<tenant>:<module>:<loại>:<mã>[:<phụ>]} của TL-03. */
class TenantCacheKeyTest {

  private static final TenantId A =
      TenantId.of(UUID.fromString("3f2a0000-0000-7000-8000-00000000000a"));

  @Test
  void khoaMangTenantTuNguCanh() {
    TenantContext.run(
        A,
        () -> {
          assertThat(TenantCacheKey.of("task", "card", "018f").value())
              .isEqualTo("t:3f2a0000-0000-7000-8000-00000000000a:task:card:018f");
          assertThat(TenantCacheKey.of("org", "unit_tree", "2026-03-31").value())
              .endsWith(":org:unit_tree:2026-03-31");
          assertThat(TenantCacheKey.of("config", "period", "current", "v2").value())
              .endsWith(":config:period:current:v2");
          assertThat(TenantCacheKey.grouped("rl:u1", "minute").value())
              .isEqualTo("t:3f2a0000-0000-7000-8000-00000000000a:{rl:u1}:minute");
          assertThat(TenantCacheKey.of("task", "card", "1").tenant()).isEqualTo(A);
        });
  }

  @Test
  void khongTaoDuocKhoaThieuTenant() {
    assertThat(TenantContext.current()).isEmpty();
    assertThatThrownBy(() -> TenantCacheKey.of("task", "card", "018f"))
        .isInstanceOf(MissingTenantException.class);
    assertThatThrownBy(() -> TenantCacheKey.grouped("rl:u1", "minute"))
        .isInstanceOf(MissingTenantException.class);
    assertThatThrownBy(() -> TenantCacheKey.forTenant(null, "task", "card", "1"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void chanThanhPhanKhoaGhepTuDo() {
    TenantContext.run(
        A,
        () -> {
          // Không chèn được dấu ':' hay ký tự đại diện để nhảy sang không gian khoá khác.
          assertThatThrownBy(() -> TenantCacheKey.of("task", "card", "x:t:other"))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> TenantCacheKey.of("task", "card", "*"))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> TenantCacheKey.of("", "card", "1"))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> TenantCacheKey.grouped("rl:{u}", "minute"))
              .isInstanceOf(IllegalArgumentException.class);
        });
  }

  @Test
  void mauScanChiKhopMotTenant() {
    assertThat(TenantCacheKey.scanPattern(A)).isEqualTo("t:3f2a0000-0000-7000-8000-00000000000a:*");
  }

  @Test
  void ttlCoNhieuTrongKhoangMuoiPhanTram() {
    for (int i = 0; i < 1_000; i++) {
      assertThat(ValkeyTenantCache.jitter(Duration.ofMinutes(5))).isBetween(270_000L, 330_000L);
    }
    assertThatThrownBy(() -> ValkeyTenantCache.jitter(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
