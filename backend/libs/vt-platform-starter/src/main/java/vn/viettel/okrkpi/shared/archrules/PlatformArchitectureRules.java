package vn.viettel.okrkpi.shared.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.shared.crypto.AbstractC2AttributeConverter;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Classified;

/**
 * Luật ArchUnit dùng chung để kiểm thử kiến trúc của mission-core và microservice gọi (ArchUnit là
 * phụ thuộc tuỳ chọn của starter; service thêm {@code archunit-junit5} ở scope test):
 *
 * <pre>{@code
 * @ArchTest static final ArchRule executors = PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS;
 * }</pre>
 */
public final class PlatformArchitectureRules {

  /**
   * TL-01 "Cô lập tài nguyên theo phụ thuộc": cấm {@code Executors.newCachedThreadPool} và {@code
   * newFixedThreadPool} (hàng đợi hoặc số luồng không giới hạn); tác vụ nền dùng {@code
   * ThreadPoolExecutor} có hàng đợi giới hạn và chính sách từ chối rõ ràng. Thư viện nền cũng tuân
   * thủ (lời gọi ra ngoài dùng luồng ảo, bị chặn bởi bulkhead).
   */
  public static final ArchRule NO_UNBOUNDED_EXECUTORS =
      noClasses()
          .should()
          .callMethodWhere(unboundedExecutorFactory())
          .because(
              "TL-01: ExecutorService không giới hạn bị cấm; dùng executor có hàng đợi giới hạn");

