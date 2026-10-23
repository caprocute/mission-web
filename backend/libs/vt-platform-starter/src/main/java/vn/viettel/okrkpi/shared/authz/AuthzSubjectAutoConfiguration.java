package vn.viettel.okrkpi.shared.authz;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Bộ trích chủ thể từ JWT ({@link AuthzSubjectResolver}) luôn có mặt khi service dùng JWT, kể cả
 * khi tắt OPA ({@code okrkpi.opa.enabled=false}, mặc định của dev compose): các controller lấy
 * người gọi qua bean này, thiếu bean thì mọi API cần biết người gọi trả 401 dù token hợp lệ. Phần
 * kiểm quyền OPA vẫn chỉ bật theo {@link AuthzAutoConfiguration}.
 */
@AutoConfiguration(before = AuthzAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.Jwt")
public class AuthzSubjectAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public AuthzSubjectResolver authzSubjectResolver() {
    return new AuthzSubjectResolver();
  }
}
