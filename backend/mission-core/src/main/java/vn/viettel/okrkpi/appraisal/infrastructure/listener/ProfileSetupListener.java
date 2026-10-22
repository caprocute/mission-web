package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.Objects;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.ScoreComputed;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * Consumer EVT-09 {@code config.period.locked.v1} và EVT-10 {@code scoring.score.computed.v1} của
 * MOD-04 (CORE-04-02). Được gọi qua {@code InboxMessageProcessor} của schema appraisal nên khử
 * trùng lặp theo {@code event_id} và giữ thứ tự theo {@code aggregate_version} cùng một giao dịch.
 */
public class ProfileSetupListener {

  /** Định danh consumer EVT-09 trong {@code appraisal.inbox_processed}. */
  public static final String LOCKED_CONSUMER = "appraisal.period-locked";

  /** Định danh consumer EVT-10 trong {@code appraisal.inbox_processed}. */
  public static final String SCORED_CONSUMER = "appraisal.score-computed";

  private final ProfileSetupService service;

  public ProfileSetupListener(ProfileSetupService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void onLocked(KyDaKhoa event, InboxEnvelope<?> envelope) {
    service.onPeriodLocked(
        envelope.tenantId(), event.getPeriodId(), event.getPeriodBucket(), event.getLockedAt());
  }

  public void onScored(DiemDaTinh event, InboxEnvelope<?> envelope) {
    service.onScoreComputed(
        envelope.tenantId(),
        new ScoreComputed(
            event.getScoreResultId(),
            event.getProfileId(),
            event.getJobKind(),
            event.getTotalScore(),
            event.getAutoRatingCode(),
            bytes(event.getInputHash()),
            bytes(event.getPolicyContentHash()),
            envelope.occurredAt()));
  }

  static byte[] bytes(ByteBuffer b) {
    if (b == null) {
      return null;
    }
    ByteBuffer d = b.duplicate();
    byte[] out = new byte[d.remaining()];
    d.get(out);
    return out;
  }
}
