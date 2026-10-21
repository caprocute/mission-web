package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class TenantContextTest {

  private static final TenantId A = TenantId.of(UUID.randomUUID());
  private static final TenantId B = TenantId.of(UUID.randomUUID());

  @Test
  void runLongNhauVaKhoiPhuc() {
    assertThat(TenantContext.current()).isEmpty();
    TenantContext.run(
        A,
        () -> {
          assertThat(TenantContext.require()).isEqualTo(A);
          TenantContext.run(B, () -> assertThat(TenantContext.require()).isEqualTo(B));
          assertThat(TenantContext.require()).isEqualTo(A);
        });
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void callTraKetQuaVaDonNguCanhKhiLoi() throws Exception {
    assertThat(TenantContext.call(A, () -> TenantContext.require())).isEqualTo(A);
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    A,
                    () -> {
                      throw new IllegalArgumentException("x");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void requireNemLoiKhiThieuTenant() {
    assertThatThrownBy(TenantContext::require).isInstanceOf(MissingTenantException.class);
  }

  @Test
  void luongKhacKhongThayTenant() throws Exception {
    TenantContext.run(
        A,
        () -> {
          Thread t =
              Thread.ofVirtual()
                  .unstarted(
                      () -> {
                        assertThat(TenantContext.current()).isEmpty();
                      });
          t.start();
          try {
            t.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }
}
