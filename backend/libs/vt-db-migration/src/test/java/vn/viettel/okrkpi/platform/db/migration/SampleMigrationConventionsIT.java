package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterAll;

/** Chạy lớp cơ sở trên bộ migration mẫu đúng quy ước, kèm migration dùng chung dải V0_x. */
class SampleMigrationConventionsIT extends MigrationConventionsTest {

  @Override
  protected List<String> schemas() {
    return List.of("lint_ok");
  }

  @Override
  protected MigrationOptions options() {
    return new MigrationOptions("classpath:db/test-common", "classpath:db/test-ok/", false);
  }

  @AfterAll
  static void danhSachLoaiKhoiCdcChiGomCotC2() throws IOException {
    assertThat(
            Files.readAllLines(
                java.nio.file.Path.of(
                    "target", "generated-resources", "cdc", "lint_ok-column-exclude-list.txt"),
                StandardCharsets.UTF_8))
        .containsExactly("lint_ok.sample_item.note_enc");
  }
}
