package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

class TenantBucketServiceTest {

  private final List<String> calls = new ArrayList<>();

  private final ObjectStorage storage =
      new ObjectStorage() {
        @Override
        public boolean ensureLockedBucket(String bucket, int lockDays) {
          calls.add(bucket + "/" + lockDays);
          return true;
        }

        @Override
        public StoredObject put(
            ObjectLocation location, byte[] content, String mediaType, Instant lockUntil) {
          throw new UnsupportedOperationException();
        }

        @Override
        public byte[] read(ObjectLocation location, String versionId) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  void capBucketCoKhoaTheoThamSo() {
    TenantBucketService service = new TenantBucketService(storage, "hn-01", 3650);
    UUID tenant = UUID.randomUUID();
    assertThat(service.provision(tenant)).isEqualTo("ev-hn-01-" + tenant);
    assertThat(service.provision(tenant)).isEqualTo("ev-hn-01-" + tenant);
    assertThat(calls)
        .containsExactly("ev-hn-01-" + tenant + "/3650", "ev-hn-01-" + tenant + "/3650");
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
