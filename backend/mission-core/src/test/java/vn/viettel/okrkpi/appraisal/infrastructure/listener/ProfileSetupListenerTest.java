package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService;
import vn.viettel.okrkpi.appraisal.infrastructure.persistence.ProfileSetupConfiguration;
import vn.viettel.okrkpi.events.EventTopics;

class ProfileSetupListenerTest {

  @Test
  void diemNhanEvt09Evt10VaChepByte() {
    ProfileSetupListener listener = new ProfileSetupListener(mock(ProfileSetupService.class));
    assertThat(ProfileSetupConfiguration.endpoints(listener))
        .containsOnlyKeys(EventTopics.EVT_09_KY_DA_KHOA, EventTopics.EVT_10_DIEM_DA_TINH);
    ByteBuffer b = ByteBuffer.wrap(new byte[] {1, 2, 3});
    b.get();
    assertThat(ProfileSetupListener.bytes(b)).containsExactly(2, 3);
    assertThat(b.remaining()).isEqualTo(2);
    assertThat(ProfileSetupListener.bytes(null)).isNull();
  }
}
