package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenFollowUpService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * Consumer điều phối hậu kiểm (CORE-07-09): EVT-10 tính lại, EVT-17 sinh lại biểu mẫu, EVT-18 ký
 * lại, EVT-09 khoá lại. Gọi qua {@code InboxMessageProcessor} của schema oversight nên khử trùng
 * lặp theo {@code event_id} và ghi tiến độ cùng một giao dịch; thứ tự giữa các sự kiện xử lý theo
 * thời điểm trong sự kiện ở {@link ReopenFollowUpService}.
 */
public class ReopenFollowUpListener {

  public static final String RECOMPUTED_CONSUMER = "oversight.reopen-recomputed";
  public static final String GENERATED_CONSUMER = "oversight.reopen-form-generated";
  public static final String SIGNED_CONSUMER = "oversight.reopen-form-signed";
  public static final String RELOCKED_CONSUMER = "oversight.reopen-relocked";

  private final ReopenFollowUpService service;

  public ReopenFollowUpListener(ReopenFollowUpService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void onRecomputed(DiemDaTinh event, InboxEnvelope<?> envelope) {
    service.onRecomputed(
        envelope.tenantId(),
        event.getProfileId(),
        event.getScoreResultId(),
        event.getJobKind(),
        envelope.occurredAt());
  }

  public void onGenerated(HoSoSoDaSinh event, InboxEnvelope<?> envelope) {
    service.onFormGenerated(
        envelope.tenantId(), event.getFormInstanceId(), event.getFormCode(), envelope.occurredAt());
  }

  public void onSigned(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    service.onSigned(
        envelope.tenantId(), event.getSourceModule(), event.getSourceRefId(), event.getSignedAt());
  }

  public void onRelocked(KyDaKhoa event, InboxEnvelope<?> envelope) {
    service.onRelocked(envelope.tenantId(), event.getSagaId(), event.getLockedAt());
  }
}
