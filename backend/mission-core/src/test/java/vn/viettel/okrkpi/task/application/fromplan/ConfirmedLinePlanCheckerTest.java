package vn.viettel.okrkpi.task.application.fromplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;

class ConfirmedLinePlanCheckerTest {

  @Test
  void themKiemTraXacNhanKhiCoTenant() {
    LeadConfirmationService confirmations = mock(LeadConfirmationService.class);
    TenantId tenant = TenantId.of(UUID.randomUUID());
    PlanChecker.Check extra =
        new PlanChecker.Check("QT-04", PlanChecker.Level.CHAN, 1, "Bị từ chối", null);
    when(confirmations.checks(eq(tenant), any())).thenReturn(List.of(extra));
    AtomicReference<TenantId> current = new AtomicReference<>();
    ConfirmedLinePlanChecker checker =
        new ConfirmedLinePlanChecker(new CelEngine(), confirmations, current::get);
    PlanChecker.Context ctx =
        new PlanChecker.Context(
            LocalDate.of(2030, 4, 1),
            LocalDate.of(2030, 6, 30),
            List.of(),
            PlanChecker.DEFAULT_GROUPS,
            List.of());
    assertThat(checker.check(List.of(), ctx)).doesNotContain(extra);
    current.set(tenant);
    assertThat(checker.check(List.of(), ctx)).contains(extra);
  }
}
