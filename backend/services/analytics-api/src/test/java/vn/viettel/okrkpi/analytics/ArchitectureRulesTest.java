package vn.viettel.okrkpi.analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.archrules.PlatformArchitectureRules;

/** Kiến trúc lục giác của SVC-15 (NEN-019 · TL-01 "Quy tắc ArchUnit"). */
class ArchitectureRulesTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.viettel.okrkpi.analytics");
  }

  @Test
  void domainIsPure() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.fasterxml.jackson..",
            "org.apache.kafka..",
            "org.apache.avro..",
            "..infrastructure..",
            "..application..")
        .check(classes);
  }

  @Test
  void applicationDoesNotSeeInfrastructureOrTransport() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..infrastructure..",
            "org.springframework..",
            "org.apache.kafka..",
            "com.fasterxml.jackson..",
            "java.net.http..")
        .check(classes);
  }

  @Test
  void platformRules() {
    NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS.check(classes);
  }

  /**
   * Biến thể của {@code NO_DIRECT_VALKEY_CLIENT}: chỉ gói {@code infrastructure.cache} (nối kết nối
   * Valkey cho {@code TenantCache}) được chạm Lettuce; mọi lớp khác dùng {@code TenantCache}.
   */
  @Test
  void onlyCacheWiringTouchesValkeyClient() {
    noClasses()
        .that()
        .resideOutsideOfPackage("..infrastructure.cache..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("io.lettuce..", "org.springframework.data.redis..")
        .check(classes);
  }
}
