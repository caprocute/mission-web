package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Dòng CSV cho COPY: null là trường rỗng, chuỗi trong ngoặc kép, bytea dạng hex. */
class CopyRowsTest {

  @Test
  void maHoaDongCsv() {
    StringBuilder out = new StringBuilder();
    CopyScoreResultStore.row(out, null, "a\"b,c\nd", new byte[] {0x0f, (byte) 0xa0}, 7, "");
    assertThat(out).hasToString(",\"a\"\"b,c\nd\",\\x0fa0,7,\"\"\n");
  }
}
