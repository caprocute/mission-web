package vn.viettel.okrkpi.evidence.infrastructure.events;

import java.util.List;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.evidence.application.ScanEvents;
import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.evidence.v1.ObjectScanned;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * {@code evidence.object.scanned.v1} (giá trị {@code verdict} theo lược đồ Avro: CLEAN, INFECTED,
 * ERROR) và EVT-21 {@code notify.message.requested.v1} báo người nộp tệp bị cách ly, qua outbox
 * {@code evidence.outbox_event}. Mẫu thông báo {@code MINH_CHUNG_CACH_LY} ứng với MSG-07-02; tham
 * số chỉ có mã đối tượng (tên tệp là C2, SVC-12 tra khi dựng thông báo, suy luận).
 */
public class OutboxScanEvents implements ScanEvents {

  static final String TEMPLATE_QUARANTINED = "MINH_CHUNG_CACH_LY";

  private final OutboxWriter outbox;

  public OutboxScanEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void scanned(StoredFile f, ScanRecord r) {
    ObjectScanned payload =
        ObjectScanned.newBuilder()
            .setObjectId(f.objectId())
            .setVersion(f.versionNo())
            .setVerdict(r.verdict().eventVerdict())
            .setEngine(r.engine())
            .setEngineVersion(r.engineVersion())
            .setScannedAt(r.scannedAt())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVIDENCE_OBJECT_SCANNED_V1,
            TenantId.of(f.tenantId()),
            OutboxFileEvents.AGGREGATE,
            f.objectId(),
            f.versionNo(),
            payload,
            Classification.C1));
  }

  @Override
  public void quarantined(StoredFile f, ScanRecord r) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(f.createdBy()))
            .setTemplateCode(TEMPLATE_QUARANTINED)
            .setParams("{\"objectId\":\"" + f.objectId() + "\",\"msg\":\"MSG-07-02\"}")
            .setDeepLink(null)
            .setPriority("CAO")
            .setAggregateVersion(f.versionNo())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            TenantId.of(f.tenantId()),
            OutboxFileEvents.AGGREGATE,
            f.objectId(),
            f.versionNo(),
            payload,
            Classification.C1));
  }
}