  /**
   * TL-01 "Che dữ liệu nhạy cảm": cấm ghi đối tượng yêu cầu (thân, header {@code Authorization},
   * cookie) bằng bộ lọc ghi yêu cầu của Spring.
   */
  public static final ArchRule NO_REQUEST_LOGGING_FILTERS =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.web.filter.AbstractRequestLoggingFilter")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.web.filter.CommonsRequestLoggingFilter")
          .because("TL-01: không ghi thân yêu cầu, header Authorization, cookie vào nhật ký");

  /**
   * NEN-010, TL-01 chặng bốn: mã nghiệp vụ không tự lấy {@code Connection} từ {@code DataSource}
   * (bỏ qua trình quản lý giao dịch đặt {@code app.tenant_id}); chỉ thư viện nền được làm.
   */
  public static final ArchRule NO_DIRECT_CONNECTION_ACCESS =
      noClasses()
          .that()
          .resideOutsideOfPackage("vn.viettel.okrkpi.shared..")
          .should()
          .callMethod(javax.sql.DataSource.class, "getConnection")
          .orShould()
          .callMethod(javax.sql.DataSource.class, "getConnection", String.class, String.class)
          .because(
              "NEN-010: truy cập CSDL qua giao dịch Spring để app.tenant_id được đặt cục bộ giao dịch")
          .allowEmptyShould(true);

  /**
   * NEN-015, ADR-06, TL-01 "Mã hoá phong bì cho dữ liệu C2": thuộc tính {@code @Classified(C2)}
   * trong lớp thực thể JPA ({@code @Entity}, {@code @MappedSuperclass}, {@code @Embeddable}) bắt
   * buộc phải đi kèm {@code @Convert} dùng {@link AbstractC2AttributeConverter} (hoặc đã là mảng
   * {@code byte[]} bản mã).
   */
  public static final ArchRule C2_FIELDS_MUST_HAVE_CONVERTER =
      fields()
          .that(areClassifiedC2InJpaTypes())
          .should(haveEncryptingJpaConverterOrByteArrayType())
          .because(
              "NEN-015 / ADR-06: thuộc tính @Classified(C2) trên thực thể JPA phải có @Convert mã hoá phong bì")
          .allowEmptyShould(true);

  /**
   * NEN-015, TL-01 "Che dữ liệu nhạy cảm": lớp có thuộc tính {@code @Classified(C2)} hoặc
   * {@code @Classified(C3)} bắt buộc ghi đè {@code toString()} và không được đọc trực tiếp thuộc
   * tính nhạy cảm trong {@code toString()}.
   */
  public static final ArchRule C2_TYPES_MUST_MASK_TO_STRING =
      classes()
          .that(declareSensitiveClassifiedField())
          .should(overrideToStringWithoutReadingSensitiveFields())
          .because(
              "NEN-015 / TL-01: lớp chứa thuộc tính @Classified(C2/C3) phải ghi đè toString() và che trường nhạy cảm")
          .allowEmptyShould(true);

  private PlatformArchitectureRules() {}

  private static DescribedPredicate<JavaMethodCall> unboundedExecutorFactory() {
    return DescribedPredicate.describe(
        "Executors.newCachedThreadPool hoặc Executors.newFixedThreadPool",
        call ->
            call.getTargetOwner().isEquivalentTo(java.util.concurrent.Executors.class)
                && (call.getName().equals("newCachedThreadPool")
                    || call.getName().equals("newFixedThreadPool")));
  }

  private static DescribedPredicate<JavaField> areClassifiedC2InJpaTypes() {
    return DescribedPredicate.describe(
        "được đánh dấu @Classified(C2) trong lớp JPA (@Entity/@MappedSuperclass/@Embeddable)",
        field -> isJpaManagedType(field.getOwner()) && isClassifiedAs(field, Classification.C2));
  }

  private static boolean isJpaManagedType(JavaClass clazz) {
    return clazz.isAnnotatedWith("jakarta.persistence.Entity")
        || clazz.isAnnotatedWith("jakarta.persistence.MappedSuperclass")
        || clazz.isAnnotatedWith("jakarta.persistence.Embeddable");
  }

  private static boolean isClassifiedAs(JavaField field, Classification target) {
    if (!field.isAnnotatedWith(Classified.class)) {
      return false;
    }
    Classified ann = field.getAnnotationOfType(Classified.class);
    return ann.value() == target;
  }

  private static boolean isSensitiveField(JavaField field) {
    if (!field.isAnnotatedWith(Classified.class)) {
      return false;
    }
    Classified ann = field.getAnnotationOfType(Classified.class);
    return ann.value().isSensitive();
  }

  private static ArchCondition<JavaField> haveEncryptingJpaConverterOrByteArrayType() {
    return new ArchCondition<>(
        "có @Convert với trình chuyển đổi kế thừa AbstractC2AttributeConverter hoặc kiểu byte[]") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        if (field.getRawType().isEquivalentTo(byte[].class)) {
          return;
        }
        Optional<JavaAnnotation<JavaField>> convertAnn =
            field.tryGetAnnotationOfType("jakarta.persistence.Convert");
        if (convertAnn.isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  "Thuộc tính "
                      + field.getFullName()
                      + " đánh dấu @Classified(C2) trong thực thể JPA nhưng thiếu @Convert"));
          return;
        }
        Optional<Object> converterVal = convertAnn.get().get("converter");
        if (converterVal.isEmpty() || !(converterVal.get() instanceof JavaClass converterClass)) {
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  "Thuộc tính "
                      + field.getFullName()
                      + " có @Convert nhưng không chỉ định converter mã hoá C2"));
          return;
        }
        if (!converterClass.isAssignableTo(AbstractC2AttributeConverter.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  "Thuộc tính "
                      + field.getFullName()
                      + " dùng converter "
                      + converterClass.getName()
                      + " không kế thừa AbstractC2AttributeConverter"));
        }
      }
    };
  }

  private static DescribedPredicate<JavaClass> declareSensitiveClassifiedField() {
    return DescribedPredicate.describe(
        "khai báo thuộc tính @Classified(C2) hoặc @Classified(C3)",
        clazz -> clazz.getFields().stream().anyMatch(PlatformArchitectureRules::isSensitiveField));
  }

  private static ArchCondition<JavaClass> overrideToStringWithoutReadingSensitiveFields() {
    return new ArchCondition<>(
        "ghi đè toString() và không in trực tiếp giá trị thuộc tính @Classified(C2/C3)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        Set<String> sensitiveNames =
            clazz.getFields().stream()
                .filter(PlatformArchitectureRules::isSensitiveField)
                .map(JavaField::getName)
                .collect(Collectors.toSet());
        if (sensitiveNames.isEmpty()) {
          return;
        }
        Optional<JavaMethod> toStringOpt = clazz.tryGetMethod("toString");
        if (toStringOpt.isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  "Lớp "
                      + clazz.getName()
                      + " có thuộc tính @Classified nhạy cảm "
                      + sensitiveNames
                      + " nhưng không ghi đè toString() để che dữ liệu"));
          return;
        }
        JavaMethod toStringMethod = toStringOpt.get();
        for (JavaFieldAccess access : toStringMethod.getFieldAccesses()) {
          if (access.getTarget().getOwner().equals(clazz)
              && sensitiveNames.contains(access.getTarget().getName())) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    "Phương thức "
                        + toStringMethod.getFullName()
                        + " đọc trực tiếp trường nhạy cảm '"
                        + access.getTarget().getName()
                        + "'"));
          }
        }
        Set<String> forbiddenGetters =
            sensitiveNames.stream()
                .flatMap(
                    name ->
                        Set.of(
                            name,
                            "get"
                                + name.substring(0, 1).toUpperCase(Locale.ROOT)
                                + name.substring(1))
                            .stream())
                .collect(Collectors.toSet());
        for (JavaMethodCall call : toStringMethod.getMethodCallsFromSelf()) {
          if (call.getTarget().getOwner().equals(clazz)
              && forbiddenGetters.contains(call.getTarget().getName())
              && call.getTarget().getRawParameterTypes().isEmpty()) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    "Phương thức "
                        + toStringMethod.getFullName()
                        + " gọi getter của trường nhạy cảm '"
                        + call.getTarget().getName()
                        + "()'"));
          }
        }
      }
    };
  }
}
