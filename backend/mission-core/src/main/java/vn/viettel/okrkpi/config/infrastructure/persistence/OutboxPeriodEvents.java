package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.PeriodEvents;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.config.v1.KyDaHuyKhoa;
import vn.vtokr.config.v1.KyDaKhoa;

/**
 * Ghi EVT-09 {@code config.period.locked.v1} khi kỳ vào {@code DA_KHOA} (chốt kỳ hoặc khoá lại sau
 * hậu kiểm) và EVT-26 {@code config.period.unlocked.v1} khi saga bù trừ đưa kỳ từ {@code DANG_KHOA}
 * về {@code MO}, vào {@code config.outbox_event} trong giao dịch của bên gọi.
 *
 * <p>Hai chuyển còn lại không có sự kiện của MOD-08 trong danh mục: {@code MO → DANG_KHOA} chỉ được
 * báo qua dòng {@code period.drain.marker} của bước 2 saga, còn {@code DA_KHOA → MO_LAI_HAU_KIEM}
 * do MOD-07 phát EVT-22 (wiki trang 14). Danh sách phiên bản Policy Pack ghim để trống tới khi
 * MOD-05 cung cấp (CORE-05-03).
 */
public class OutboxPeriodEvents implements PeriodEvents {

  static final String AGGREGATE = "period";

  private final OutboxWriter outbox;

  public OutboxPeriodEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void statusChanged(Period p, PeriodTransition t, UUID lockOrderId, String reason) {
    if (t.to() == PeriodState.DA_KHOA) {
      KyDaKhoa payload =
          KyDaKhoa.newBuilder()
              .setPeriodId(p.id().value())
              .setPeriodBucket(p.bucket())
              .setLockedAt(t.changedAt())
              .setSagaId(t.causeRef())
              .setPolicyPackVersionIds(List.of())
              .setAggregateVersion(t.rowVersion())
              .build();
      append(EventTopics.EVT_09_KY_DA_KHOA, p, t, payload);
    } else if (t.from() == PeriodState.DANG_KHOA && t.to() == PeriodState.MO) {
      KyDaHuyKhoa payload =
          KyDaHuyKhoa.newBuilder()
              .setPeriodId(p.id().value())
              .setPeriodBucket(p.bucket())
              .setLockOrderId(Objects.requireNonNull(lockOrderId, "lockOrderId"))
              .setSagaId(t.causeRef())
              .setReason(reason == null ? "" : reason)
              .setUnlockedAt(t.changedAt())
              .setAggregateVersion(t.rowVersion())
              .build();
      append(EventTopics.EVT_26_KY_DA_HUY_KHOA, p, t, payload);
    }
  }

  private void append(
      String topic, Period p, PeriodTransition t, org.apache.avro.specific.SpecificRecord payload) {
    outbox.append(
        EventEnvelope.of(
                topic,
                p.tenantId(),
                AGGREGATE,
                p.id().value(),
                t.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(t.actorId())));
  }
}
