package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.DeadLetters.Letter;
import vn.viettel.okrkpi.integ.application.port.SyncCursors;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class IntegServicesTest {

  static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
  static final TenantId TENANT =
      TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-00000013a001"));
  static final UUID CONNECTOR = UUID.randomUUID();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final TransactionOperations tx = TransactionOperations.withoutTransaction();

  @Test
  void conTroDocMacDinhVaGhiDoTre() {
    SyncCursors cursors = mock(SyncCursors.class);
    when(cursors.find(TENANT, CONNECTOR, "van-ban")).thenReturn(Optional.empty());
    SyncCursorService service = new SyncCursorService(cursors, clock, tx);

    assertThat(service.load(TENANT, CONNECTOR, "van-ban", "0")).isEqualTo("0");
    service.advance(TENANT, CONNECTOR, "van-ban", "7", NOW.minusSeconds(90));
    verify(cursors).save(TENANT, CONNECTOR, "van-ban", "7", NOW, 90);
    service.advance(TENANT, CONNECTOR, "van-ban", "8", NOW.plusSeconds(10));
    verify(cursors).save(TENANT, CONNECTOR, "van-ban", "8", NOW, 0);
    service.advance(TENANT, CONNECTOR, "van-ban", "9", null);
    verify(cursors).save(TENANT, CONNECTOR, "van-ban", "9", NOW, null);
    assertThatThrownBy(() -> service.advance(TENANT, CONNECTOR, "van-ban", "", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thuChetKiemTraThamSoVaKhongXuLyHaiLan() {
    DeadLetters letters = mock(DeadLetters.class);
    DeadLetterService service =
        new DeadLetterService(
            letters, mock(InboundPipeline.class), mock(OutboundPipeline.class), tx, clock);
    UUID actor = UUID.randomUUID();

    assertThatThrownBy(() -> service.list(TENANT, null, true, null, null, 201))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.list(TENANT, null, true, NOW, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.record(TENANT, CONNECTOR, "KHAC", UUID.randomUUID(), new Exception()))
        .isInstanceOf(IllegalArgumentException.class);

    UUID done = UUID.randomUUID();
    when(letters.find(TENANT, done))
        .thenReturn(
            Optional.of(
                new Letter(
                    done,
                    CONNECTOR,
                    DeadLetterService.OUTBOUND,
                    UUID.randomUUID(),
                    "E",
                    "E",
                    NOW,
                    actor,
                    NOW)));
    assertThatThrownBy(() -> service.replay(TENANT, done, actor))
        .isInstanceOf(IllegalStateException.class);
    when(letters.resolve(eq(TENANT), any(), eq(actor), eq(NOW))).thenReturn(false);
    assertThatThrownBy(() -> service.resolve(TENANT, done, actor))
        .isInstanceOf(IllegalStateException.class);

    UUID id = service.record(TENANT, CONNECTOR, DeadLetterService.INBOUND, UUID.randomUUID(), null);
    assertThat(id).isNotNull();
    verify(letters, never()).page(any(), any(), eq(true), any(), any(), eq(201));
  }
}
