package vn.viettel.okrkpi.task.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents;

/**
 * CORE-02-24 · ranh giới C3 của nội dung nhiệm vụ mật: chỉ use case nội dung mật và adapter của nó
 * (và controller trả bản mã cho người nhận) chạm tới kho bản mã; bảng bản mã và bản bọc không có
 * trong publication CDC phân tích.
 */
class SecretContentBoundaryTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.viettel.okrkpi");

  @Test
  void chiUseCaseNoiDungMatDocDuocBanMa() {
    noClasses()
        .that()
        .haveNameNotMatching(
            "vn\\.viettel\\.okrkpi\\.task\\..*\\.(TaskSecretContentService|TaskSecretContentController"
                + "|JdbcTaskSecretContents|TaskSecretContentConfiguration)(\\$.*)?")
        .and()
        .resideOutsideOfPackage("..task.application.secret.port..")
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(TaskSecretContents.class)
        .because("bản mã C3 chỉ đi qua TaskSecretContentService (TL-03 xử lý theo phân loại)")
        .check(CLASSES);
  }

  @Test
  void bangNoiDungMatKhongCoTrongPublicationPhanTich() throws IOException {
    Path generated = Path.of("..", "..", "analytics", "cdc", "generated");
    List<Path> publications;
    try (Stream<Path> files = Files.walk(generated)) {
      publications = files.filter(f -> f.endsWith("publication.sql")).toList();
    }
    assertThat(publications).isNotEmpty();
    for (Path f : publications) {
      assertThat(Files.readString(f))
          .as(f.toString())
          .contains("pub_analytics")
          .doesNotContain("task_secret_content")
          .doesNotContain("e2ee_key_wrap");
    }
  }
}
