package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedIdsTest {

  private static final String RAW = "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77";
  private static final UUID UUID_VALUE = UUID.fromString(RAW);

  @Test
  void tenantId() {
    assertThat(TenantId.parse(RAW)).isEqualTo(TenantId.of(UUID_VALUE));
    assertThat(TenantId.of(UUID_VALUE).value()).isEqualTo(UUID_VALUE);
    assertThat(TenantId.of(UUID_VALUE)).hasToString(RAW);
    assertThatThrownBy(() -> new TenantId(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void periodId() {
    assertThat(PeriodId.parse(RAW)).isEqualTo(PeriodId.of(UUID_VALUE));
    assertThat(PeriodId.of(UUID_VALUE)).hasToString(RAW);
    assertThat(Ids.isUuidV7(PeriodId.newId().value())).isTrue();
    assertThatThrownBy(() -> new PeriodId(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void actorId() {
    assertThat(ActorId.parse(RAW)).isEqualTo(ActorId.of(UUID_VALUE));
    assertThat(ActorId.of(UUID_VALUE)).hasToString(RAW);
    assertThatThrownBy(() -> new ActorId(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void kieuKhacNhauKhongBangNhauDuCungUuid() {
    assertThat((Object) TenantId.of(UUID_VALUE)).isNotEqualTo(PeriodId.of(UUID_VALUE));
  }
}
