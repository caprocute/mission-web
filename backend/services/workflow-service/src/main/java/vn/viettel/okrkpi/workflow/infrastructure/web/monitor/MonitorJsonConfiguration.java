package vn.viettel.okrkpi.workflow.infrastructure.web.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInboxItem;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInstanceSummary;

/**
 * Bỏ trường rỗng khi tuần tự hoá phản hồi giám sát: lược đồ {@code WorkflowInstanceSummary} khai
 * {@code title}, {@code dueAt}, {@code stuckReason}... là chuỗi không nhận {@code null}, nên trường
 * vắng (bị che ở phạm vi cell, bước không hạn) phải được bỏ thay vì ghi {@code null}.
 */
@Configuration(proxyBeanMethods = false)
public class MonitorJsonConfiguration {

  @Bean
  Jackson2ObjectMapperBuilderCustomizer workflowMonitorJson() {
    return builder ->
        builder
            .mixIn(WorkflowInstanceSummary.class, NonNull.class)
            .mixIn(WorkflowInboxItem.class, NonNull.class);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  interface NonNull {}
}
