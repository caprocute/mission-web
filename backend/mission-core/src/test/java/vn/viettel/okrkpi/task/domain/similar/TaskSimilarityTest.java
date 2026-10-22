package vn.viettel.okrkpi.task.domain.similar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskSimilarityTest {

  static final UUID PERIOD = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();

  @Test
  void fingerprintIgnoresCaseSpacesAndPunctuation() {
    byte[] a = TaskSimilarity.fingerprint(PERIOD, "Báo cáo  tổng kết, quý III.", "Báo cáo", LEAD);
    byte[] b = TaskSimilarity.fingerprint(PERIOD, " báo cáo tổng kết quý iii ", "báo cáo!", LEAD);
    byte[] c = TaskSimilarity.fingerprint(PERIOD, "Báo cáo tổng kết quý II", "Báo cáo", LEAD);
    assertThat(a).isEqualTo(b).isNotEqualTo(c);
    assertThat(TaskSimilarity.normalize(null)).isEmpty();
    assertThat(TaskSimilarity.normalize(" -- A, b ")).isEqualTo("a b");
  }

  @Test
  void similarityIsDiceOnFoldedTrigrams() {
    assertThat(TaskSimilarity.similarity("Báo cáo tổng kết quý III", "bao cao tong ket quy III"))
        .isEqualByComparingTo("1.00");
    assertThat(
            TaskSimilarity.similar(
                "Báo cáo tổng kết công tác quý III", "Báo cáo tổng kết công tác quý IV"))
        .isTrue();
    assertThat(TaskSimilarity.similar("Báo cáo tổng kết quý III", "Tổ chức hội nghị cán bộ"))
        .isFalse();
    assertThat(TaskSimilarity.similarity("", "x")).isEqualByComparingTo("0");
    assertThat(TaskSimilarity.similarity("Đường", "duong")).isEqualByComparingTo("1.00");
  }
}
