package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.config.application.port.UiPackEvents;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.config.v1.UiPackDaPhatHanh;

/** EVT-23 {@code config.uipack.published.v1} vào {@code config.outbox_event} (CORE-08-11). */
public class OutboxUiPackEvents implements UiPackEvents {

  /** Loại thực thể gốc của EVT-23. */
  public static final String AGGREGATE_TYPE = "ui_pack_version";

  private final OutboxWriter outbox;

  public OutboxUiPackEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void published(TenantId tenantId, UiPackVersion version, List<String> cdnPaths) {
    UiPackDaPhatHanh payload =
        UiPackDaPhatHanh.newBuilder()
            .setUiPackId(version.uiPackId())
            .setVersionId(version.id())
            .setContentHash(ByteBuffer.wrap(HexFormat.of().parseHex(version.bundleSha256())))
            .setCdnPaths(List.copyOf(cdnPaths))
            .setAggregateVersion(version.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_23_UI_PACK_DA_PHAT_HANH,
                tenantId,
                AGGREGATE_TYPE,
                version.id(),
                version.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(version.updatedBy())));
  }
}
