package vn.viettel.okrkpi.oversight.domain.reopen;

import java.time.Instant;

/**
 * Điều phối sau EVT-22 của một yêu cầu mở lại (CORE-07-09), lưu ở khoá {@code followUp} của {@code
 * scope_json}: thời điểm mọi hồ sơ trong phạm vi hoàn tất, thời điểm đã đề nghị MOD-08 khoá lại
 * sớm, và dấu "chưa hoàn tất hậu kiểm" khi hết hạn mở mà còn hồ sơ chưa xong (ngoại lệ QT-13) kèm
 * số hồ sơ còn dở.
 */
public record ReopenFollowUp(
    Instant completedAt, Instant relockRequestedAt, Instant incompleteAt, int pendingProfiles) {

  public static final ReopenFollowUp NONE = new ReopenFollowUp(null, null, null, 0);

  public boolean incomplete() {
    return incompleteAt != null;
  }

  public ReopenFollowUp relockRequested(Instant completed, Instant at) {
    return new ReopenFollowUp(completed, at, incompleteAt, 0);
  }

  public ReopenFollowUp markedIncomplete(Instant at, int pending) {
    return new ReopenFollowUp(completedAt, relockRequestedAt, at, pending);
  }
}
