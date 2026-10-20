package vn.viettel.okrkpi.platform.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Meta-annotation chuẩn cho mọi lớp kiểm thử tích hợp ({@code *IT.java}) của hệ thống Mission.
 *
 * <p>Kết hợp {@link SpringBootTest}, {@link ActiveProfiles} ({@code "test"}) và {@link
 * Testcontainers} ({@code disabledWithoutDocker = true}) để chạy ở pha Failsafe ({@code ./mvnw
 * verify}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {

  /** Lớp cấu hình hoặc lớp ứng dụng Spring Boot cần nạp cho kiểm thử tích hợp. */
  @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
  Class<?>[] classes() default {};

  /** Thuộc tính ghi đè bổ sung cho ngữ cảnh Spring Test. */
  @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
  String[] properties() default {};
}
