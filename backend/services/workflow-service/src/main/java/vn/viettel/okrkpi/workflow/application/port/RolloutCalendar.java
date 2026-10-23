package vn.viettel.okrkpi.workflow.application.port;

import vn.viettel.okrkpi.workflow.domain.template.RolloutWindow;

/** Lịch kỳ và giờ cao điểm dùng cho khung cấm triển khai mẫu luồng. */
public interface RolloutCalendar {

  RolloutWindow window();
}
