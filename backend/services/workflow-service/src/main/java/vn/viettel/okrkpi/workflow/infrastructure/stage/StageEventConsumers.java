package vn.viettel.okrkpi.workflow.infrastructure.stage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.stage.StageProcessService;
import vn.vtokr.appraisal.v1.HoSoDanhGiaChuyenBuoc;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.oversight.v1.KhieuNaiDaGui;
import vn.vtokr.policy.v1.PolicyPackDaPhatHanh;

/**
 * Consumer của bốn sự kiện điều khiển mẫu phần 2 qua inbox idempotent {@code
 * flowable.inbox_processed} (sự kiện trùng không áp lại, thứ tự theo phiên bản aggregate): EVT-11
 * hồ sơ chuyển bước, EVT-13 Policy Pack đã phát hành, EVT-18 ký số đã hoàn tất, EVT-25 khiếu nại đã
 * gửi. Lỗi cấu hình tiến trình là lỗi vĩnh viễn, chuyển thư chết.
 */
@Component
@ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
public class StageEventConsumers {

  static final String PREFIX = "workflow.stage.";

  private final StageProcessService service;

  public StageEventConsumers(StageProcessService service) {
    this.service = service;
  }

  @InboxListener(topic = EventTopics.EVT_11_HO_SO_DANH_GIA_CHUYEN_BUOC, consumer = PREFIX + "evt11")
  public void onProfileStep(InboxEnvelope<HoSoDanhGiaChuyenBuoc> e) {
    HoSoDanhGiaChuyenBuoc p = e.payload();
    permanent(
        () ->
            service.onAppraisalStep(
                e.tenantId().value(),
                p.getProfileId(),
                p.getStage(),
                p.getToStatus(),
                p.getActorId()));
  }

  @InboxListener(topic = EventTopics.EVT_13_POLICY_PACK_DA_PHAT_HANH, consumer = PREFIX + "evt13")
  public void onPublished(InboxEnvelope<PolicyPackDaPhatHanh> e) {
    permanent(() -> service.onPolicyPublished(e.tenantId().value(), e.payload().getVersionId()));
  }

  @InboxListener(topic = EventTopics.EVT_18_KY_SO_DA_HOAN_TAT, consumer = PREFIX + "evt18")
  public void onSigned(InboxEnvelope<KySoDaHoanTat> e) {
    KySoDaHoanTat p = e.payload();
    permanent(
        () ->
            service.onSigned(
                e.tenantId().value(),
                p.getSourceModule(),
                p.getPurpose(),
                p.getSourceRefId(),
                e.actorId() == null ? null : e.actorId().value()));
  }

  @InboxListener(topic = EventTopics.EVT_25_KHIEU_NAI_DA_GUI, consumer = PREFIX + "evt25")
  public void onComplaint(InboxEnvelope<KhieuNaiDaGui> e) {
    KhieuNaiDaGui p = e.payload();
    permanent(
        () ->
            service.onComplaintFiled(
                e.tenantId().value(),
                p.getComplaintId(),
                p.getProfileId(),
                p.getComplainantId(),
                p.getResponseDeadline(),
                p.getAggregateVersion()));
  }

  private static void permanent(Runnable action) {
    try {
      action.run();
    } catch (ApprovalSetupException | IllegalArgumentException ex) {
      throw new PermanentInboxException("Sự kiện không áp được cho luồng: " + ex.getMessage());
    }
  }
}
