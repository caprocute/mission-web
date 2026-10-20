package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerArgsTest {

  @Test
  void phanTichThamSoVaDanhSachCsdl(@TempDir Path dir) throws IOException {
    Path list = dir.resolve("csdl.txt");
    Files.writeString(
        list, "# danh sách từ registry\njdbc:postgresql://a/td_a\n\njdbc:postgresql://b/td_b\n");
    MigrationRunner.Arguments a =
        MigrationRunner.parse(
            new String[] {
              "--schemas=okr, task",
              "--db=jdbc:postgresql://c/td_c",
              "--db-list=" + list,
              "--parallel=3"
            });
    assertThat(a.schemas()).containsExactly("okr", "task");
    assertThat(a.databases())
        .containsExactly(
            "jdbc:postgresql://c/td_c", "jdbc:postgresql://a/td_a", "jdbc:postgresql://b/td_b");
    assertThat(a.parallel()).isEqualTo(3);
  }

  @Test
  void macDinhNamCsdlSongSong() {
    assertThat(MigrationRunner.parse(new String[] {"--schemas=task", "--db=jdbc:x"}).parallel())
        .isEqualTo(5);
  }

  @Test
  void tuChoiThamSoSai() {
    assertThatThrownBy(() -> MigrationRunner.parse(new String[] {"--schemas=task"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MigrationRunner.parse(new String[] {"--db=jdbc:x"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MigrationRunner.parse(new String[] {"--db=jdbc:x", "--schemas=Task"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                MigrationRunner.parse(new String[] {"--db=jdbc:x", "--schemas=t", "--parallel=0"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MigrationRunner.parse(new String[] {"--la"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void maThoat() {
    var ok = new MigrationRunner.DatabaseOutcome("a", true, 1, null);
    var loi = new MigrationRunner.DatabaseOutcome("b", false, 0, "x");
    assertThat(MigrationRunner.exitCode(List.of(ok))).isZero();
    assertThat(MigrationRunner.exitCode(List.of(ok, loi))).isEqualTo(1);
  }

  @Test
  void anThamSoChuoiKetNoi() {
    assertThat(MigrationRunner.redact("jdbc:postgresql://h/db?password=x"))
        .isEqualTo("jdbc:postgresql://h/db?…");
    assertThat(MigrationRunner.redact("jdbc:postgresql://h/db"))
        .isEqualTo("jdbc:postgresql://h/db");
  }

  @Test
  void matKhauTuBienMoiTruongHoacTep(@TempDir Path dir) throws IOException {
    assertThat(
            new EnvironmentCredentials(Map.of("MIG_TASK_PASSWORD", "a"))
                .forSchema("jdbc:x", "task"))
        .isEqualTo(new MigrationCredentials.Credentials("mig_task", "a"));
    Files.writeString(dir.resolve("mig_okr"), "b\n");
    assertThat(
            new EnvironmentCredentials(Map.of("MIG_SECRETS_DIR", dir.toString()))
                .forSchema("jdbc:x", "okr")
                .password())
        .isEqualTo("b");
    assertThatThrownBy(() -> new EnvironmentCredentials(Map.of()).forSchema("jdbc:x", "okr"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MIG_OKR_PASSWORD");
    assertThat(new MigrationCredentials.Credentials("u", "bi-mat").toString())
        .doesNotContain("bi-mat");
  }
}
