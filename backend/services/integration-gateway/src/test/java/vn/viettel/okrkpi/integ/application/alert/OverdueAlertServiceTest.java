package vn.viettel.okrkpi.integ.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.alert.OutboundAlerts.Failed;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class OverdueAlertServiceTest {

  final OutboundAlerts store = mock(OutboundAlerts.class);
  final AdminAlerts alerts = mock(AdminAlerts.class);
  final TransactionOperations tx = TransactionOperations.withoutTransaction();

  @Test
  void alertsEachFailedMessageOnce() {
    UUID tenant = UUID.randomUUID();
    Failed fresh = new Failed(tenant, UUID.randomUUID(), "CBCCVC");
    Failed taken = new Failed(tenant, UUID.randomUUID(), "CBCCVC");
    when(store.failedUnalerted(Set.of("CBCCVC"), 50)).thenReturn(List.of(fresh, taken));
    when(store.markAlerted(eq(TenantId.of(tenant)), any(), eq(fresh.messageId()))).thenReturn(true);
    when(store.markAlerted(eq(TenantId.of(tenant)), any(), eq(taken.messageId())))
        .thenReturn(false);

    OverdueAlertService service = new OverdueAlertService(store, alerts, Set.of("CBCCVC"), tx);

    assertThat(service.scan(50)).isEqualTo(1);
    verify(alerts).outboundOverdue(TenantId.of(tenant), "CBCCVC", fresh.messageId());
    verify(alerts, never()).outboundOverdue(any(), any(), eq(taken.messageId()));
  }

  @Test
  void nothingToWatchWithoutConnectorCodes() {
    assertThat(new OverdueAlertService(store, alerts, Set.of(), tx).scan(50)).isZero();
    verifyNoInteractions(store);
  }
}
