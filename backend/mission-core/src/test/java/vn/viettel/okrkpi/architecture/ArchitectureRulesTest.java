package vn.viettel.okrkpi.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.shared.archrules.PlatformArchitectureRules;

/**
 * Kiểm thử kiến trúc cho {@code SVC-05 mission-core} (CORE-00-02, TL-01 mục 2.1, ADR-02, ADR-07,
 * ADR-12, ADR-21, ADR-22):
 *
 * <ul>
 *   <li>Xác minh 8 mô-đun nghiệp vụ Spring Modulith ({@code config}, {@code policy}, {@code okr},
 *       {@code task}, {@code product}, {@code appraisal}, {@code dossier}, {@code oversight}) và ma
 *       trận phụ thuộc một chiều chỉ qua {@code :: api}.
 *   <li>Sinh sơ đồ PlantUML C4 và Module Canvas vào {@code target/spring-modulith-docs}.
 *   <li>Thực thi toàn bộ quy tắc ArchUnit của TL-01 và 5 quy tắc nền tảng {@link
 *       PlatformArchitectureRules}.
 *   <li>Kiểm thử âm tính chứng minh quy tắc bắt được vi phạm {@code task -> policy}, {@code domain
 *       -> spring} và truy cập gói {@code internal} xuyên mô-đun.
 * </ul>
 */
