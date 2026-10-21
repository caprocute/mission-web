package vn.viettel.okrkpi.shared.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/** Dữ liệu mẫu EVT-05 (TienDoDaCapNhat) dùng chung cho kiểm thử outbox và inbox. */
public final class OutboxFixtures {

  public static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a00-0000-7000-8000-00000000000a"));

  private OutboxFixtures() {}

  public static TienDoDaCapNhat tienDo(UUID taskId, int version) {
    return TienDoDaCapNhat.newBuilder()
        .setTaskId(taskId)
        .setPeriodId(UUID.randomUUID())
        .setProgressId(UUID.randomUUID())
        .setReporterId(UUID.randomUUID())
        .setProgressPct(new BigDecimal("75.500"))
        .setReportedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
        .setAggregateVersion(version)
        .build();
  }

  public static EventEnvelope envelope(UUID taskId, int version) {
    return EventEnvelope.of(
        EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
        TENANT,
        "Task",
        taskId,
        version,
        tienDo(taskId, version),
        Classification.C1);
  }
}
