package vn.viettel.okrkpi.scoring;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.archrules.PlatformArchitectureRules;

/**
 * Kiểm thử kiến trúc lục giác bắt buộc cho mọi microservice Java (NEN-019 · TL-01 "Quy tắc
 * ArchUnit").
 */
class ArchitectureRulesTest {

  private static final String BASE_PACKAGE = "vn.viettel.okrkpi.scoring";

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  @Test
  void domainIsPure() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.hibernate..",
                "com.fasterxml.jackson..",
                "..infrastructure..",
                "..api..",
                "..application..");
    rule.check(classes);
  }

  @Test
  void applicationDoesNotSeeInfrastructure() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..", "..api..");
    rule.check(classes);
  }

  @Test
  void internalIsPrivateToModule() {
    ArchRule rule =
        classes()
            .that()
            .resideInAPackage(BASE_PACKAGE + ".internal..")
            .should()
            .onlyBeAccessed()
            .byClassesThat()
            .resideInAPackage(BASE_PACKAGE + "..")
            .allowEmptyShould(true);
    rule.check(classes);
  }

  @Test
  void controllersOnlyInWebAdapter() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAnyPackage("..api..", "..infrastructure.web..")
            .allowEmptyShould(true);
    rule.check(classes);
  }

  @Test
  void transactionsOnlyInApplication() {
    ArchRule classRule =
        classes()
            .that()
            .areAnnotatedWith(Transactional.class)
            .should()
            .resideInAPackage("..application..")
            .allowEmptyShould(true);
    ArchRule methodRule =
        methods()
            .that()
            .areAnnotatedWith(Transactional.class)
            .should()
            .beDeclaredInClassesThat()
            .resideInAPackage("..application..");
    classRule.check(classes);
    methodRule.check(classes);
  }

  @Test
  void generalCodingRules() {
    NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(classes);
    NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
  }

  @Test
  void platformArchitectureRules() {
    PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS.check(classes);
    PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS.check(classes);
    PlatformArchitectureRules.NO_DIRECT_CONNECTION_ACCESS.check(classes);
  }
}
