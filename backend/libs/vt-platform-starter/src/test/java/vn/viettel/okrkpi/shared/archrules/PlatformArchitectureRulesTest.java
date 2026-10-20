package vn.viettel.okrkpi.shared.archrules;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Luật dùng chung của TL-01 "Cô lập tài nguyên theo phụ thuộc" và "Che dữ liệu nhạy cảm". */
class PlatformArchitectureRulesTest {

  private static JavaClasses classes(String pkg) {
    return new ClassFileImporter().importPackages(pkg);
  }

  @Test
  void camExecutorKhongGioiHanNgoaiThuVienNen() {
    assertThatThrownBy(
            () ->
                PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS.check(
                    classes("vn.viettel.okrkpi.archfixture.bad")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("newCachedThreadPool")
        .hasMessageContaining("newFixedThreadPool");
    PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS.check(
        classes("vn.viettel.okrkpi.archfixture.good"));
  }

  @Test
  void camBoLocGhiYeuCau() {
    assertThatThrownBy(
            () ->
                PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS.check(
                    classes("vn.viettel.okrkpi.archfixture.bad")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("CommonsRequestLoggingFilter");
    PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS.check(
        classes("vn.viettel.okrkpi.archfixture.good"));
  }

  @Test
  void thuVienNenTuanThuLuat() {
    JavaClasses platform =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.viettel.okrkpi.shared");
    PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS.check(platform);
    PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS.check(platform);
  }
}
