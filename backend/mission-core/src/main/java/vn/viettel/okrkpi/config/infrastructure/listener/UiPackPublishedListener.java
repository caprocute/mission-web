package vn.viettel.okrkpi.config.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.config.application.UiPackPublishService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.config.v1.UiPackDaPhatHanh;

/**
 * Consumer EVT-23 {@code config.uipack.published.v1} của MOD-08 (CORE-08-11): ghi con trỏ {@code
 * ui-packs/{tenant}/current.json} rồi vô hiệu CDN. Đi qua inbox của schema config, nên giao lại
 * cùng sự kiện không ghi hai lần; bản cũ đến trễ bị bỏ vì không còn là phiên bản hiện hành.
 */
public class UiPackPublishedListener {

  /** Định danh consumer trong {@code config.inbox_processed}. */
  public static final String CONSUMER = "config.ui-pack-published";

  private final UiPackPublishService publish;

  public UiPackPublishedListener(UiPackPublishService publish) {
    this.publish = Objects.requireNonNull(publish, "publish");
  }

  public void on(UiPackDaPhatHanh event, InboxEnvelope<?> envelope) {
    TenantContext.run(
        envelope.tenantId(),
        () -> publish.onPublished(envelope.tenantId(), event.getVersionId(), event.getCdnPaths()));
  }
}
