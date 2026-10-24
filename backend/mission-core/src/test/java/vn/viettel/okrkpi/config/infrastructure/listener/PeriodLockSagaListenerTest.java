package vn.viettel.okrkpi.config.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.PeriodLockProgressService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.sys.v1.AuditEvent;

/** QA-06: đọc vết saga chốt kỳ từ {@code sys.audit.v1}, bỏ qua bản ghi kiểm toán khác. */
class PeriodLockSagaListenerTest {

  private final PeriodLockProgressService progress = mock(PeriodLockProgressService.class);
  private final PeriodLockSagaListener listener = new PeriodLockSagaListener(progress);
  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID saga = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final Instant at = Instant.parse("2026-10-24T08:00:00Z");

  private static AuditEvent event(String action, String type, String id, String purpose) {
    return AuditEvent.newBuilder()
        .setAction(action)
        .setObjectType(type)
        .setObjectId(id)
        .setOutcome("THANH_CONG")
        .setPurpose(purpose)
        .setActorAssignmentId(null)
        .setActorRole(null)
        .setSystem(true)
        .setTargets(null)
        .build();
  }

  private InboxEnvelope<AuditEvent> envelope(AuditEvent e) {
    return new InboxEnvelope<>(
        UUID.randomUUID(),
        "sys.audit.v1",
        tenant.value() + ":" + saga,
        tenant,
        saga,
        3,
        "AuditEvent",
        Classification.C1,
        ActorId.of(actor),
        null,
        null,
        at,
        0,
        0,
        e);
  }

  @Test
  void bocBuocVaSagaChotKy() {
    AuditEvent step = event("SAGA_BUOC_XONG", "SAGA_BUOC", saga + ":2:DAT_DANG_KHOA", "CHOT_KY");
    listener.on(step, envelope(step));
    verify(progress).apply(tenant, saga, "BUOC_XONG", 2, true, actor, at);

    AuditEvent done = event("SAGA_HOAN_TAT", "SAGA", saga.toString(), "CHOT_KY");
    listener.on(done, envelope(done));
    verify(progress).apply(tenant, saga, "HOAN_TAT", null, true, actor, at);
  }

  @Test
  void boQuaKiemToanKhacVaMaHong() {
    AuditEvent other = event("DOI_QUYEN", "ROLE_BINDING", UUID.randomUUID().toString(), null);
    AuditEvent otherSaga = event("SAGA_HOAN_TAT", "SAGA", saga.toString(), "CAP_PHAT");
    AuditEvent broken = event("SAGA_BUOC_XONG", "SAGA_BUOC", "khong-phai-uuid", "CHOT_KY");
    assertThat(PeriodLockSagaListener.relevant(other)).isFalse();
    assertThat(PeriodLockSagaListener.relevant(otherSaga)).isFalse();
    assertThat(PeriodLockSagaListener.relevant("khác")).isFalse();
    listener.on(other, envelope(other));
    listener.on(otherSaga, envelope(otherSaga));
    listener.on(broken, envelope(broken));
    verify(progress, never()).apply(any(), any(), any(), any(), anyBoolean(), any(), any());
  }
}
