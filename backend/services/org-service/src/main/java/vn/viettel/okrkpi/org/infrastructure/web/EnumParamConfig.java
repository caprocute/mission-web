package vn.viettel.okrkpi.org.infrastructure.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancyField;

/**
 * Tham số truy vấn có giá trị enum viết thường trong hợp đồng ({@code field=org_unit_id}) phải đọc bằng
 * {@code fromValue}; bộ chuyển mặc định của Spring dùng tên hằng nên trả 400.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class EnumParamConfig implements WebMvcConfigurer {

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, OrgDiscrepancyField.class, OrgDiscrepancyField::fromValue);
  }
}
