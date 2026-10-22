package vn.viettel.okrkpi.scoring.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class PolicyPackPublishedConsumerTest {

  @Test
  void bamBytesThanhHexKhongDichViTriBoDem() {
    ByteBuffer b = ByteBuffer.wrap(new byte[] {(byte) 0xAB, 0x01});
    assertThat(PolicyPackPublishedConsumer.hex(b)).isEqualTo("ab01");
    assertThat(b.remaining()).isEqualTo(2);
  }
}
