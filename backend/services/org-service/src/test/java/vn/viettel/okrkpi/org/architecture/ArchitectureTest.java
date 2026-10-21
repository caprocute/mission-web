package vn.viettel.okrkpi.org.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Quy tắc kiến trúc lục giác (docs/quy-dinh/BACKEND.md): domain thuần, application chỉ thấy cổng. */
@AnalyzeClasses(packages = "vn.viettel.okrkpi.org", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainThuan = noClasses().that().resideInAPackage("..org.domain..")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
          "com.fasterxml..", "..org.application..", "..org.infrastructure..");

  @ArchTest
  static final ArchRule applicationKhongThayHaTang = noClasses().that().resideInAPackage("..org.application..")
      .should().dependOnClassesThat().resideInAnyPackage("..org.infrastructure..", "org.springframework.jdbc..",
          "org.springframework.web..", "jakarta.servlet..", "java.sql..");

  @ArchTest
  static final ArchRule controllerChiNamTrongWeb = classes().that()
      .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
      .should().resideInAPackage("..org.infrastructure.web..").allowEmptyShould(true);

  @ArchTest
  static final ArchRule giaoDichChiOApplication = noClasses().that().resideOutsideOfPackage("..org.application..")
      .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class);

  @ArchTest
  static final ArchRule adapterLuuTruChiTrongPersistence = classes().that()
      .areAnnotatedWith(org.springframework.stereotype.Repository.class)
      .should().resideInAPackage("..org.infrastructure.persistence..");

  @ArchTest
  static final ArchRule khongInRaLuongChuan = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule khongDungJul = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
