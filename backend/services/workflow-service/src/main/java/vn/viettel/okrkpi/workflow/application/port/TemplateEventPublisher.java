package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/** Phát sự kiện "mẫu luồng đã phát hành" qua outbox trong cùng giao dịch (BS-01, đề xuất). */
public interface TemplateEventPublisher {

  void published(TemplateVersion template, List<String> cells);
}
