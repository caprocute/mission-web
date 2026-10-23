package vn.viettel.okrkpi.workflow.infrastructure.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Danh mục mẫu luồng trên classpath dùng chung cho danh mục chọn mẫu và engine thử (DV-06-08). */
@Configuration(proxyBeanMethods = false)
public class TemplateCatalogConfiguration {

  @Bean
  TemplateCatalog templateCatalog(ObjectMapper json) {
    return TemplateCatalog.load(json);
  }
}
