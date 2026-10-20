package vn.viettel.okrkpi.shared.error;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * TL-01 "Cấu trúc gói Java theo module": lớp domain chỉ phụ thuộc {@code shared.kernel}, nên bản
 * thân {@code vt-kernel} không được phụ thuộc Spring, JPA hay Jackson.
 */
class KernelArchitectureTest {

  private static final String KERNEL = "vn.viettel.okrkpi.shared.kernel..";
  private static JavaClasses kernel;

  @BeforeAll
  static void importKernel() {
    kernel =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.viettel.okrkpi.shared.kernel");
    Assertions.assertThat(kernel).as("phải nhập được lớp của vt-kernel").isNotEmpty();
  }

  @Test
  void kernelKhongPhuThuocSpring() {
    noClasses()
        .that()
        .resideInAPackage(KERNEL)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .check(kernel);
  }

  @Test
  void kernelKhongPhuThuocJpa() {
    noClasses()
        .that()
        .resideInAPackage(KERNEL)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "org.hibernate..")
        .check(kernel);
  }

  @Test
  void kernelKhongPhuThuocJackson() {
    noClasses()
        .that()
        .resideInAPackage(KERNEL)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.fasterxml.jackson..")
        .check(kernel);
  }

  @Test
  void kernelChiDungJdkVaUuidCreator() {
    noClasses()
        .that()
        .resideInAPackage(KERNEL)
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", KERNEL, "com.github.f4b6a3.uuid..")
        .check(kernel);
  }
}
