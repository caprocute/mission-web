package vn.viettel.okrkpi.evidence.infrastructure.scheduling;

import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.evidence.application.UploadService;

/**
 * Job dọn phiên tải lên quá {@code expires_at}: huỷ multipart dở dang trên RGW, đặt {@code HUY}.
 */
public class ExpiredUploadSweeper {

  static final int BATCH = 200;

  private final UploadService uploads;

  public ExpiredUploadSweeper(UploadService uploads) {
    this.uploads = uploads;
  }

  @Scheduled(
      initialDelayString = "${okrkpi.evidence.upload.sweep-interval:PT10M}",
      fixedDelayString = "${okrkpi.evidence.upload.sweep-interval:PT10M}")
  public void sweep() {
    while (uploads.expireStale(BATCH) == BATCH) {
      // Còn phiên quá hạn: dọn tiếp lô sau.
    }
  }
}
