package vn.viettel.okrkpi.scoring.infrastructure.events;

import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.scoring.application.IncrementalScoring;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;
import vn.vtokr.product.v1.MinhChungDaXacNhan;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/**
 * Bốn consumer tính dần của SVC-07 (DV-07-04) theo NEN-012: inbox idempotent {@code
 * scoring.inbox_processed} cùng giao dịch với tác dụng, thử lại có giãn cách rồi thư chết. Cùng một
 * nhóm tiêu thụ {@value #CONSUMER} cho cả bốn topic (khoá phân vùng {@code tenantId:aggregateId}
 * giữ thứ tự theo đối tượng). Consumer chỉ xếp tác vụ; đọc đầu vào và tính do worker theo lô
 * ({@code ScoreJobWorker}), nên không kiểm thứ tự phiên bản thực thể (worker luôn đọc trạng thái
 * mới nhất của lõi). EVT-13 do {@code PolicyPackPublishedConsumer} (DV-07-02) xử lý.
 */
public class IncrementalScoringConsumers {

  static final String CONSUMER = "scoring-incremental";

  /** Nhiệm vụ nháp chưa ảnh hưởng điểm. */
  static final String DRAFT = "NHAP";

  private final IncrementalScoring scoring;

  public IncrementalScoringConsumers(IncrementalScoring scoring) {
    this.scoring = scoring;
  }

  /** EVT-06 nhiệm vụ đổi trạng thái. */
  @InboxListener(topic = EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI, consumer = CONSUMER)
  public void onTaskStatusChanged(InboxEnvelope<TrangThaiNhiemVuDoi> e) {
    TrangThaiNhiemVuDoi p = e.payload();
    if (DRAFT.equals(String.valueOf(p.getToStatus()))) {
      return;
    }
    scoring.onTaskChanged(e.tenantId().value(), p.getTaskId());
  }

  /** EVT-08 minh chứng đã xác nhận. */
  @InboxListener(topic = EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN, consumer = CONSUMER)
  public void onEvidenceAccepted(InboxEnvelope<MinhChungDaXacNhan> e) {
    scoring.onTaskChanged(e.tenantId().value(), e.payload().getTaskId());
  }

  /** EVT-09 kỳ đã khoá: tính chốt kỳ. */
  @InboxListener(topic = EventTopics.EVT_09_KY_DA_KHOA, consumer = CONSUMER)
  public void onPeriodLocked(InboxEnvelope<KyDaKhoa> e) {
    KyDaKhoa p = e.payload();
    try {
      scoring.onPeriodLocked(
          e.tenantId().value(),
          e.eventId(),
          p.getPeriodId(),
          p.getPeriodBucket(),
          p.getPolicyPackVersionIds());
    } catch (IllegalArgumentException ex) {
      throw new PermanentInboxException(
          "EVT-09 không áp được cho kỳ " + p.getPeriodId() + ": " + ex.getMessage());
    }
  }

  /** EVT-22 kỳ mở lại hậu kiểm: tính lại trong phạm vi. */
  @InboxListener(topic = EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM, consumer = CONSUMER)
  public void onPeriodReopened(InboxEnvelope<KyDaMoLaiHauKiem> e) {
    KyDaMoLaiHauKiem p = e.payload();
    try {
      scoring.onPeriodReopened(
          e.tenantId().value(),
          e.eventId(),
          p.getPeriodId(),
          String.valueOf(p.getScope()),
          p.getScopeIds());
    } catch (IllegalArgumentException ex) {
      throw new PermanentInboxException(
          "EVT-22 không áp được cho kỳ " + p.getPeriodId() + ": " + ex.getMessage());
    }
  }
}
