package vn.viettel.okrkpi.appraisal.application.setup;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository;

/**
 * BR-09-16: không có API ghi ảnh chụp điểm; chỉ consumer EVT-09, EVT-10 ({@link
 * ProfileSetupService} qua {@link
 * vn.viettel.okrkpi.appraisal.infrastructure.listener.ProfileSetupListener}) được dùng kho ghi ảnh
 * chụp.
 */
class ScoreSnapshotWriteRuleTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.viettel.okrkpi");

  @Test
  void chiConsumerDungKhoGhiAnhChup() {
    classes()
        .that()
        .areAssignableTo(ProfileSetupRepository.class)
        .or()
        .areAssignableTo(ProfileSetupService.class)
        .should()
        .onlyHaveDependentClassesThat()
        .resideInAnyPackage(
            "..appraisal.application.setup..",
            "..appraisal.infrastructure.persistence..",
            "..appraisal.infrastructure.listener..")
        .check(CLASSES);
  }
}
