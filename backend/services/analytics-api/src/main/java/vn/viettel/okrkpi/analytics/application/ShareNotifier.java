package vn.viettel.okrkpi.analytics.application;

import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * Báo người nhận khi dashboard được chia sẻ (EVT-21 "Yêu cầu gửi thông báo"). Chưa hiện thực:
 * EVT-21 cần mã người nhận, analytics-api không có danh bạ đơn vị sang người dùng (câu hỏi mở ở
 * wiki/14); mặc định không làm gì.
 */
public interface ShareNotifier {

  ShareNotifier NONE = (viewer, dashboard) -> {};

  void shared(Viewer viewer, Dashboard dashboard);
}
