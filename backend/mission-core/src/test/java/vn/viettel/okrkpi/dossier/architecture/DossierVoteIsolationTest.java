package vn.viettel.okrkpi.dossier.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * BR-11-07 (CORE-06-04): kết quả biểu quyết tại hội nghị chỉ nằm ở {@code vote_json}, không thay
 * mức quyết định. MOD-06 không được phụ thuộc lớp nào của MOD-04 ngoài {@code appraisal.api}, và
 * trong {@code appraisal.api} chỉ được gọi thao tác đọc ({@code find*}), nên không có đường nào ghi
 * vào {@code appraisal.rating_proposal}.
 */
class DossierVoteIsolationTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.viettel.okrkpi");

  @Test
  void mod06KhongPhuThuocNoiBoMod04() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("vn.viettel.okrkpi.dossier..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "vn.viettel.okrkpi.appraisal.application..",
                "vn.viettel.okrkpi.appraisal.domain..",
                "vn.viettel.okrkpi.appraisal.infrastructure..",
                "vn.viettel.okrkpi.appraisal.internal..")
            .because("BR-11-07: biểu quyết không được ghi vào mức đề xuất hay mức quyết định");
    rule.check(CLASSES);
  }

  @Test
  void mod06ChiGoiThaoTacDocCuaAppraisalApi() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("vn.viettel.okrkpi.dossier..")
            .should()
            .callMethodWhere(
                com.tngtech.archunit.base.DescribedPredicate.describe(
                    "phương thức ghi của appraisal.api",
                    call ->
                        call.getTargetOwner()
                                .getPackageName()
                                .startsWith("vn.viettel.okrkpi.appraisal")
                            && !call.getName().startsWith("find")))
            .because("BR-11-07: MOD-06 chỉ đọc kết quả của MOD-04");
    rule.check(CLASSES);
  }
}
