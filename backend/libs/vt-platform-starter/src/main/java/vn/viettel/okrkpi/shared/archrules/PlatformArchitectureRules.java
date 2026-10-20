package vn.viettel.okrkpi.shared.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;

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

  private PlatformArchitectureRules() {}

  private static DescribedPredicate<JavaMethodCall> unboundedExecutorFactory() {
    return DescribedPredicate.describe(
        "Executors.newCachedThreadPool hoặc Executors.newFixedThreadPool",
        call ->
            call.getTargetOwner().isEquivalentTo(java.util.concurrent.Executors.class)
                && (call.getName().equals("newCachedThreadPool")
                    || call.getName().equals("newFixedThreadPool")));
  }
}