@AnalyzeClasses(
    packages = "vn.viettel.okrkpi",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureRulesTest {

  private static final String BASE_PACKAGE = "vn.viettel.okrkpi.";

  private static final String[] BUSINESS_MODULE_PACKAGES = {
    "vn.viettel.okrkpi.okr..",
    "vn.viettel.okrkpi.task..",
    "vn.viettel.okrkpi.product..",
    "vn.viettel.okrkpi.appraisal..",
    "vn.viettel.okrkpi.policy..",
    "vn.viettel.okrkpi.dossier..",
    "vn.viettel.okrkpi.oversight..",
    "vn.viettel.okrkpi.config.."
  };

  /** Ma trận phụ thuộc hợp lệ giữa 8 mô-đun nghiệp vụ (TL-01 mục 2.1, wiki/21). */
  private static final Map<String, Set<String>> ALLOWED_MODULE_DEPENDENCIES =
      Map.of(
          "config", Set.of(),
          "policy", Set.of("config"),
          "okr", Set.of("config", "policy"),
          "task", Set.of("config", "policy", "okr"),
          "product", Set.of("config", "policy", "task"),
          "appraisal", Set.of("config", "policy", "task", "product", "okr"),
          "dossier", Set.of("config", "appraisal"),
          "oversight", Set.of("config", "appraisal", "task", "product"));

  /** Quy tắc 1 (TL-01): Tầng {@code domain} thuần Java, không phụ thuộc Spring/JPA/Jackson/HTTP. */
  @ArchTest
  static final ArchRule domainIsPure =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "java..",
              "vn.viettel.okrkpi..domain..",
              "vn.viettel.okrkpi.shared.domain..",
              "vn.viettel.okrkpi.shared.kernel..")
          .allowEmptyShould(true)
          .because(
              "Tầng domain của mọi mô-đun trong mission-core phải thuần Java (TL-01 mục 2.1).");

  /** Quy tắc 2 (TL-01): Tầng {@code application} không phụ thuộc vào {@code infrastructure}. */
  @ArchTest
  static final ArchRule applicationDoesNotSeeInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..")
          .allowEmptyShould(true)
          .because("Tầng application chỉ gọi qua cổng domain/api, không phụ thuộc infrastructure.");

  /** Quy tắc 3 (TL-01): Gói {@code internal} là riêng tư trong từng mô-đun. */
  @ArchTest
  static final ArchRule internalIsPrivateToModule =
      SlicesRuleDefinition.slices()
          .matching("vn.viettel.okrkpi.(*)..")
          .namingSlices("Module '$1'")
          .should()
          .beFreeOfCycles()
          .because("8 mô-đun trong mission-core là đồ thị có hướng không chu trình (DAG).");

  /** Quy tắc 3b (TL-01): Chỉ gói {@code api} được lộ ra cho mô-đun nghiệp vụ khác. */
  @ArchTest
  static final ArchRule onlyApiPackageExposedToOtherModules =
      classes()
          .that()
          .resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
          .should(onlyAccessAllowedModulesViaApiPackage())
          .allowEmptyShould(true)
          .because(
              "Mô-đun chỉ được giao tiếp với mô-đun được phép trong ma trận phụ thuộc thông qua gói"
                  + " .api (TL-01 mục 2.1).");

  /**
   * Quy tắc 3c (CORE-00-03, TL-03 L464): bản sao tổ chức {@code org_replica} chỉ được đọc qua
   * {@code replica.api} ({@code OrgDirectoryApi}); mô-đun khác không chạm tầng ghi của bản sao.
   */
  @ArchTest
  static final ArchRule orgReplicaOnlyViaApi =
      noClasses()
          .that()
          .resideOutsideOfPackage("vn.viettel.okrkpi.replica..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "vn.viettel.okrkpi.replica.application..",
              "vn.viettel.okrkpi.replica.domain..",
              "vn.viettel.okrkpi.replica.infrastructure..",
              "vn.viettel.okrkpi.replica.internal..")
          .because("Bản sao tổ chức chỉ do consumer của nó ghi; mô-đun khác đọc qua replica.api.");

  /** Quy tắc 4 (TL-01): Controller REST chỉ đặt trong {@code ..infrastructure.web..}. */
  @ArchTest
  static final ArchRule controllersOnlyInWebAdapter =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAPackage("..infrastructure.web..")
          .allowEmptyShould(true)
          .because("Controller REST chỉ được đặt trong adapter ..infrastructure.web.. (TL-01).");

  /** Quy tắc 5 (TL-01): {@code @Transactional} chỉ được đặt ở tầng {@code application}. */
  @ArchTest
  static final ArchRule transactionsOnlyInApplication =
      classes()
          .that()
          .resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
          .and()
          .areAnnotatedWith(Transactional.class)
          .should()
          .resideInAPackage("..application..")
          .allowEmptyShould(true)
          .because("Ranh giới giao dịch của mô-đun nghiệp vụ chỉ đặt ở tầng application (TL-01).");

  /** Quy tắc 6 (TL-01): Không dùng {@code System.out} / {@code System.err}. */
  @ArchTest
  static final ArchRule noStdStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  /** Quy tắc 7 (TL-01): Không ném ngoại lệ chung ({@code Exception}, {@code RuntimeException}). */
  @ArchTest
  static final ArchRule noGenericExceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  /** Quy tắc 8 (TL-01): Không dùng {@code java.util.logging}. */
  @ArchTest
  static final ArchRule noJavaUtilLogging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  /**
   * Cấm mô-đun nghiệp vụ gọi HTTP trực tiếp sang nhau. Bộ chuyển đổi gọi dịch vụ ngoài mission-core
   * (ví dụ SVC-10 ký số, CORE-02-09) đặt ở {@code infrastructure.client} và đi qua {@code
   * ResilientClient}, nên được miễn.
   */
  @ArchTest
  static final ArchRule noDirectHttpCallsBetweenCoreModules =
      noClasses()
          .that()
          .resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
          .and()
          .resideOutsideOfPackage("..infrastructure.client..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.web.client..", "java.net.http..")
          .allowEmptyShould(true)
          .because(
              "Các mô-đun trong mission-core giao tiếp trong tiến trình qua cổng .api hoặc sự kiện,"
                  + " không gọi HTTP trực tiếp (TL-01).");

  /** Cấm mô-đun nghiệp vụ khởi tạo Valkey/Redis client trực tiếp. */
  @ArchTest
  static final ArchRule noDirectValkeyClientInBusinessModules =
      noClasses()
          .that()
          .resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.lettuce..", "org.springframework.data.redis.connection..")
          .allowEmptyShould(true)
          .because(
              "Mô-đun nghiệp vụ không được mở kết nối Valkey/Redis trực tiếp ngoài tầng"
                  + " shared/infrastructure được cấp phép.");

  /** Quy tắc nền tảng 1..5 từ {@link PlatformArchitectureRules} (NEN-008..NEN-015). */
  @ArchTest
  static final ArchRule platformNoUnboundedExecutors =
      PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS;

  @ArchTest
  static final ArchRule platformNoRequestLoggingFilters =
      PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS;

  @ArchTest
  static final ArchRule platformNoDirectConnectionAccess =
      PlatformArchitectureRules.NO_DIRECT_CONNECTION_ACCESS;

  @ArchTest
  static final ArchRule platformC2FieldsMustHaveConverter =
      PlatformArchitectureRules.C2_FIELDS_MUST_HAVE_CONVERTER;

  @ArchTest
  static final ArchRule platformC2TypesMustMaskToString =
      PlatformArchitectureRules.C2_TYPES_MUST_MASK_TO_STRING;

  /** NEN-035: bộ đệm chỉ qua TenantCache/TenantCacheKey, khoá luôn mang tenant. */
  @ArchTest
  static final ArchRule platformNoDirectValkeyClient =
      PlatformArchitectureRules.NO_DIRECT_VALKEY_CLIENT;

  /**
   * CORE-08-03 (TL-01 "chặn ghi khi kỳ khoá"): mọi phương thức ghi công khai của lớp ứng dụng ở
   * MOD-01, MOD-02, MOD-04 và lớp gắn chính sách (MOD-03) phải gọi {@code PeriodApi.assertWritable}
   * trước khi ghi.
   */
  @ArchTest
  static final ArchRule writesCheckPeriodLock =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(
              "vn.viettel.okrkpi.okr.application..",
              "vn.viettel.okrkpi.task.application..",
              "vn.viettel.okrkpi.product.application..",
              "vn.viettel.okrkpi.policy.application..")
          .and()
          .arePublic()
          .and()
          .haveNameMatching(
              "(create|update|register|save|delete|submit|assign|record|bind|remove)[A-Z].*")
          .should(
              new ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                  "gọi PeriodApi.assertWritable") {
                @Override
                public void check(
                    com.tngtech.archunit.core.domain.JavaMethod method, ConditionEvents events) {
                  boolean calls =
                      method.getMethodCallsFromSelf().stream()
                          .anyMatch(
                              c ->
                                  c.getTargetOwner()
                                          .isEquivalentTo(
                                              vn.viettel.okrkpi.config.api.PeriodApi.class)
                                      && c.getName().equals("assertWritable"));
                  if (!calls) {
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " ghi dữ liệu nhưng không gọi PeriodApi.assertWritable"));
                  }
                }
              })
          .allowEmptyShould(true);

  @Test
  void verifyModulithStructureAndGeneratePlantUmlDocs() {
    ApplicationModules modules = ApplicationModules.of(MissionCoreApplication.class).verify();

    for (String moduleName : ALLOWED_MODULE_DEPENDENCIES.keySet()) {
      Optional<ApplicationModule> moduleOpt = modules.getModuleByName(moduleName);
      assertThat(moduleOpt)
          .as("Mô-đun '%s' phải được Spring Modulith nhận diện", moduleName)
          .isPresent();
      ApplicationModule module = moduleOpt.get();
      assertThat(module.getNamedInterfaces().getByName("api"))
          .as("Mô-đun '%s' phải công bố @NamedInterface(\"api\")", moduleName)
          .isPresent();
    }

    new Documenter(modules)
        .writeDocumentation()
        .writeModulesAsPlantUml()
        .writeIndividualModulesAsPlantUml();

    Path componentsPuml = Path.of("target/spring-modulith-docs/components.puml");
    assertThat(componentsPuml).exists().isNotEmptyFile();
  }

  @Test
  void negativeTestsCatchForbiddenArchitectureViolations(@TempDir Path tempDir) throws IOException {
    // 1. Kiểm thử âm tính: config -> task (vi phạm ma trận phụ thuộc ngược chiều)
    Path configToTaskDir = tempDir.resolve("config-to-task");
    writeSyntheticClass(
        configToTaskDir,
        "vn/viettel/okrkpi/config/application/ForbiddenConfigToTaskInvoker",
        "vn/viettel/okrkpi/task/api/TaskQueryApi");
    JavaClasses configToTaskClasses =
        new ClassFileImporter()
            .importPaths(
                configToTaskDir,
                Path.of("target/classes/vn/viettel/okrkpi/task/api/TaskQueryApi.class"));
    assertThatThrownBy(() -> onlyApiPackageExposedToOtherModules.check(configToTaskClasses))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("config")
        .hasMessageContaining("task");

    // 2. Kiểm thử âm tính: task -> okr.internal (vi phạm đóng gói chỉ lộ .api)
    Path taskToInternalDir = tempDir.resolve("task-to-internal");
    writeSyntheticClass(
        taskToInternalDir,
        "vn/viettel/okrkpi/okr/internal/HiddenOkrInternalHelper",
        "java/lang/Object");
    writeSyntheticClass(
        taskToInternalDir,
        "vn/viettel/okrkpi/task/application/ForbiddenInternalAccessor",
        "vn/viettel/okrkpi/okr/internal/HiddenOkrInternalHelper");
    JavaClasses taskToInternalClasses = new ClassFileImporter().importPath(taskToInternalDir);
    assertThatThrownBy(() -> onlyApiPackageExposedToOtherModules.check(taskToInternalClasses))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("internal");

    // 3. Kiểm thử âm tính: domain -> org.springframework.stereotype.Component (vi phạm domain thuần
    // Java)
    Path domainToSpringDir = tempDir.resolve("domain-to-spring");
    writeSyntheticClass(
        domainToSpringDir,
        "vn/viettel/okrkpi/task/domain/ImpureDomainService",
        "org/springframework/stereotype/Component");
    JavaClasses domainToSpringClasses = new ClassFileImporter().importPath(domainToSpringDir);
    assertThatThrownBy(() -> domainIsPure.check(domainToSpringClasses))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ImpureDomainService");
  }

  private static ArchCondition<JavaClass> onlyAccessAllowedModulesViaApiPackage() {
    return new ArchCondition<>(
        "chỉ phụ thuộc vào gói .api của các mô-đun nằm trong allowedDependencies") {
      @Override
      public void check(JavaClass sourceClass, ConditionEvents events) {
        Optional<String> sourceModuleOpt = extractBusinessModule(sourceClass.getPackageName());
        if (sourceModuleOpt.isEmpty()) {
          return;
        }
        String sourceModule = sourceModuleOpt.get();
        Set<String> allowedTargets =
            ALLOWED_MODULE_DEPENDENCIES.getOrDefault(sourceModule, Set.of());

        for (Dependency dependency : sourceClass.getDirectDependenciesFromSelf()) {
          JavaClass targetClass = dependency.getTargetClass();
          Optional<String> targetModuleOpt = extractBusinessModule(targetClass.getPackageName());
          if (targetModuleOpt.isEmpty()) {
            continue;
          }
          String targetModule = targetModuleOpt.get();
          if (sourceModule.equals(targetModule)) {
            continue;
          }
          if (!allowedTargets.contains(targetModule)) {
            events.add(
                SimpleConditionEvent.violated(
                    dependency,
                    String.format(
                        "Vi phạm ma trận phụ thuộc: mô-đun '%s' (%s) không được phép phụ thuộc vào"
                            + " mô-đun '%s' (%s)",
                        sourceModule, sourceClass.getName(), targetModule, targetClass.getName())));
            continue;
          }
          String targetPkg = targetClass.getPackageName();
          String expectedApiPrefix = BASE_PACKAGE + targetModule + ".api";
          if (!targetPkg.equals(expectedApiPrefix)
              && !targetPkg.startsWith(expectedApiPrefix + ".")) {
            events.add(
                SimpleConditionEvent.violated(
                    dependency,
                    String.format(
                        "Vi phạm đóng gói mô-đun: '%s' chỉ được phép truy cập '%s' qua gói '%s'"
                            + " nhưng lại tham chiếu '%s'",
                        sourceClass.getName(),
                        targetModule,
                        expectedApiPrefix,
                        targetClass.getName())));
          }
        }
      }
    };
  }

  private static Optional<String> extractBusinessModule(String packageName) {
    if (!packageName.startsWith(BASE_PACKAGE)) {
      return Optional.empty();
    }
    String remainder = packageName.substring(BASE_PACKAGE.length());
    int dotIdx = remainder.indexOf('.');
    String module = dotIdx < 0 ? remainder : remainder.substring(0, dotIdx);
    return ALLOWED_MODULE_DEPENDENCIES.containsKey(module) ? Optional.of(module) : Optional.empty();
  }

  private static void writeSyntheticClass(
      Path rootDir, String internalClassName, String referencedInternalClassName)
      throws IOException {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalClassName, null, "java/lang/Object", null);
    cw.visitField(Opcodes.ACC_PRIVATE, "dep", "L" + referencedInternalClassName + ";", null, null)
        .visitEnd();
    MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    cw.visitEnd();

    Path classFile = rootDir.resolve(internalClassName + ".class");
    Files.createDirectories(classFile.getParent());
    Files.write(classFile, cw.toByteArray());
  }
}
