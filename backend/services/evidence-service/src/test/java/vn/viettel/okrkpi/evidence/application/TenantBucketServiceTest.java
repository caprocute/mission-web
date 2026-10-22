package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantBucketServiceTest {

  private final ObjectStorage storage = mock(ObjectStorage.class);

  @Test
  void capBucketCoKhoaTheoThamSo() {
    TenantBucketService service = new TenantBucketService(storage, "hn-01", 3650);
    UUID tenant = UUID.randomUUID();
    assertThat(service.provision(tenant)).isEqualTo("ev-hn-01-" + tenant);
    assertThat(service.provision(tenant)).isEqualTo("ev-hn-01-" + tenant);
    verify(storage, times(2)).ensureLockedBucket("ev-hn-01-" + tenant, 3650);
    assertThat(service.lockDays()).isEqualTo(3650);
  }

  @Test
  void thamSoSaiBiTuChoiKhiKhoiTao() {
    assertThatThrownBy(() -> new TenantBucketService(storage, "hn-01", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantBucketService(storage, "HN_01", 30))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TenantBucketService(null, "hn-01", 30))
        .isInstanceOf(NullPointerException.class);
  }
}
