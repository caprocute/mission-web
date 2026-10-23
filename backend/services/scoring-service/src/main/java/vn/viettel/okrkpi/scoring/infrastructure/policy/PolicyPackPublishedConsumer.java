package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPublishedHandler;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.policy.v1.PolicyPackDaPhatHanh;

/**
 * Consumer EVT-13 {@code policy.pack.published.v1} của SVC-07 (DV-07-02) qua inbox idempotent
 * {@code scoring.inbox_processed}: sự kiện trùng không nạp lại. Phiên bản có biểu thức sai kiểu hay
 * bảng hỏng là lỗi vĩnh viễn, chuyển thư chết thay vì thử lại.
 */
public class PolicyPackPublishedConsumer {

  static final String CONSUMER = "scoring.policy-pack-published";

  private final PolicyPublishedHandler handler;

  public PolicyPackPublishedConsumer(PolicyPublishedHandler handler) {
    this.handler = handler;
  }

  @InboxListener(topic = EventTopics.EVT_13_POLICY_PACK_DA_PHAT_HANH, consumer = CONSUMER)
  public void onPublished(InboxEnvelope<PolicyPackDaPhatHanh> e) {
    PolicyPackDaPhatHanh p = e.payload();
    try {
      handler.onPublished(
          new PolicyPublishedHandler.Published(
              e.tenantId().value(),
              e.eventId(),
              p.getPackId(),
              p.getVersionId(),
              hex(p.getContentHash())));
    } catch (BusinessException | IllegalArgumentException ex) {
      throw new PermanentInboxException(
          "EVT-13 không áp được cho phiên bản " + p.getVersionId() + ": " + ex.getMessage());
    }
  }

  static String hex(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
