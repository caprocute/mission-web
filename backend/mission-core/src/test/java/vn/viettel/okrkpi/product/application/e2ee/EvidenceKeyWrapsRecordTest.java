package vn.viettel.okrkpi.product.application.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps;

/** CORE-07-06: bản bọc minh chứng không in khoá ra nhật ký, hiệu lực theo dấu thu hồi. */
class EvidenceKeyWrapsRecordTest {

  @Test
  void khongInKhoaVaHieuLucTheoThuHoi() {
    UUID nguoi = UUID.randomUUID();
    Instant luc = Instant.parse("2026-10-23T01:00:00Z");
    var con =
        new EvidenceKeyWraps.StoredWrap(
            nguoi, new byte[] {1}, "A", new byte[] {7}, nguoi, luc, null);
    var het =
        new EvidenceKeyWraps.StoredWrap(
            nguoi, new byte[] {1}, "A", new byte[] {7}, nguoi, luc, luc);
    assertThat(con.active()).isTrue();
    assertThat(het.active()).isFalse();
    assertThat(con.toString()).isEqualTo("StoredWrap[recipientId=" + nguoi + "]");
    assertThat(new EvidenceKeyWraps.NewWrap(nguoi, new byte[] {1}, "A", new byte[] {7}).toString())
        .isEqualTo("NewWrap[recipientId=" + nguoi + "]");
  }
}
