package vn.viettel.okrkpi.shared.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobIdsTest {

  private static final UUID ID = UUID.fromString("0192f1a2-3b4c-7d5e-8f60-123456789abc");

  @Test
  void formatThenParseRoundTrips() {
    String s = JobIds.format("score", ID);
    assertThat(s).isEqualTo("score-0192f1a2-3b4c-7d5e-8f60-123456789abc");
    assertThat(JobIds.parse("score", s)).contains(ID);
  }

  @Test
  void bareUuidStillAccepted() {
    assertThat(JobIds.parse("doc", ID.toString())).contains(ID);
  }

  @Test
  void rejectsOtherPrefixGarbageAndNull() {
    assertThat(JobIds.parse("doc", JobIds.format("score", ID))).isEmpty();
    assertThat(JobIds.parse("doc", "doc-khong-phai-uuid")).isEmpty();
    assertThat(JobIds.parse("doc", "1-1-1-1-1")).isEmpty();
    assertThat(JobIds.parse("doc", null)).isEmpty();
  }
}
