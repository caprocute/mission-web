package vn.vt.mission.fixture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Kiểm thử kiến trúc tối thiểu (TL-01 "Tháp kiểm thử", J9): lớp domain không phụ thuộc lớp application. */
@AnalyzeClasses(packages = "vn.vt.mission.fixture", importOptions = ImportOption.DoNotIncludeTests.class)
class KienTrucTest {

  @ArchTest
  static final ArchRule domainKhongPhuThuocApplication =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..application..");
}
