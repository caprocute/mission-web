package vn.viettel.okrkpi.workflow.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowInboxPage;

/**
 * Cấu hình web: JSON bỏ thuộc tính {@code null} (thuộc tính không bắt buộc của hợp đồng vắng mặt
 * thay vì {@code null} sai kiểu), trừ {@code nextCursor} bắt buộc có mặt, kể cả khi {@code null}
 * (lược đồ {@code NextCursor}); đồng hồ hệ thống dùng chung.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

  @Bean
  Jackson2ObjectMapperBuilderCustomizer workflowJson() {
    return builder ->
        builder
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            // Giữ nguyên độ lệch múi giờ khi đọc lại phản hồi đã lưu của Idempotency-Key.
            .featuresToDisable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .mixIn(WorkflowInboxPage.class, InboxPageMixin.class);
  }

  /**
   * Sửa tài liệu {@code /v3/api-docs}: springdoc (OpenAPI 3.1) ghi lược đồ đối tượng có thuộc tính
   * {@code @Nullable} thành {@code type: null}; đổi thành {@code [object, null]} cho đúng hợp đồng.
   */
  @Bean
  OpenApiCustomizer nullableObjectSchemas() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      openApi
          .getComponents()
          .getSchemas()
          .values()
          .forEach(
              schema -> {
                if (schema.getProperties() != null
                    && schema.getTypes() != null
                    && schema.getTypes().equals(Set.of("null"))) {
                  schema.setTypes(new LinkedHashSet<>(List.of("object", "null")));
                }
              });
    };
  }

  @Bean
  @ConditionalOnMissingBean
  Clock workflowClock() {
    return Clock.systemUTC();
  }

  /** Giữ {@code nextCursor} trong JSON khi {@code null}. */
  abstract static class InboxPageMixin {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract String getNextCursor();
  }
}
